package pl.fujara.app

import java.util.Locale

/** Co widac na ekranie aplikacji kurierskiej – potrzebne do decyzji i aktywnego zlecenia. */
enum class ScreenKind {
    /** Karta nowej oferty z przyciskiem Akceptuj / Zaakceptuj zlecenie / Jestem zainteresowany. */
    OFFER_CARD,
    /** Aktywne zlecenie: Odbierz na / Dostarcz na / Nawiguj bez przycisku akceptacji. */
    ACTIVE_JOB,
    /** Szczegoly zakonczonego zlecenia (historia Pyszne). */
    HISTORY_DETAILS,
    OTHER
}

object ScreenClassifier {
    private val acceptMarker = Regex(
        """(?i)(?<!\p{L})(?:akceptuj|zaakceptuj|accept|jestem\s+zainteresowany|przyjmij|прийняти|принять)(?!\p{L})"""
    )
    private val countdownMarker = Regex("""(?i)sekund\s+do\s+automatycznego\s+odrzucenia|seconds?\s+(?:to|until)\s+auto""")
    private val navigateMarker = Regex("""(?i)(?<!\p{L})(?:nawiguj|navigate|навігувати|навигация)(?!\p{L})""")
    private val scheduleMarker = Regex("""(?i)(?:odbierz|dostarcz|pickup|deliver)\w*\s+(?:na|by|at|do)\s+\d{1,2}[:.]\d{2}""")
    private val historyMarker = Regex("""(?i)szczeg[oó][lł]y\s+zlecenia|order\s+details|zlecenie\s+dostarczone|zlecenie\s+anulowane|czas\s+aktywno[sś]ci""")
    private val inProgressMarker = Regex("""(?i)w\s+drodze|dostarcz\s+do|odebrano|picked\s+up|zad[zź]wo[nń]|gotowe(?!\p{L})""")

    fun classify(text: String): ScreenKind {
        if (text.isBlank()) return ScreenKind.OTHER
        if (historyMarker.containsMatchIn(text) && !acceptMarker.containsMatchIn(text)) return ScreenKind.HISTORY_DETAILS
        if (acceptMarker.containsMatchIn(text) || countdownMarker.containsMatchIn(text)) return ScreenKind.OFFER_CARD
        if (navigateMarker.containsMatchIn(text) && (scheduleMarker.containsMatchIn(text) || inProgressMarker.containsMatchIn(text))) {
            return ScreenKind.ACTIVE_JOB
        }
        return ScreenKind.OTHER
    }
}

/** Adresy i nazwa lokalu z karty Pyszne (oferta lub aktywne zlecenie). */
data class PyszneOfferDetails(
    val restaurant: String?,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val stops: Int
)

object PyszneOfferDetailsParser {
    private val pickupLabel = Regex("""(?i)^(?:odbi[oó]r|pickup)\s*[:\-]?\s*(.*)$""")
    private val dropoffLabel = Regex("""(?i)^(?:dostawa\s+do\s+klienta|dostawa|delivery|dostarcz\s+do)\s*[:\-]?\s*(.*)$""")
    private val addressLine = Regex("""(?i)^[\p{L}\p{N} .'\-/]{2,60}\d[\p{L}\p{N} /\-]*,\s*\d{2}-\d{3}\s+[\p{L} \-]{2,40}$|^[\p{L}\p{N} .'\-/]{3,60}\s\d{1,4}[a-zA-Z]?(?:/\d{1,4})?(?:,.*)?$""")
    private val stopsMarker = Regex("""(?i)(\d)\s*przystank""")
    private val timeLine = Regex("""(?i)^(?:odbierz|dostarcz)\w*\s+(?:na|do)\s+\d""")

    fun parse(text: String): PyszneOfferDetails {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        var restaurant: String? = null
        var pickupAddress: String? = null
        var dropoffAddress: String? = null

        lines.forEachIndexed { index, line ->
            pickupLabel.find(line)?.let { match ->
                val inline = match.groupValues[1].trim()
                val candidate = inline.ifBlank { lines.getOrNull(index + 1).orEmpty() }
                if (candidate.isNotBlank() && !timeLine.containsMatchIn(candidate) && restaurant == null) {
                    restaurant = candidate.take(80)
                    val next = lines.drop(index + if (inline.isBlank()) 2 else 1).take(2).firstOrNull { addressLine.matches(it) }
                    pickupAddress = next
                }
            }
            dropoffLabel.find(line)?.let {
                if (dropoffAddress == null) {
                    dropoffAddress = lines.drop(index + 1).take(2).firstOrNull { addressLine.matches(it) && !timeLine.containsMatchIn(it) }
                }
            }
        }

        val stops = stopsMarker.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 1
        return PyszneOfferDetails(
            restaurant = restaurant?.let { PyszneHistoryParser.normalizeRestaurant(it).ifBlank { null } }?.let { restaurant },
            pickupAddress = pickupAddress,
            dropoffAddress = dropoffAddress,
            stops = stops
        )
    }
}

/**
 * Aktywne zlecenie kuriera (po akceptacji). Utrzymywane w pamieci serwisu.
 * Dzieki temu przy kolejnej ofercie mozemy ocenic: czy zdaze, czy po drodze.
 */
data class ActiveJob(
    val platform: CourierPlatform,
    val acceptedAtMillis: Long,
    val restaurant: String?,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val pickupMinuteOfDay: Int?,
    val deliveryMinuteOfDay: Int?,
    /** Szacowany czas pelnej dostawy z oferty (Wolt/Stuart/Uber), gdy brak godzin. */
    val estimatedMinutes: Int?,
    val distanceKm: Double,
    val amountPln: Double,
    val stops: Int = 1,
    val stage: Stage = Stage.TO_PICKUP,
    val lastSeenAtMillis: Long = acceptedAtMillis
) {
    enum class Stage { TO_PICKUP, TO_CUSTOMER }

    /** Minuta dnia, o ktorej powinienem skonczyc biezace zlecenie. */
    fun expectedFinishMinuteOfDay(currentMinuteOfDay: Int): Int {
        deliveryMinuteOfDay?.let { return normalizeAfter(it, currentMinuteOfDay) }
        val startMinute = acceptedMinuteOfDay()
        val minutes = estimatedMinutes ?: (distanceKm * 3.2 + 8).toInt().coerceIn(8, 90)
        return startMinute + minutes
    }

    fun acceptedMinuteOfDay(): Int {
        val local = DecisionAnalytics.localDateTime(acceptedAtMillis)
        return local.hour * 60 + local.minute
    }

    private fun normalizeAfter(minute: Int, now: Int): Int = if (minute < now - 12 * 60) minute + 24 * 60 else minute
}

class ActiveJobTracker(
    /** Po tylu minutach bez sygnalu zlecenie uznajemy za zakonczone. */
    private val staleAfterMs: Long = 50 * 60_000L
) {
    @Volatile
    var current: ActiveJob? = null
        private set

    fun onAccepted(
        platform: CourierPlatform,
        offer: Offer,
        nowMillis: Long,
        restaurant: String?,
        pickupAddress: String?,
        dropoffAddress: String?,
        stops: Int
    ) {
        current = ActiveJob(
            platform = platform,
            acceptedAtMillis = nowMillis,
            restaurant = restaurant,
            pickupAddress = pickupAddress,
            dropoffAddress = dropoffAddress,
            pickupMinuteOfDay = offer.pickupTimeMinutesOfDay,
            deliveryMinuteOfDay = offer.deliveryTimeMinutesOfDay,
            estimatedMinutes = offer.durationMinutes,
            distanceKm = offer.distanceKm,
            amountPln = offer.amountPln,
            stops = stops
        )
    }

    /** Ekran aktywnego zlecenia potwierdza, ze zlecenie trwa; moze doprecyzowac godziny. */
    fun onActiveScreen(
        platform: CourierPlatform,
        nowMillis: Long,
        pickupMinute: Int?,
        deliveryMinute: Int?,
        restaurant: String?,
        pickedUp: Boolean
    ) {
        val existing = current
        if (existing == null || existing.platform != platform) {
            current = ActiveJob(
                platform = platform,
                acceptedAtMillis = nowMillis,
                restaurant = restaurant,
                pickupAddress = null,
                dropoffAddress = null,
                pickupMinuteOfDay = pickupMinute,
                deliveryMinuteOfDay = deliveryMinute,
                estimatedMinutes = null,
                distanceKm = 0.0,
                amountPln = 0.0,
                stage = if (pickedUp) ActiveJob.Stage.TO_CUSTOMER else ActiveJob.Stage.TO_PICKUP,
                lastSeenAtMillis = nowMillis
            )
            return
        }
        current = existing.copy(
            pickupMinuteOfDay = pickupMinute ?: existing.pickupMinuteOfDay,
            deliveryMinuteOfDay = deliveryMinute ?: existing.deliveryMinuteOfDay,
            restaurant = existing.restaurant ?: restaurant,
            stage = if (pickedUp) ActiveJob.Stage.TO_CUSTOMER else existing.stage,
            lastSeenAtMillis = nowMillis
        )
    }

    fun onDelivered() {
        current = null
    }

    fun activeAt(nowMillis: Long): ActiveJob? {
        val job = current ?: return null
        if (nowMillis - job.lastSeenAtMillis > staleAfterMs) {
            current = null
            return null
        }
        return job
    }

    fun clear() {
        current = null
    }
}

/** Ocena "czy brac" nowej oferty w trakcie aktywnego zlecenia. */
data class OfferFeasibility(
    val verdict: Verdict,
    /** Minuty zapasu (dodatnie) albo spoznienia (ujemne) wzgledem odbioru nowej oferty. */
    val slackMinutes: Int?,
    val currentFinishMinuteOfDay: Int,
    val newPickupMinuteOfDay: Int?,
    val note: String
) {
    enum class Verdict { OK, TIGHT, CONFLICT, UNKNOWN }
}

object OfferFeasibilityCalculator {
    /**
     * @param travelToNewPickupMinutes czas dojazdu z miejsca zakonczenia biezacego
     *        zlecenia (albo z aktualnej pozycji) do nowego odbioru – z RouteAdvisor,
     *        gdy jest dostepny; inaczej szacunek z dystansu.
     */
    fun evaluate(
        job: ActiveJob,
        newOffer: Offer,
        currentMinuteOfDay: Int,
        travelToNewPickupMinutes: Int?,
        language: String = "pl"
    ): OfferFeasibility {
        val finish = job.expectedFinishMinuteOfDay(currentMinuteOfDay)
        val newPickup = newOffer.pickupTimeMinutesOfDay?.let { if (it < currentMinuteOfDay - 12 * 60) it + 1440 else it }
        val travel = travelToNewPickupMinutes ?: 6

        if (newPickup != null) {
            val slack = newPickup - (finish + travel)
            val verdict = when {
                slack >= 3 -> OfferFeasibility.Verdict.OK
                slack >= -4 -> OfferFeasibility.Verdict.TIGHT
                else -> OfferFeasibility.Verdict.CONFLICT
            }
            val note = when (verdict) {
                OfferFeasibility.Verdict.OK -> tr(language,
                    "Zdążysz: koniec bieżącego ~${clock(finish)}, odbiór ${clock(newPickup)} (+$slack min)",
                    "Feasible: current done ~${clock(finish)}, pickup ${clock(newPickup)} (+$slack min)",
                    "Встигнеш: поточне ~${clock(finish)}, забір ${clock(newPickup)} (+$slack хв)",
                    "Успеешь: текущее ~${clock(finish)}, забор ${clock(newPickup)} (+$slack мин)")
                OfferFeasibility.Verdict.TIGHT -> tr(language,
                    "Na styk: koniec ~${clock(finish)}, odbiór ${clock(newPickup)} (${slack} min)",
                    "Tight: done ~${clock(finish)}, pickup ${clock(newPickup)} (${slack} min)",
                    "На межі: ~${clock(finish)}, забір ${clock(newPickup)} (${slack} хв)",
                    "Впритык: ~${clock(finish)}, забор ${clock(newPickup)} (${slack} мин)")
                else -> tr(language,
                    "Kolizja: odbiór ${clock(newPickup)} przed końcem bieżącego ~${clock(finish)} (${slack} min)",
                    "Conflict: pickup ${clock(newPickup)} before current ends ~${clock(finish)} (${slack} min)",
                    "Конфлікт: забір ${clock(newPickup)} до кінця поточного ~${clock(finish)}",
                    "Конфликт: забор ${clock(newPickup)} до конца текущего ~${clock(finish)}")
            }
            return OfferFeasibility(verdict, slack, finish, newPickup, note)
        }

        // Bez godziny odbioru (Wolt/Stuart): oferta startuje od razu po akceptacji.
        // Liczymy ile minut biezacego zlecenia jeszcze zostalo.
        val remaining = finish - currentMinuteOfDay
        val verdict = when {
            remaining <= 4 -> OfferFeasibility.Verdict.OK
            remaining <= 12 -> OfferFeasibility.Verdict.TIGHT
            else -> OfferFeasibility.Verdict.CONFLICT
        }
        val note = when (verdict) {
            OfferFeasibility.Verdict.OK -> tr(language,
                "Bieżące kończysz za ~$remaining min – nowa dostawa ruszy prawie od razu",
                "Current job ends in ~$remaining min – new one starts almost immediately",
                "Поточне закінчиш за ~$remaining хв",
                "Текущее закончишь через ~$remaining мин")
            OfferFeasibility.Verdict.TIGHT -> tr(language,
                "Bieżące jeszcze ~$remaining min; nowa dostawa dojdzie do kolejki (${newOffer.durationMinutes ?: "?"} min)",
                "Current still ~$remaining min; new job queues after it (${newOffer.durationMinutes ?: "?"} min)",
                "Поточне ще ~$remaining хв; нове в черзі",
                "Текущее ещё ~$remaining мин; новое в очереди")
            else -> tr(language,
                "Bieżące jeszcze ~$remaining min – nowe zlecenie znacznie się opóźni",
                "Current still ~$remaining min – new job will be badly delayed",
                "Поточне ще ~$remaining хв – нове сильно запізниться",
                "Текущее ещё ~$remaining мин – новое сильно задержится")
        }
        return OfferFeasibility(verdict, -remaining, finish, null, note)
    }

    private fun clock(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", (minute / 60) % 24, minute % 60)

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String = when (language) {
        "en" -> en
        "uk" -> uk
        "ru" -> ru
        else -> pl
    }
}
