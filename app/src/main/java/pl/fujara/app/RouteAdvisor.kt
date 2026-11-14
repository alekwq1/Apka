package pl.fujara.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

data class RouteLeg(
    val distanceKm: Double,
    val minutes: Int,
    /** Opoznienie z powodu ruchu (tylko dostawcy z danymi o ruchu, np. TomTom). */
    val trafficDelayMinutes: Int?,
    val provider: String
)

/** Wynik analizy trasy dla nowej oferty. */
data class RouteAdvice(
    /** Z aktualnej pozycji do odbioru nowej oferty. */
    val toPickup: RouteLeg?,
    /** Z odbioru do klienta nowej oferty (gdy znamy oba adresy). */
    val pickupToDrop: RouteLeg?,
    /** Z miejsca zakonczenia biezacego zlecenia do nowego odbioru. */
    val fromCurrentJobEnd: RouteLeg?,
    val pickupPoint: GeoPoint?,
    val computedAtMillis: Long
) {
    val hasAnything: Boolean
        get() = toPickup != null || pickupToDrop != null || fromCurrentJobEnd != null
}

enum class RouteProfile(val key: String, val osrm: String, val tomtom: String, val avgKmh: Double) {
    CAR("car", "driving", "car", 28.0),
    SCOOTER("scooter", "driving", "motorcycle", 26.0),
    BIKE("bike", "cycling", "bicycle", 15.0);

    companion object {
        fun fromKey(key: String?): RouteProfile = entries.firstOrNull { it.key == key } ?: CAR
    }
}

/**
 * Opcjonalny modul tras (wymaga zgody na lokalizacje i internet w ustawieniach).
 *
 *  - lokalizacja: LocationManager (bez Google Play Services),
 *  - geokodowanie: systemowy Geocoder, fallback Nominatim (OpenStreetMap),
 *  - trasa: TomTom Routing z ruchem drogowym (klucz uzytkownika, darmowy limit dzienny)
 *    albo publiczny OSRM bez danych o ruchu.
 *
 * Zapytania sa cache'owane i ograniczane, aby nie zalewac API przy skanie co 1,2 s.
 */
class RouteAdvisor(private val context: Context, private val prefs: AppPrefs) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fujara-routes").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val geocodeCache = ConcurrentHashMap<String, GeoPoint?>()
    private val routeCache = ConcurrentHashMap<String, Pair<Long, RouteLeg>>()
    private val adviceCache = ConcurrentHashMap<String, RouteAdvice>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastLocation: Location? = null
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    val enabled: Boolean
        get() = prefs.routesEnabled && hasLocationPermission()

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Lekkie nasluchiwanie pozycji tylko, gdy modul jest wlaczony. */
    fun startLocationUpdates() {
        if (listening || !enabled) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            providers.forEach { provider ->
                manager.getLastKnownLocation(provider)?.let { candidate ->
                    val current = lastLocation
                    if (current == null || candidate.time > current.time) lastLocation = candidate
                }
                manager.requestLocationUpdates(provider, 20_000L, 60f, listener, Looper.getMainLooper())
            }
            listening = providers.isNotEmpty()
        }
    }

    fun stopLocationUpdates() {
        if (!listening) return
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
        listening = false
    }

    fun currentPoint(): GeoPoint? {
        val location = lastLocation ?: return null
        if (System.currentTimeMillis() - location.time > 15 * 60_000L) return null
        return GeoPoint(location.latitude, location.longitude)
    }

    /**
     * Liczy porade dla oferty. Wynik przychodzi asynchronicznie przez [onResult] na watku
     * glownym; dla tej samej sygnatury zwraca od razu wartosc z cache.
     */
    fun advise(
        signature: String,
        restaurant: String?,
        pickupAddress: String?,
        dropoffAddress: String?,
        activeJob: ActiveJob?,
        onResult: (RouteAdvice) -> Unit
    ): RouteAdvice? {
        if (!enabled) return null
        adviceCache[signature]?.let { return it }
        if (!inFlight.add(signature)) return null

        val origin = currentPoint()
        val profile = RouteProfile.fromKey(prefs.routeProfile)
        executor.execute {
            val advice = runCatching {
                val cityHint = prefs.routeCityHint.trim()
                val pickupPoint = geocodeFirst(listOfNotNull(pickupAddress, restaurant), origin, cityHint)
                val dropPoint = dropoffAddress?.let { geocodeFirst(listOf(it), origin, cityHint) }
                val jobEndPoint = activeJob?.let { job ->
                    val target = if (job.stage == ActiveJob.Stage.TO_PICKUP) job.dropoffAddress ?: job.pickupAddress else job.dropoffAddress
                    target?.let { geocodeFirst(listOf(it), origin, cityHint) }
                }

                RouteAdvice(
                    toPickup = if (origin != null && pickupPoint != null) route(origin, pickupPoint, profile) else null,
                    pickupToDrop = if (pickupPoint != null && dropPoint != null) route(pickupPoint, dropPoint, profile) else null,
                    fromCurrentJobEnd = if (jobEndPoint != null && pickupPoint != null) route(jobEndPoint, pickupPoint, profile) else null,
                    pickupPoint = pickupPoint,
                    computedAtMillis = System.currentTimeMillis()
                )
            }.getOrNull()
            inFlight.remove(signature)
            if (advice != null && advice.hasAnything) {
                adviceCache[signature] = advice
                if (adviceCache.size > 60) adviceCache.keys.take(20).forEach { adviceCache.remove(it) }
                main.post { onResult(advice) }
            }
        }
        return null
    }

    fun shutdown() {
        stopLocationUpdates()
        executor.shutdownNow()
    }

    // ---- geocoding -----------------------------------------------------------------

    private fun geocodeFirst(queries: List<String>, near: GeoPoint?, cityHint: String): GeoPoint? {
        for (query in queries) {
            val clean = query.trim().takeIf { it.length >= 3 } ?: continue
            geocode(clean, near, cityHint)?.let { return it }
        }
        return null
    }

    private fun geocode(query: String, near: GeoPoint?, cityHint: String): GeoPoint? {
        val key = query.lowercase(Locale.ROOT)
        if (geocodeCache.containsKey(key)) return geocodeCache[key]
        val withCity = if (cityHint.isNotBlank() && !key.contains(cityHint.lowercase(Locale.ROOT))) "$query, $cityHint" else query
        val result = geocodeSystem(withCity, near) ?: geocodeNominatim(withCity, near)
        geocodeCache[key] = result
        if (geocodeCache.size > 400) geocodeCache.keys.take(100).forEach { geocodeCache.remove(it) }
        return result
    }

    @Suppress("DEPRECATION")
    private fun geocodeSystem(query: String, near: GeoPoint?): GeoPoint? {
        if (!Geocoder.isPresent()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Na API 33 synchroniczna wersja nadal dziala, a asynchroniczna wymagalaby
            // dodatkowego oczekiwania; wykonujemy to na watku tla.
        }
        return runCatching {
            val geocoder = Geocoder(context, Locale("pl", "PL"))
            val results = if (near != null) {
                geocoder.getFromLocationName(query, 3, near.lat - 0.35, near.lon - 0.5, near.lat + 0.35, near.lon + 0.5)
            } else {
                geocoder.getFromLocationName(query, 3)
            }
            results?.firstOrNull { it.hasLatitude() && it.hasLongitude() }?.let { GeoPoint(it.latitude, it.longitude) }
        }.getOrNull()
    }

    private fun geocodeNominatim(query: String, near: GeoPoint?): GeoPoint? = runCatching {
        val viewbox = near?.let { "&viewbox=${it.lon - 0.5},${it.lat + 0.35},${it.lon + 0.5},${it.lat - 0.35}&bounded=0" }.orEmpty()
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=pl&q=" +
            URLEncoder.encode(query, "UTF-8") + viewbox
        val body = httpGet(url, mapOf("User-Agent" to USER_AGENT, "Accept-Language" to "pl")) ?: return null
        val array = org.json.JSONArray(body)
        if (array.length() == 0) return null
        val first = array.getJSONObject(0)
        GeoPoint(first.getString("lat").toDouble(), first.getString("lon").toDouble())
    }.getOrNull()

    // ---- routing -------------------------------------------------------------------

    private fun route(from: GeoPoint, to: GeoPoint, profile: RouteProfile): RouteLeg? {
        val key = String.format(Locale.ROOT, "%.4f,%.4f>%.4f,%.4f|%s", from.lat, from.lon, to.lat, to.lon, profile.key)
        routeCache[key]?.let { (at, leg) -> if (System.currentTimeMillis() - at < 120_000L) return leg }
        val leg = (prefs.tomtomApiKey.trim().takeIf { it.isNotBlank() }?.let { routeTomTom(from, to, profile, it) }
            ?: routeOsrm(from, to, profile)
            ?: straightLineEstimate(from, to, profile))
        routeCache[key] = System.currentTimeMillis() to leg
        if (routeCache.size > 200) routeCache.keys.take(50).forEach { routeCache.remove(it) }
        return leg
    }

    private fun routeTomTom(from: GeoPoint, to: GeoPoint, profile: RouteProfile, key: String): RouteLeg? = runCatching {
        val url = "https://api.tomtom.com/routing/1/calculateRoute/" +
            String.format(Locale.ROOT, "%.6f,%.6f:%.6f,%.6f", from.lat, from.lon, to.lat, to.lon) +
            "/json?traffic=true&travelMode=${profile.tomtom}&routeType=fastest&key=" + URLEncoder.encode(key, "UTF-8")
        val body = httpGet(url, emptyMap()) ?: return null
        val summary = JSONObject(body).getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        RouteLeg(
            distanceKm = summary.getDouble("lengthInMeters") / 1000.0,
            minutes = (summary.getDouble("travelTimeInSeconds") / 60.0).roundToInt().coerceAtLeast(1),
            trafficDelayMinutes = (summary.optDouble("trafficDelayInSeconds", 0.0) / 60.0).roundToInt(),
            provider = "TomTom"
        )
    }.getOrNull()

    private fun routeOsrm(from: GeoPoint, to: GeoPoint, profile: RouteProfile): RouteLeg? = runCatching {
        val url = "https://router.project-osrm.org/route/v1/${profile.osrm}/" +
            String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat) + "?overview=false"
        val body = httpGet(url, mapOf("User-Agent" to USER_AGENT)) ?: return null
        val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        RouteLeg(
            distanceKm = route.getDouble("distance") / 1000.0,
            minutes = (route.getDouble("duration") / 60.0 * OSRM_CITY_FACTOR).roundToInt().coerceAtLeast(1),
            trafficDelayMinutes = null,
            provider = "OSRM"
        )
    }.getOrNull()

    private fun straightLineEstimate(from: GeoPoint, to: GeoPoint, profile: RouteProfile): RouteLeg {
        val km = haversineKm(from, to) * 1.3
        return RouteLeg(
            distanceKm = km,
            minutes = (km / profile.avgKmh * 60.0).roundToInt().coerceAtLeast(1),
            trafficDelayMinutes = null,
            provider = "szacunek"
        )
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "GET"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT = "FUJARA/1.1 (Android courier assistant; local use)"
        /** OSRM liczy czas bez ruchu; w miescie realny czas jest zwykle ~25% dluzszy. */
        private const val OSRM_CITY_FACTOR = 1.25

        fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }

        fun describe(advice: RouteAdvice, language: String): String {
            fun leg(prefix: String, leg: RouteLeg): String {
                val traffic = leg.trafficDelayMinutes?.takeIf { it >= 2 }?.let { " (+$it ${tr(language, "ruch", "traffic", "рух", "трафик")})" }.orEmpty()
                return "$prefix ${String.format(Locale.forLanguageTag("pl-PL"), "%.1f", leg.distanceKm)} km · ${leg.minutes} min$traffic"
            }
            val parts = buildList {
                advice.toPickup?.let { add(leg(tr(language, "do odbioru", "to pickup", "до забору", "до забора"), it)) }
                advice.fromCurrentJobEnd?.let { add(leg(tr(language, "po bieżącym", "after current", "після поточного", "после текущего"), it)) }
                advice.pickupToDrop?.let { add(leg(tr(language, "do klienta", "to customer", "до клієнта", "до клиента"), it)) }
            }
            return parts.joinToString("  •  ")
        }

        private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String = when (language) {
            "en" -> en
            "uk" -> uk
            "ru" -> ru
            else -> pl
        }
    }
}
