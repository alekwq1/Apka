package pl.fujara.app

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 1.6: ocena oferty na podstawie TRASY (z Twojej lokalizacji przez pozostale punkty zlecen
 * w trakcie), a nie godzin "Dostarcz na" z ekranu Pyszne, ktore sa tylko orientacyjne.
 */

/** Trasa przez kilka punktow: dystans, czas, czasy kolejnych odcinkow i geometria do mapy. */
data class MultiRoute(
    val distanceKm: Double,
    val minutes: Int,
    /** Czas dojazdu do kazdego kolejnego punktu (bez punktu startowego), w minutach od startu. */
    val arrivalMinutes: List<Int>,
    val geometry: List<GeoPoint>,
    val provider: String
)

/** Punkt trasy z informacja, czy to odbior/dostawa i do ktorego zlecenia nalezy. */
data class PlanStop(
    val point: GeoPoint,
    val isPickup: Boolean,
    val isNew: Boolean,
    val label: String
)

/** Wynik planowania: biezace zlecenia vs biezace + nowa oferta. */
data class OfferPlan(
    val base: MultiRoute?,
    val combined: MultiRoute,
    val combinedStops: List<PlanStop>,
    /** Ile km i minut dokladam ponad to, co i tak musialbym przejechac dla nowej oferty osobno. */
    val detourKm: Double,
    val addedMinutes: Int,
    val verdict: SpatialVerdict.Verdict,
    /** Za ile minut bede przy nowym odbiorze (licząc trase przez punkty biezace). */
    val minutesToNewPickup: Int,
    /** Za ile minut skoncze wszystkie BIEZACE zlecenia (sama trasa, bez nowej oferty). */
    val minutesToFinishCurrent: Int?
)

object RoutePlanner {
    /** Szacunek bez API tras: prosta × 1,3, srednio 25 km/h w miescie + 2 min na kazdy postoj. */
    fun estimate(points: List<GeoPoint>): MultiRoute {
        var km = 0.0
        val arrivals = mutableListOf<Int>()
        var minutes = 0.0
        points.zipWithNext().forEach { (a, b) ->
            val leg = Geo.haversineKm(a, b) * 1.3
            km += leg
            minutes += leg / 25.0 * 60.0 + 2.0
            arrivals += minutes.roundToInt()
        }
        return MultiRoute(km, minutes.roundToInt(), arrivals, points, "szacunek")
    }

    /**
     * Najlepsze wplecenie nowej oferty (odbiory, potem dostawy – w kolejnosci) w trase biezaca.
     * [route] liczy trase przez podane punkty (OSRM/TomTom albo szacunek).
     */
    fun plan(
        me: GeoPoint,
        active: List<PlanStop>,
        newStops: List<PlanStop>,
        route: (List<GeoPoint>) -> MultiRoute
    ): OfferPlan {
        val base = if (active.isNotEmpty()) route(listOf(me) + active.map { it.point }) else null
        var best: Pair<MultiRoute, List<PlanStop>>? = null
        for (insertAt in 0..active.size) {
            val stops = active.take(insertAt) + newStops + active.drop(insertAt)
            val r = route(listOf(me) + stops.map { it.point })
            if (best == null || r.minutes < best.first.minutes || (r.minutes == best.first.minutes && r.distanceKm < best.first.distanceKm)) {
                best = r to stops
            }
        }
        val (combined, stops) = best!!
        val ownKm = if (newStops.size >= 2) estimate(newStops.map { it.point }).distanceKm else 0.0
        val baseKm = base?.distanceKm ?: 0.0
        val baseMin = base?.minutes ?: 0
        val detour = (combined.distanceKm - baseKm - ownKm).coerceAtLeast(0.0)
        val added = (combined.minutes - baseMin).coerceAtLeast(0)
        val firstNewIdx = stops.indexOfFirst { it.isNew }
        val toNewPickup = combined.arrivalMinutes.getOrNull(firstNewIdx) ?: combined.minutes
        val verdict = when {
            active.isEmpty() -> SpatialVerdict.Verdict.UNKNOWN
            detour <= 1.5 -> SpatialVerdict.Verdict.ON_THE_WAY
            detour <= 4.0 -> SpatialVerdict.Verdict.SMALL_DETOUR
            else -> SpatialVerdict.Verdict.OPPOSITE
        }
        return OfferPlan(base, combined, stops, detour, added, verdict, toNewPickup, base?.minutes)
    }

    fun describe(plan: OfferPlan, newPickupMinuteOfDay: Int?, nowMinuteOfDay: Int, language: String): String {
        fun f(v: Double) = String.format(Locale.forLanguageTag("pl-PL"), "%.1f", v)
        val head = when (plan.verdict) {
            SpatialVerdict.Verdict.ON_THE_WAY -> tr(language, "PO DRODZE", "ON THE WAY", "ПО ДОРОЗІ", "ПО ПУТИ")
            SpatialVerdict.Verdict.SMALL_DETOUR -> tr(language, "MAŁY OBJAZD", "SMALL DETOUR", "НЕВЕЛИКИЙ ОБ'ЇЗД", "НЕБОЛЬШОЙ ОБЪЕЗД")
            SpatialVerdict.Verdict.OPPOSITE -> tr(language, "W PRZECIWNĄ STRONĘ", "WRONG DIRECTION", "У ПРОТИЛЕЖНИЙ БІК", "В ДРУГУЮ СТОРОНУ")
            SpatialVerdict.Verdict.UNKNOWN -> tr(language, "BRAK ZLECEŃ W TRAKCIE", "NO JOB IN PROGRESS", "НЕМАЄ ЗАМОВЛЕНЬ", "НЕТ ЗАКАЗОВ")
        }
        val body = if (plan.base == null) {
            tr(language,
                "cała trasa ${f(plan.combined.distanceKm)} km · ${plan.combined.minutes} min",
                "whole route ${f(plan.combined.distanceKm)} km · ${plan.combined.minutes} min",
                "маршрут ${f(plan.combined.distanceKm)} км · ${plan.combined.minutes} хв",
                "маршрут ${f(plan.combined.distanceKm)} км · ${plan.combined.minutes} мин")
        } else {
            tr(language,
                "+${f(plan.detourKm)} km objazdu · +${plan.addedMinutes} min (razem ${f(plan.combined.distanceKm)} km zamiast ${f(plan.base.distanceKm)})",
                "+${f(plan.detourKm)} km detour · +${plan.addedMinutes} min (total ${f(plan.combined.distanceKm)} km vs ${f(plan.base.distanceKm)})",
                "+${f(plan.detourKm)} км · +${plan.addedMinutes} хв",
                "+${f(plan.detourKm)} км · +${plan.addedMinutes} мин")
        }
        val pickupNote = newPickupMinuteOfDay?.let { pickup ->
            var target = pickup
            if (target < nowMinuteOfDay - 12 * 60) target += 1440
            val slack = target - (nowMinuteOfDay + plan.minutesToNewPickup)
            val clock = String.format(Locale.ROOT, "%02d:%02d", ((nowMinuteOfDay + plan.minutesToNewPickup) / 60) % 24, (nowMinuteOfDay + plan.minutesToNewPickup) % 60)
            "\n" + tr(language,
                "Przy odbiorze ~$clock (${if (slack >= 0) "+" else ""}$slack min wzgl. Pyszne)",
                "At pickup ~$clock (${if (slack >= 0) "+" else ""}$slack min vs app)",
                "Біля забору ~$clock", "У забора ~$clock")
        }.orEmpty()
        return "$head: $body$pickupNote"
    }

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String = when (language) {
        "en" -> en
        "uk" -> uk
        "ru" -> ru
        else -> pl
    }
}

/** Dekoder geometrii "encoded polyline" (OSRM, precyzja 5). */
object Polyline {
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out += GeoPoint(lat / factor, lon / factor)
        }
        return out
    }
}

/** Porownywanie adresow niezaleznie od polskich znakow, wielkosci liter i dopiskow "Polska". */
object AddressKey {
    private val postal = Regex("""\d{2}-\d{3}""")

    fun of(address: String): String {
        val base = Normalizer.normalize(address.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace('ł', 'l')
            .replace(Regex("""(?i)\b(?:ul|al|pl)\.?\s+"""), "")
            .replace(Regex("""\bpolska\b"""), "")
        // Ulica + numer (pierwszy czlon przed przecinkiem) wystarcza do rozroznienia.
        var first = base.substringBefore(',')
        postal.find(first)?.let { first = first.substring(0, it.range.first) }
        // "granitowa 6 gdansk" -> "granitowa 6"
        Regex("""^(.*?\d+(?:[a-z](?![a-z]))?(?:\s*/\s*\d+(?:[a-z](?![a-z]))?)?)(?![0-9])""").find(first.trim())?.let { first = it.groupValues[1] }
        return first.replace(Regex("""[^a-z0-9/]"""), "")
    }

    fun postalCode(address: String): String? = postal.find(address)?.value

    fun distinct(addresses: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return addresses.filter { seen.add(of(it)) }
    }
}

/** Teksty techniczne z drzewa dostepnosci, ktore nie sa nazwa lokalu ("card copy orderId button"). */
object UiNoise {
    private val technical = Regex("""(?i)(?<!\p{L})(?:button|card|copy|icon|image|imageview|textview|orderid|view|layout|toolbar|chevron|arrow|badge)(?!\p{L})""")
    private val camel = Regex("""\p{Ll}\p{Lu}""")

    fun isNoise(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return true
        return technical.containsMatchIn(t) || (camel.containsMatchIn(t) && t.contains(' ').not() && t.length > 6) ||
            Regex("""(?i)^pyszne\.?pl""").containsMatchIn(t)
    }
}

/**
 * Postep zlecenia z lokalizacji: bylem przy odbiorze i odjechalem → odebrane;
 * bylem przy kliencie i odjechalem → dostarczone. Dziala tez, gdy aplikacja kurierska
 * nie pokazala ekranu "Dostarcz zamówienie" albo klikniecie nie zostalo wykryte.
 */
object JobProgress {
    const val NEAR_PICKUP_M = 150.0
    const val NEAR_DROP_M = 130.0
    const val LEFT_M = 450.0

    data class Update(val job: ActiveJob, val finished: Boolean, val changed: Boolean)

    fun update(job: ActiveJob, me: GeoPoint?, pickup: GeoPoint?, drop: GeoPoint?, nowMillis: Long): Update {
        if (me == null) return Update(job, finished = false, changed = false)
        var j = job
        var changed = false
        if (j.stage == ActiveJob.Stage.TO_PICKUP && pickup != null) {
            val d = Geo.haversineKm(me, pickup) * 1000.0
            if (d <= NEAR_PICKUP_M && j.nearPickupAtMillis == null) {
                j = j.copy(nearPickupAtMillis = nowMillis); changed = true
            }
            if (j.nearPickupAtMillis != null && d >= LEFT_M) {
                j = j.copy(stage = ActiveJob.Stage.TO_CUSTOMER, pickedUpAtMillis = j.pickedUpAtMillis ?: nowMillis); changed = true
            }
        }
        if (j.stage == ActiveJob.Stage.TO_CUSTOMER && drop != null) {
            val d = Geo.haversineKm(me, drop) * 1000.0
            if (d <= NEAR_DROP_M && j.nearDropAtMillis == null) {
                j = j.copy(nearDropAtMillis = nowMillis); changed = true
            }
            // Bylem u klienta co najmniej 30 s i odjechalem – dostarczone.
            val near = j.nearDropAtMillis
            if (near != null && d >= LEFT_M && nowMillis - near >= 30_000L) {
                return Update(j, finished = true, changed = true)
            }
        }
        return Update(j, finished = false, changed = changed)
    }
}
