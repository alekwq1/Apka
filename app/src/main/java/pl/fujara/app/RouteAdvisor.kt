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
import kotlin.math.roundToInt

/** Wynik analizy trasy dla nowej oferty (1.2: analiza przestrzenna + punkty do mapy). */
data class RouteAdvice(
    /** Z aktualnej pozycji do odbioru nowej oferty (OSRM/TomTom – minuty z ruchem, gdy klucz). */
    val toPickup: RouteLeg?,
    /** Po drodze / maly objazd / w przeciwna strone – wzgledem wszystkich aktywnych zlecen. */
    val spatial: SpatialVerdict?,
    /** Punkty do mini-mapy: ja, odbior/dostawa biezacych, odbior/dostawa nowej. */
    val points: List<MapPoint>,
    val computedAtMillis: Long,
    /**
     * Rzetelna analiza: aktualna lokalizacja (≤ 2 min), wszystkie odbiory i dostawy nowej
     * oferty oraz pozostale punkty zlecen w trakcie zgeokodowane. Tylko wtedy pokazujemy
     * werdykt i mape.
     */
    val verified: Boolean = true,
    val missing: List<String> = emptyList(),
    /** 1.6: plan na trasach (zamiast godzin z ekranu). */
    val plan: OfferPlan? = null,
    /** Geometria: trasa samych zlecen w trakcie (zielona) i trasa z nowa oferta. */
    val activeRoute: List<GeoPoint> = emptyList(),
    val newRoute: List<GeoPoint> = emptyList(),
    /** Ile jeszcze do konca zlecen w trakcie (trasa z lokalizacji). */
    val activeRemainingKm: Double? = null,
    val activeRemainingMinutes: Int? = null
) {
    val hasAnything: Boolean
        get() = true
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
                manager.requestLocationUpdates(provider, 5_000L, 15f, listener, Looper.getMainLooper())
            }
            listening = providers.isNotEmpty()
        }
    }

    fun stopLocationUpdates() {
        if (!listening) return
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
        listening = false
    }

    fun currentPoint(maxAgeMs: Long = 2 * 60_000L): GeoPoint? {
        val location = lastLocation ?: return null
        if (System.currentTimeMillis() - location.time > maxAgeMs) return null
        return GeoPoint(location.latitude, location.longitude)
    }

    /** Swieza pozycja (API 30+: getCurrentLocation), gdy ostatnia jest starsza niz 2 min. */
    @android.annotation.SuppressLint("MissingPermission")
    fun requestFreshLocation() {
        if (!hasLocationPermission() || currentPoint() != null) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                .forEach { provider ->
                    runCatching {
                        manager.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                            val previous = lastLocation
                            if (loc != null && (previous == null || loc.time >= previous.time)) lastLocation = loc
                        }
                    }
                }
        }
    }

    /**
     * Liczy porade dla oferty. Wynik przychodzi asynchronicznie przez [onResult] na watku
     * glownym; dla tej samej sygnatury zwraca od razu wartosc z cache.
     *
     * Kolejnosc: geokodowanie (system → Nominatim) → analiza przestrzenna po prostej
     * (natychmiast, bez API tras) → opcjonalnie czas dojazdu z OSRM/TomTom.
     */
    fun advise(
        signature: String,
        restaurant: String?,
        pickups: List<String>,
        drops: List<String>,
        activeJobs: List<ActiveJob>,
        language: String,
        onResult: (RouteAdvice) -> Unit
    ): RouteAdvice? {
        if (!enabled) return null
        adviceCache[signature]?.let { return it }
        if (!inFlight.add(signature)) return null

        requestFreshLocation()
        val profile = RouteProfile.fromKey(prefs.routeProfile)
        executor.execute {
            // Czekamy do 3 s na swieza pozycje – bez niej ocena bylaby od starego miejsca.
            var waited = 0
            while (currentPoint() == null && waited < 3_000 && hasLocationPermission()) {
                Thread.sleep(200)
                waited += 200
            }
            val origin = currentPoint()
            val advice = runCatching {
                val cityHint = prefs.routeCityHint.trim()
                val anchor = origin ?: cityCenter(cityHint)
                fun geo(query: String): GeoPoint? = geocodeFirst(listOf(query), origin, cityHint)?.takeIf { sane(it, anchor) }

                // Adres ma pierwszenstwo; sama nazwa lokalu tylko gdy adresu brak.
                val pickupQueries = pickups.ifEmpty { listOfNotNull(restaurant) }
                // Sama nazwa lokalu: tylko punkt w promieniu 12 km od kuriera (nie "KFC pod Pruszczem").
                val newPickups = if (pickups.isEmpty()) {
                    pickupQueries.mapNotNull { q -> geo(q)?.takeIf { pt -> anchor == null || Geo.haversineKm(pt, anchor) <= 12.0 } }
                } else pickupQueries.mapNotNull { q -> geo(q) }
                val newDrops = drops.mapNotNull { q -> geo(q) }
                val missing = mutableListOf<String>()
                if (origin == null) missing += tr(language, "Twojej lokalizacji", "your location", "твоя локація", "твоя геолокация")
                // 1.6: Wolt czesto pokazuje tylko nazwe lokalu – gdy znaleziona blisko (sane), wystarcza.
                if (pickups.isEmpty() && newPickups.isEmpty()) missing += tr(language, "adresu odbioru", "pickup address", "адреса забору", "адрес забора")
                else if (newPickups.size < pickups.size) missing += tr(language, "punktu odbioru na mapie", "pickup on map", "забір на мапі", "забор на карте")
                if (drops.isEmpty()) missing += tr(language, "adresu dostawy", "drop-off address", "адреса доставки", "адрес доставки")
                else if (newDrops.size < drops.size) missing += tr(language, "punktu dostawy na mapie", "drop-off on map", "доставка на мапі", "доставка на карте")
                var expectedActive = 0

                val points = mutableListOf<MapPoint>()
                origin?.let { points += MapPoint(it, tr(language, "Ja", "Me", "Я", "Я"), MapPoint.Kind.ME) }
                val activePoints = mutableListOf<GeoPoint>()
                val activeStopsOrdered = mutableListOf<Pair<GeoPoint, Boolean>>()
                activeJobs.forEach { job ->
                    var pickupNo = 0
                    var dropNo = 0
                    job.remainingAddresses().forEach { (address, isPickup) ->
                        expectedActive++
                        geo(address)?.let { pt ->
                            activePoints += pt
                            activeStopsOrdered += pt to isPickup
                            val tag = platformShort(job.platform)
                            val label = if (isPickup) {
                                pickupNo++
                                "$tag ${tr(language, "odbiór", "pickup", "забір", "забор")}" + if (pickupNo > 1) " $pickupNo" else ""
                            } else {
                                dropNo++
                                "$tag ${tr(language, "dostawa", "drop", "доставка", "доставка")}" + if (dropNo > 1) " $dropNo" else ""
                            }
                            points += MapPoint(pt, label, if (isPickup) MapPoint.Kind.ACTIVE_PICKUP else MapPoint.Kind.ACTIVE_DROP)
                        }
                    }
                }
                newPickups.forEachIndexed { i, pt ->
                    points += MapPoint(pt, tr(language, "NOWY odbiór", "NEW pickup", "НОВИЙ забір", "НОВЫЙ забор") + if (newPickups.size > 1) " ${i + 1}" else "", MapPoint.Kind.NEW_PICKUP)
                }
                newDrops.forEachIndexed { i, pt ->
                    points += MapPoint(pt, tr(language, "NOWA dostawa", "NEW drop", "НОВА доставка", "НОВАЯ доставка") + if (newDrops.size > 1) " ${i + 1}" else "", MapPoint.Kind.NEW_DROP)
                }

                if (activePoints.size < expectedActive) missing += tr(language, "adresu zlecenia w trakcie", "active job address", "адреси поточного", "адреса текущего")

                // Plan na trasach: najlepsze wplecenie nowej oferty w trase biezacych zlecen.
                val activeStops = activeStopsOrdered.map { (pt, isPickup) -> PlanStop(pt, isPickup, false, "") }
                val newStops = newPickups.map { PlanStop(it, true, true, "") } + newDrops.map { PlanStop(it, false, true, "") }
                val plan = if (origin != null && newStops.isNotEmpty()) {
                    RoutePlanner.plan(origin, activeStops, newStops) { pts -> routeMulti(pts, profile) }
                } else null
                val newLeg = newPickups + newDrops
                val spatial = if (newPickups.isNotEmpty()) SpatialAdvisor.evaluateLeg(origin, activePoints, newLeg, language) else null
                val toPickup = if (origin != null && newPickups.isNotEmpty()) route(origin, newPickups.first(), profile) else null

                RouteAdvice(
                    toPickup = toPickup,
                    spatial = spatial,
                    points = points,
                    computedAtMillis = System.currentTimeMillis(),
                    verified = missing.isEmpty(),
                    missing = missing,
                    plan = plan,
                    activeRoute = plan?.base?.geometry.orEmpty(),
                    newRoute = plan?.combined?.geometry.orEmpty(),
                    activeRemainingKm = plan?.base?.distanceKm,
                    activeRemainingMinutes = plan?.base?.minutes
                )
            }.getOrNull()
            inFlight.remove(signature)
            if (advice != null) {
                // Niepelnej analizy nie cache'ujemy – kolejny skan sprobuje ponownie.
                if (advice.verified) adviceCache[signature] = advice
                if (adviceCache.size > 60) adviceCache.keys.take(20).forEach { adviceCache.remove(it) }
                main.post { onResult(advice) }
            }
        }
        return null
    }

    /**
     * Widok samych zlecen w trakcie (bez nowej oferty): trasa z lokalizacji przez pozostale
     * punkty – odbior znika po odebraniu, zostaje dostawa.
     */
    fun adviseActive(signature: String, activeJobs: List<ActiveJob>, language: String, onResult: (RouteAdvice) -> Unit): RouteAdvice? {
        if (!enabled || activeJobs.isEmpty()) return null
        adviceCache[signature]?.let { return it }
        if (!inFlight.add(signature)) return null
        requestFreshLocation()
        val profile = RouteProfile.fromKey(prefs.routeProfile)
        executor.execute {
            var waited = 0
            while (currentPoint() == null && waited < 3_000 && hasLocationPermission()) { Thread.sleep(200); waited += 200 }
            val origin = currentPoint()
            val advice = runCatching {
                val cityHint = prefs.routeCityHint.trim()
                val anchor = origin ?: cityCenter(cityHint)
                val points = mutableListOf<MapPoint>()
                origin?.let { points += MapPoint(it, tr(language, "Ja", "Me", "Я", "Я"), MapPoint.Kind.ME) }
                val stops = mutableListOf<GeoPoint>()
                var expected = 0
                activeJobs.forEach { job ->
                    job.remainingAddresses().forEach { (address, isPickup) ->
                        expected++
                        geocodeFirst(listOf(address), origin, cityHint)?.takeIf { sane(it, anchor) }?.let { pt ->
                            stops += pt
                            val tag = platformShort(job.platform)
                            points += MapPoint(pt, if (isPickup) "$tag ${tr(language, "odbiór", "pickup", "забір", "забор")}" else "$tag ${tr(language, "dostawa", "drop", "доставка", "доставка")}",
                                if (isPickup) MapPoint.Kind.ACTIVE_PICKUP else MapPoint.Kind.ACTIVE_DROP)
                        }
                    }
                }
                val missing = mutableListOf<String>()
                if (origin == null) missing += tr(language, "Twojej lokalizacji", "your location", "твоєї локації", "геолокации")
                if (stops.size < expected) missing += tr(language, "adresu zlecenia w trakcie", "active job address", "адреси поточного", "адреса текущего")
                val route = if (origin != null && stops.isNotEmpty()) routeMulti(listOf(origin) + stops, profile) else null
                RouteAdvice(
                    toPickup = null,
                    spatial = null,
                    points = points,
                    computedAtMillis = System.currentTimeMillis(),
                    verified = missing.isEmpty(),
                    missing = missing,
                    activeRoute = route?.geometry.orEmpty(),
                    activeRemainingKm = route?.distanceKm,
                    activeRemainingMinutes = route?.minutes
                )
            }.getOrNull()
            inFlight.remove(signature)
            if (advice != null) {
                if (advice.verified) adviceCache[signature] = advice
                main.post { onResult(advice) }
            }
        }
        return null
    }

    /** Punkt z cache geokodowania (bez sieci); gdy brak – zleca geokodowanie w tle. */
    fun cachedPoint(address: String?): GeoPoint? {
        val a = address?.trim()?.takeIf { it.length >= 3 } ?: return null
        val key = a.lowercase(Locale.ROOT)
        if (geocodeCache.containsKey(key)) return geocodeCache[key]
        if (enabled) executor.execute { runCatching { geocodeFirst(listOf(a), currentPoint(), prefs.routeCityHint.trim()) } }
        return null
    }

    /**
     * Trasa przez kilka punktow z geometria: TomTom (z ruchem, gdy jest klucz) → OSRM → szacunek.
     * Cache 2 min na zestaw punktow.
     */
    fun routeMulti(points: List<GeoPoint>, profile: RouteProfile): MultiRoute {
        if (points.size < 2) return RoutePlanner.estimate(points)
        val key = points.joinToString(";") { String.format(Locale.ROOT, "%.4f,%.4f", it.lat, it.lon) } + "|" + profile.key
        multiCache[key]?.let { (at, r) -> if (System.currentTimeMillis() - at < 120_000L) return r }
        val r = prefs.tomtomApiKey.trim().takeIf { it.isNotBlank() }?.let { tomtomMulti(points, profile, it) }
            ?: osrmMulti(points, profile)
            ?: RoutePlanner.estimate(points)
        multiCache[key] = System.currentTimeMillis() to r
        if (multiCache.size > 200) multiCache.keys.take(60).forEach { multiCache.remove(it) }
        return r
    }

    private val multiCache = ConcurrentHashMap<String, Pair<Long, MultiRoute>>()

    private fun osrmMulti(points: List<GeoPoint>, profile: RouteProfile): MultiRoute? = runCatching {
        val coords = points.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lon, it.lat) }
        val url = "https://router.project-osrm.org/route/v1/${profile.osrm}/$coords?overview=full&geometries=polyline&steps=false"
        val body = httpGet(url, mapOf("User-Agent" to USER_AGENT)) ?: return null
        val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        val legs = route.getJSONArray("legs")
        val arrivals = mutableListOf<Int>()
        var acc = 0.0
        for (i in 0 until legs.length()) {
            // OSRM bez ruchu: +25% w miescie i 2 min na kazdy postoj.
            acc += legs.getJSONObject(i).getDouble("duration") / 60.0 * OSRM_CITY_FACTOR + 2.0
            arrivals += acc.roundToInt()
        }
        MultiRoute(
            distanceKm = route.getDouble("distance") / 1000.0,
            minutes = acc.roundToInt(),
            arrivalMinutes = arrivals,
            geometry = Polyline.decode(route.getString("geometry")),
            provider = "OSRM"
        )
    }.getOrNull()

    private fun tomtomMulti(points: List<GeoPoint>, profile: RouteProfile, key: String): MultiRoute? = runCatching {
        val coords = points.joinToString(":") { String.format(Locale.ROOT, "%.6f,%.6f", it.lat, it.lon) }
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$coords/json?traffic=true&travelMode=${profile.tomtom}&routeType=fastest&key=" +
            URLEncoder.encode(key, "UTF-8")
        val body = httpGet(url, emptyMap()) ?: return null
        val routeObj = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        val legs = routeObj.getJSONArray("legs")
        val arrivals = mutableListOf<Int>()
        val geometry = mutableListOf<GeoPoint>()
        var acc = 0.0
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            acc += leg.getJSONObject("summary").getDouble("travelTimeInSeconds") / 60.0 + 2.0
            arrivals += acc.roundToInt()
            val pts = leg.getJSONArray("points")
            for (j in 0 until pts.length()) {
                val p = pts.getJSONObject(j)
                geometry += GeoPoint(p.getDouble("latitude"), p.getDouble("longitude"))
            }
        }
        MultiRoute(
            distanceKm = routeObj.getJSONObject("summary").getDouble("lengthInMeters") / 1000.0,
            minutes = acc.roundToInt(),
            arrivalMinutes = arrivals,
            geometry = geometry,
            provider = "TomTom"
        )
    }.getOrNull()

    /** Odrzucamy punkty dalej niz 40 km od pozycji / miasta – to blad geokodowania. */
    private fun sane(point: GeoPoint, anchor: GeoPoint?): Boolean = anchor == null || Geo.haversineKm(point, anchor) <= 40.0

    private fun cityCenter(city: String): GeoPoint? = when (VoiceKeywordMatcher.normalize(city)) {
        "gdansk" -> GeoPoint(54.352, 18.646)
        "gdynia" -> GeoPoint(54.518, 18.531)
        "sopot" -> GeoPoint(54.441, 18.560)
        "warszawa" -> GeoPoint(52.230, 21.011)
        "krakow" -> GeoPoint(50.062, 19.937)
        "wroclaw" -> GeoPoint(51.108, 17.039)
        "poznan" -> GeoPoint(52.406, 16.925)
        "lodz" -> GeoPoint(51.759, 19.457)
        else -> null
    }

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String = Companion.tr(language, pl, en, uk, ru)

    private fun platformShort(platform: CourierPlatform): String = when (platform) {
        CourierPlatform.PYSZNE -> "Pyszne"
        CourierPlatform.WOLT -> "Wolt"
        CourierPlatform.STUART -> "Stuart"
        CourierPlatform.UBER -> "Uber"
        CourierPlatform.GLOVO -> "Glovo"
        CourierPlatform.BOLT -> "Bolt"
        CourierPlatform.GLOBAL -> ""
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
        val withCity = if (cityHint.isNotBlank() && !key.contains(cityHint.lowercase(Locale.ROOT)) && AddressKey.postalCode(query) == null) "$query, $cityHint" else query
        // Kod pocztowy z adresu jest kontrola: wynik z innym kodem to inna ulica o tej samej nazwie.
        val postal = AddressKey.postalCode(query)
        val result = geocodeSystem(withCity, near, postal) ?: geocodeNominatim(withCity, near, postal)
            ?: postal?.let { geocodeSystem(withCity, near, null) ?: geocodeNominatim(withCity, near, null) }
        geocodeCache[key] = result
        if (geocodeCache.size > 400) geocodeCache.keys.take(100).forEach { geocodeCache.remove(it) }
        return result
    }

    @Suppress("DEPRECATION")
    private fun geocodeSystem(query: String, near: GeoPoint?, postal: String?): GeoPoint? {
        if (!Geocoder.isPresent()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Na API 33 synchroniczna wersja nadal dziala, a asynchroniczna wymagalaby
            // dodatkowego oczekiwania; wykonujemy to na watku tla.
        }
        return runCatching {
            val geocoder = Geocoder(context, Locale("pl", "PL"))
            val results = if (near != null) {
                geocoder.getFromLocationName(query, 5, near.lat - 0.35, near.lon - 0.5, near.lat + 0.35, near.lon + 0.5)
            } else {
                geocoder.getFromLocationName(query, 5)
            }
            results?.filter { it.hasLatitude() && it.hasLongitude() }
                ?.firstOrNull { postal == null || it.postalCode == null || it.postalCode == postal }
                ?.let { GeoPoint(it.latitude, it.longitude) }
        }.getOrNull()
    }

    private fun geocodeNominatim(query: String, near: GeoPoint?, postal: String?): GeoPoint? = runCatching {
        val viewbox = near?.let { "&viewbox=${it.lon - 0.5},${it.lat + 0.35},${it.lon + 0.5},${it.lat - 0.35}&bounded=0" }.orEmpty()
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1&countrycodes=pl&q=" +
            URLEncoder.encode(query, "UTF-8") + viewbox
        val body = httpGet(url, mapOf("User-Agent" to USER_AGENT, "Accept-Language" to "pl")) ?: return null
        val array = org.json.JSONArray(body)
        if (array.length() == 0) return null
        var chosen: JSONObject? = null
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val pc = o.optJSONObject("address")?.optString("postcode").orEmpty()
            if (postal == null || pc.isBlank() || pc == postal) { chosen = o; break }
        }
        val pick = chosen ?: return null
        GeoPoint(pick.getString("lat").toDouble(), pick.getString("lon").toDouble())
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
        const val USER_AGENT = "FUJARA/1.1 (Android courier assistant; local use)"
        /** OSRM liczy czas bez ruchu; w miescie realny czas jest zwykle ~25% dluzszy. */
        private const val OSRM_CITY_FACTOR = 1.25

        fun haversineKm(a: GeoPoint, b: GeoPoint): Double = Geo.haversineKm(a, b)

        fun describe(advice: RouteAdvice, language: String): String {
            val parts = buildList {
                advice.toPickup?.let { leg ->
                    val traffic = leg.trafficDelayMinutes?.takeIf { it >= 2 }?.let { " (+$it ${tr(language, "ruch", "traffic", "рух", "трафик")})" }.orEmpty()
                    add("${tr(language, "do odbioru", "to pickup", "до забору", "до забора")} ${String.format(Locale.forLanguageTag("pl-PL"), "%.1f", leg.distanceKm)} km · ${leg.minutes} min$traffic")
                }
                advice.spatial?.fromActiveEndKm?.let { km ->
                    add("${tr(language, "od końca bieżącego", "from current end", "від кінця поточного", "от конца текущего")} ${String.format(Locale.forLanguageTag("pl-PL"), "%.1f", km)} km")
                }
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
