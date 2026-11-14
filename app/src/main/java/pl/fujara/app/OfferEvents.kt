package pl.fujara.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Decyzja kuriera wobec pokazanej oferty.
 *
 * ACCEPTED  – kliknieto Akceptuj / Zaakceptuj zlecenie albo zaraz po zniknieciu
 *             oferty pojawil sie ekran aktywnej dostawy ("Odbierz na", "Nawiguj").
 * REJECTED  – kliknieto Odrzuc (recznie albo glosem).
 * EXPIRED   – oferta zniknela bez klikniecia, np. Stuart "10 sekund do
 *             automatycznego odrzucenia" albo Wolt po uplywie czasu.
 * UNKNOWN   – nie udalo sie ustalic (np. uslugа zostala wylaczona w trakcie).
 */
enum class OfferDecision(val key: String) {
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    UNKNOWN("unknown");

    companion object {
        fun fromKey(key: String?): OfferDecision = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/** Skad pochodzi informacja o decyzji – przydatne w analizie wiarygodnosci. */
enum class DecisionSource(val key: String) {
    CLICK("click"),
    VOICE("voice"),
    NEXT_SCREEN("next_screen"),
    TIMEOUT("timeout"),
    NONE("none");

    companion object {
        fun fromKey(key: String?): DecisionSource = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * Jedna pokazana oferta razem z ocena FUJARY i decyzja kuriera.
 * To jest "zbior" do analityki: ile odrzucilem, ile przyjalem, jakie stawki,
 * o ktorych godzinach, z jakich restauracji.
 */
data class OfferEvent(
    val id: String,
    val platform: CourierPlatform,
    val shownAtMillis: Long,
    val decidedAtMillis: Long?,
    val decision: OfferDecision,
    val decisionSource: DecisionSource,
    val amountPln: Double,
    val netPln: Double,
    val distanceKm: Double,
    val durationMinutes: Int?,
    val netPerHour: Double?,
    val netPerKm: Double?,
    val status: ProfitabilityStatus,
    val restaurant: String? = null,
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null,
    val pickupMinuteOfDay: Int? = null,
    val deliveryMinuteOfDay: Int? = null,
    val stops: Int = 1,
    /** Czy oferta pojawila sie, gdy kurier mial juz aktywne zlecenie. */
    val duringActiveJob: Boolean = false,
    val blacklisted: Boolean = false
) {
    val date: LocalDate
        get() = Instant.ofEpochMilli(shownAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    val hourOfDay: Int
        get() = Instant.ofEpochMilli(shownAtMillis).atZone(ZoneId.systemDefault()).hour

    val decisionLatencySeconds: Int?
        get() = decidedAtMillis?.let { ((it - shownAtMillis) / 1000L).toInt().coerceAtLeast(0) }
}

/** Sygnatura oferty: ta sama karta skanowana co 1,2 s nie moze tworzyc nowych wpisow. */
object OfferSignature {
    fun of(platform: CourierPlatform, offer: Offer): String =
        listOf(
            platform.key,
            String.format(Locale.ROOT, "%.2f", offer.amountPln),
            String.format(Locale.ROOT, "%.1f", offer.distanceKm),
            offer.pickupTimeMinutesOfDay?.toString().orEmpty(),
            offer.deliveryTimeMinutesOfDay?.toString().orEmpty()
        ).joinToString("|")

    fun newId(platform: CourierPlatform, nowMillis: Long, signature: String): String =
        "${platform.key}-$nowMillis-${(signature.hashCode().toLong() and 0xffffffffL).toString(16)}"
}

/**
 * Czysta maszyna stanow: zamienia strumien "oferta widoczna / klikniecie /
 * ekran po zniknieciu" na finalne [OfferEvent]-y. Bez Androida, latwo testowalna.
 *
 * Reguly:
 *  - Nowa sygnatura = nowa oferta. Poprzednia, jesli nie miala decyzji, dostaje
 *    decyzje na podstawie tego, co bylo widac potem.
 *  - Klikniecie Akceptuj/Odrzuc w ciagu [clickWindowMs] po ostatnim widoku oferty
 *    rozstrzyga decyzje (zrodlo CLICK; VOICE gdy odrzucenie bylo glosowe).
 *  - Jezeli po zniknieciu oferty w ciagu [nextScreenWindowMs] widac ekran aktywnej
 *    dostawy, uznajemy ACCEPTED (NEXT_SCREEN).
 *  - Po [expireAfterMs] bez decyzji i bez ekranu dostawy: EXPIRED (TIMEOUT);
 *    dla ofert, ktore same znikaja (Stuart), to jest wlasciwy wynik.
 */
class DecisionTracker(
    private val clickWindowMs: Long = 25_000L,
    private val nextScreenWindowMs: Long = 20_000L,
    private val expireAfterMs: Long = 45_000L
) {
    data class Pending(
        val id: String,
        val signature: String,
        val platform: CourierPlatform,
        val offer: Offer,
        val result: Profitability,
        val shownAtMillis: Long,
        var lastSeenAtMillis: Long,
        val restaurant: String?,
        val pickupAddress: String?,
        val dropoffAddress: String?,
        val stops: Int,
        val duringActiveJob: Boolean,
        val blacklisted: Boolean,
        var visible: Boolean = true,
        var vanishedAtMillis: Long? = null
    )

    private var pending: Pending? = null
    private val finished = mutableListOf<OfferEvent>()

    val current: Pending?
        get() = pending

    /** Oferta jest aktualnie widoczna na ekranie. */
    fun offerVisible(
        platform: CourierPlatform,
        offer: Offer,
        result: Profitability,
        nowMillis: Long,
        restaurant: String? = null,
        pickupAddress: String? = null,
        dropoffAddress: String? = null,
        stops: Int = 1,
        duringActiveJob: Boolean = false,
        blacklisted: Boolean = false
    ): Pending {
        val signature = OfferSignature.of(platform, offer)
        val existing = pending
        if (existing != null && existing.signature == signature) {
            existing.lastSeenAtMillis = nowMillis
            existing.visible = true
            existing.vanishedAtMillis = null
            return existing
        }

        // Inna oferta: poprzednia bez decyzji konczy sie jako EXPIRED/UNKNOWN.
        existing?.let { finalize(it, inferVanishedDecision(it, nowMillis), nowMillis) }

        return Pending(
            id = OfferSignature.newId(platform, nowMillis, signature),
            signature = signature,
            platform = platform,
            offer = offer,
            result = result,
            shownAtMillis = nowMillis,
            lastSeenAtMillis = nowMillis,
            restaurant = restaurant?.takeIf { it.isNotBlank() },
            pickupAddress = pickupAddress?.takeIf { it.isNotBlank() },
            dropoffAddress = dropoffAddress?.takeIf { it.isNotBlank() },
            stops = stops.coerceAtLeast(1),
            duringActiveJob = duringActiveJob,
            blacklisted = blacklisted
        ).also { pending = it }
    }

    /** Skan nie znalazl oferty na ekranie. */
    fun offerNotVisible(nowMillis: Long) {
        val p = pending ?: return
        if (p.visible) {
            p.visible = false
            p.vanishedAtMillis = nowMillis
        }
        tick(nowMillis)
    }

    /**
     * Kurier kliknal przycisk (tekst z TYPE_VIEW_CLICKED). Zwraca decyzje,
     * jesli tekst byl jednoznaczny.
     */
    fun buttonClicked(text: String, nowMillis: Long, source: DecisionSource = DecisionSource.CLICK): OfferDecision? {
        val p = pending ?: return null
        if (nowMillis - p.lastSeenAtMillis > clickWindowMs) return null
        val decision = DecisionButtons.classify(text) ?: return null
        finalize(p, decision, nowMillis, source)
        return decision
    }

    /** Glosowe odrzucenie wykonane przez FUJARE. */
    fun rejectedByVoice(nowMillis: Long) {
        val p = pending ?: return
        finalize(p, OfferDecision.REJECTED, nowMillis, DecisionSource.VOICE)
    }

    /**
     * Widac ekran aktywnej dostawy (Odbierz na / Nawiguj / Odbiór za N min).
     *
     * Gdy kurier mial juz aktywne zlecenie tej platformy przed pojawieniem sie oferty
     * ([hadPreviousJob]), ekran "w trakcie" po zniknieciu oferty moze byc po prostu
     * STARYM zleceniem – wtedy nie wolno uznac oferty za przyjeta. Przyjmujemy tylko,
     * gdy ekran pokazuje inny lokal niz poprzednie zlecenie albo ten sam co oferta.
     */
    fun activeDeliveryScreenSeen(
        platform: CourierPlatform?,
        nowMillis: Long,
        screenRestaurant: String? = null,
        previousJobRestaurant: String? = null,
        hadPreviousJob: Boolean = false
    ) {
        val p = pending ?: return
        if (platform != null && platform != p.platform) return
        if (p.visible && nowMillis - p.shownAtMillis < 1_500L) return
        val reference = p.vanishedAtMillis ?: p.lastSeenAtMillis
        if (nowMillis - reference > nextScreenWindowMs) return

        if (hadPreviousJob) {
            val screen = screenRestaurant?.let(PyszneHistoryParser::normalizeRestaurant)?.takeIf { it.isNotBlank() }
            val previous = previousJobRestaurant?.let(PyszneHistoryParser::normalizeRestaurant)?.takeIf { it.isNotBlank() }
            val offerName = p.restaurant?.let(PyszneHistoryParser::normalizeRestaurant)?.takeIf { it.isNotBlank() }
            val matchesOffer = screen != null && offerName != null && (screen.contains(offerName) || offerName.contains(screen))
            val sameAsPrevious = screen == null || previous == null || screen == previous
            if (!matchesOffer && sameAsPrevious) return
        }
        finalize(p, OfferDecision.ACCEPTED, nowMillis, DecisionSource.NEXT_SCREEN)
    }

    /** Okresowe sprawdzenie timeoutow. */
    fun tick(nowMillis: Long) {
        val p = pending ?: return
        val vanished = p.vanishedAtMillis ?: return
        if (nowMillis - vanished >= expireAfterMs) {
            finalize(p, inferVanishedDecision(p, nowMillis), nowMillis)
        }
    }

    /** Zabiera zakonczone zdarzenia (do zapisu w magazynie). */
    fun drainFinished(): List<OfferEvent> {
        if (finished.isEmpty()) return emptyList()
        val out = finished.toList()
        finished.clear()
        return out
    }

    fun reset() {
        pending = null
    }

    private fun inferVanishedDecision(p: Pending, nowMillis: Long): OfferDecision {
        // Stuart i Wolt: oferta znika sama po uplywie czasu – to jest "EXPIRED".
        // Nie zgadujemy "odrzucona", bo klikniecie dostajemy osobnym zdarzeniem.
        return if (p.vanishedAtMillis != null || nowMillis - p.lastSeenAtMillis > 3_000L) {
            OfferDecision.EXPIRED
        } else {
            OfferDecision.UNKNOWN
        }
    }

    private fun finalize(
        p: Pending,
        decision: OfferDecision,
        nowMillis: Long,
        source: DecisionSource = if (decision == OfferDecision.EXPIRED) DecisionSource.TIMEOUT else DecisionSource.NONE
    ) {
        finished += OfferEvent(
            id = p.id,
            platform = p.platform,
            shownAtMillis = p.shownAtMillis,
            decidedAtMillis = nowMillis,
            decision = decision,
            decisionSource = source,
            amountPln = p.result.grossPln,
            netPln = p.result.netPln,
            distanceKm = p.result.distanceKm,
            durationMinutes = p.result.durationMinutes,
            netPerHour = p.result.netPerHour,
            netPerKm = p.result.netPerKm,
            status = p.result.status,
            restaurant = p.restaurant,
            pickupAddress = p.pickupAddress,
            dropoffAddress = p.dropoffAddress,
            pickupMinuteOfDay = p.offer.pickupTimeMinutesOfDay,
            deliveryMinuteOfDay = p.offer.deliveryTimeMinutesOfDay,
            stops = p.stops,
            duringActiveJob = p.duringActiveJob,
            blacklisted = p.blacklisted
        )
        if (pending === p) pending = null
    }
}

/** Rozpoznawanie etykiet przyciskow decyzji (PL/EN/UK/RU) w aplikacjach kurierskich. */
object DecisionButtons {
    private val accept = Regex(
        """(?i)(?<!\p{L})(?:akceptuj|zaakceptuj(?:\s+zlecenie)?|przyjmij|przyjmuj[eę]|accept(?:\s+(?:order|job|offer|delivery))?|прийняти|принять|принимаю|jestem\s+zainteresowany)(?!\p{L})"""
    )
    private val reject = Regex(
        """(?i)(?<!\p{L})(?:odrzu[cć](?:\s+zlecenie)?|odrzucam|decline|reject|skip|відхилити|отклонить|пропустить)(?!\p{L})"""
    )

    fun classify(text: String): OfferDecision? {
        val clean = text.trim()
        if (clean.isBlank() || clean.length > 60) return null
        return when {
            reject.containsMatchIn(clean) -> OfferDecision.REJECTED
            accept.containsMatchIn(clean) -> OfferDecision.ACCEPTED
            else -> null
        }
    }

    fun isAcceptLabel(text: String): Boolean = classify(text) == OfferDecision.ACCEPTED
    fun isRejectLabel(text: String): Boolean = classify(text) == OfferDecision.REJECTED
}

/** Agregaty do ekranu "Decyzje". */
data class DecisionStats(
    val shown: Int,
    val accepted: Int,
    val rejected: Int,
    val expired: Int,
    val unknown: Int,
    val acceptanceRatePercent: Double?,
    val avgNetPerHourAccepted: Double?,
    val avgNetPerHourRejected: Double?,
    val avgNetPerKmAccepted: Double?,
    val avgNetPerKmRejected: Double?,
    val avgAmountAccepted: Double?,
    val avgAmountRejected: Double?,
    val acceptedGoodShare: Double?,
    val rejectedGoodCount: Int,
    val acceptedPoorCount: Int,
    val medianDecisionSeconds: Int?,
    /** Kwota netto z zaakceptowanych ofert (suma netPln). */
    val acceptedNetTotal: Double,
    /** Ile netto "zostawiono" odrzucajac oferty ocenione jako DOBRE. */
    val rejectedGoodNetTotal: Double
)

data class PlatformDecisionStats(val platform: CourierPlatform, val stats: DecisionStats)

data class HourDecisionBucket(
    val hour: Int,
    val shown: Int,
    val accepted: Int,
    val avgNetPerHourShown: Double?
)

data class RestaurantDecisionRow(
    val restaurant: String,
    val shown: Int,
    val accepted: Int,
    val rejected: Int,
    val avgNetPerHour: Double?
)

object DecisionAnalytics {
    fun stats(events: List<OfferEvent>): DecisionStats {
        val accepted = events.filter { it.decision == OfferDecision.ACCEPTED }
        val rejected = events.filter { it.decision == OfferDecision.REJECTED }
        val expired = events.filter { it.decision == OfferDecision.EXPIRED }
        val unknown = events.filter { it.decision == OfferDecision.UNKNOWN }
        val decided = accepted.size + rejected.size + expired.size
        val latencies = events.mapNotNull { it.decisionLatencySeconds }.filter { it in 0..600 }.sorted()
        return DecisionStats(
            shown = events.size,
            accepted = accepted.size,
            rejected = rejected.size,
            expired = expired.size,
            unknown = unknown.size,
            acceptanceRatePercent = decided.takeIf { it > 0 }?.let { accepted.size * 100.0 / it },
            avgNetPerHourAccepted = accepted.mapNotNull { it.netPerHour }.averageOrNull(),
            avgNetPerHourRejected = (rejected + expired).mapNotNull { it.netPerHour }.averageOrNull(),
            avgNetPerKmAccepted = accepted.mapNotNull { it.netPerKm }.averageOrNull(),
            avgNetPerKmRejected = (rejected + expired).mapNotNull { it.netPerKm }.averageOrNull(),
            avgAmountAccepted = accepted.map { it.amountPln }.averageOrNull(),
            avgAmountRejected = (rejected + expired).map { it.amountPln }.averageOrNull(),
            acceptedGoodShare = accepted.takeIf { it.isNotEmpty() }
                ?.let { list -> list.count { it.status == ProfitabilityStatus.PROFITABLE } * 100.0 / list.size },
            rejectedGoodCount = (rejected + expired).count { it.status == ProfitabilityStatus.PROFITABLE },
            acceptedPoorCount = accepted.count { it.status == ProfitabilityStatus.UNPROFITABLE },
            medianDecisionSeconds = latencies.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            acceptedNetTotal = accepted.sumOf { it.netPln },
            rejectedGoodNetTotal = (rejected + expired)
                .filter { it.status == ProfitabilityStatus.PROFITABLE }
                .sumOf { it.netPln }
        )
    }

    fun byPlatform(events: List<OfferEvent>): List<PlatformDecisionStats> =
        events.groupBy { it.platform }
            .map { (platform, list) -> PlatformDecisionStats(platform, stats(list)) }
            .sortedByDescending { it.stats.shown }

    fun byHour(events: List<OfferEvent>): List<HourDecisionBucket> =
        (0..23).map { hour ->
            val list = events.filter { it.hourOfDay == hour }
            HourDecisionBucket(
                hour = hour,
                shown = list.size,
                accepted = list.count { it.decision == OfferDecision.ACCEPTED },
                avgNetPerHourShown = list.mapNotNull { it.netPerHour }.averageOrNull()
            )
        }

    fun byRestaurant(events: List<OfferEvent>, limit: Int = 12): List<RestaurantDecisionRow> =
        events.filter { !it.restaurant.isNullOrBlank() }
            .groupBy { PyszneHistoryParser.normalizeRestaurant(it.restaurant!!) }
            .map { (_, list) ->
                RestaurantDecisionRow(
                    restaurant = list.first().restaurant!!,
                    shown = list.size,
                    accepted = list.count { it.decision == OfferDecision.ACCEPTED },
                    rejected = list.count { it.decision == OfferDecision.REJECTED || it.decision == OfferDecision.EXPIRED },
                    avgNetPerHour = list.mapNotNull { it.netPerHour }.averageOrNull()
                )
            }
            .sortedByDescending { it.shown }
            .take(limit)

    fun forDate(events: List<OfferEvent>, date: LocalDate): List<OfferEvent> = events.filter { it.date == date }

    fun between(events: List<OfferEvent>, from: LocalDate, to: LocalDate): List<OfferEvent> =
        events.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    /** Seria dzienna: ile pokazano / przyjeto – do wykresu. */
    fun dailySeries(events: List<OfferEvent>, from: LocalDate, to: LocalDate): List<Triple<LocalDate, Int, Int>> {
        val grouped = events.groupBy { it.date }
        val out = mutableListOf<Triple<LocalDate, Int, Int>>()
        var day = from
        while (!day.isAfter(to)) {
            val list = grouped[day].orEmpty()
            out += Triple(day, list.size, list.count { it.decision == OfferDecision.ACCEPTED })
            day = day.plusDays(1)
        }
        return out
    }

    fun minuteOfDayLabel(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

    fun localDateTime(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
