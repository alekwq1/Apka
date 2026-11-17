package pl.fujara.app

import java.time.LocalDate

/** Wolt w danym dniu – kwoty i km z najlepszego dostepnego zrodla. */
data class WoltDayPart(
    val orders: Int,
    val grossPln: Double,
    val distanceKm: Double,
    val measuredMinutes: Int,
    val netPln: Double,
    val source: Source
) {
    enum class Source { OFFICIAL, LIVE, ESTIMATE, NONE }
}

object WoltDayPartCalculator {
    /**
     * Kolejnosc zrodel: oficjalna suma dnia z Wolt → zlecenia (oficjalne + zmierzone przez FUJARE)
     * → przyjete oferty (szacunek). Netto liczone tymi samymi regulami co oferty Wolt.
     */
    fun calculate(
        date: LocalDate,
        orders: List<WoltOrder>,
        officialDayTotal: Double?,
        acceptedOffers: List<OfferEvent>,
        rules: ProfitabilityCalculator.Rules,
        decisionBasis: DecisionBasis,
        zusPercent: Double
    ): WoltDayPart {
        val dayOrders = orders.filter { it.date == date }
        val dayOffers = acceptedOffers.filter { it.date == date && it.platform == CourierPlatform.WOLT && it.decision == OfferDecision.ACCEPTED }
        if (dayOrders.isEmpty() && officialDayTotal == null && dayOffers.isEmpty()) {
            return WoltDayPart(0, 0.0, 0.0, 0, 0.0, WoltDayPart.Source.NONE)
        }
        val km: Double
        val count: Int
        val minutes: Int
        var gross: Double
        val source: WoltDayPart.Source
        if (dayOrders.isNotEmpty() || officialDayTotal != null) {
            count = dayOrders.size
            km = dayOrders.mapNotNull { it.distanceKm }.sum().takeIf { it > 0.0 } ?: dayOffers.sumOf { it.distanceKm }
            minutes = dayOrders.mapNotNull { it.durationMinutes }.sum()
            gross = dayOrders.sumOf { it.grossPln }
            source = when {
                officialDayTotal != null || dayOrders.any { it.source == WoltOrder.SOURCE_OFFICIAL } -> WoltDayPart.Source.OFFICIAL
                else -> WoltDayPart.Source.LIVE
            }
            // Oficjalna suma dnia jest wazniejsza od sumy pojedynczych wpisow (moze brakowac przewinietych).
            if (officialDayTotal != null && officialDayTotal > gross) gross = officialDayTotal
        } else {
            count = dayOffers.size
            km = dayOffers.sumOf { it.distanceKm }
            minutes = dayOffers.sumOf { it.durationMinutes ?: 0 }
            gross = dayOffers.sumOf { it.amountPln }
            source = WoltDayPart.Source.ESTIMATE
        }
        val net = if (gross > 0.0) {
            ProfitabilityCalculator.calculate(
                offer = Offer(amountPln = gross, distanceKm = km, durationMinutes = minutes.takeIf { it > 0 } ?: 1, applyExtraTimeBuffer = false),
                rules = rules,
                currentMinuteOfDay = 12 * 60,
                decisionBasis = decisionBasis,
                zusPercent = zusPercent
            ).netPln
        } else 0.0
        return WoltDayPart(maxOf(count, if (gross > 0) 1 else 0), gross, km, minutes, net, source)
    }
}

/**
 * Dzien lacznie: Pyszne + Wolt. Czas pracy = Start/Pauza/Stop z ekranu Start (gdy jest),
 * bo to jest realny czas aktywnosci; bez niego – suma czasow zlecen jak dotychczas.
 */
object CombinedDayCalculator {
    fun combine(pyszne: PyszneDaySummary, wolt: WoltDayPart, workMinutes: Int): PyszneDaySummary {
        val gross = pyszne.grossPln + wolt.grossPln
        val net = pyszne.netPln + wolt.netPln
        val km = pyszne.distanceKm + wolt.distanceKm
        val seconds = if (workMinutes > 0) workMinutes * 60 else pyszne.durationSeconds + wolt.measuredMinutes * 60
        val hours = seconds / 3600.0
        return pyszne.copy(
            orderCount = pyszne.orderCount + wolt.orders,
            grossPln = gross,
            distanceKm = km,
            durationSeconds = seconds,
            netPln = net,
            netPerHour = if (hours >= 10.0 / 60.0) net / hours else pyszne.netPerHour,
            netPerKm = if (km > 0.0) net / km else null
        )
    }
}

/**
 * 1.6: czas pracy dnia, gdy kurier nie uzywa Start/Pauza. Liczymy od pierwszej do ostatniej
 * aktywnosci (oferty, decyzje, zlecenia), a przerwy dluzsze niz [breakAfterMinutes] bez
 * zadnej aktywnosci nie wliczaja sie. Trwajacy dzien liczy sie do "teraz", jesli ostatnia
 * aktywnosc byla niedawno.
 */
object ActivityWorkTime {
    fun minutes(activityMillis: List<Long>, nowMillis: Long, breakAfterMinutes: Int = 25): Int {
        val sorted = activityMillis.filter { it in 1..nowMillis }.sorted()
        if (sorted.isEmpty()) return 0
        val limit = breakAfterMinutes * 60_000L
        var total = 0L
        sorted.zipWithNext().forEach { (a, b) -> if (b - a <= limit) total += b - a }
        val tail = nowMillis - sorted.last()
        if (tail <= limit) total += tail
        return (total / 60_000L).toInt()
    }

    /** Czas pracy: z Start/Pauza, a gdy go brak – z aktywnosci. */
    fun effective(shiftMinutes: Int, activityMillis: List<Long>, nowMillis: Long): Int =
        if (shiftMinutes > 0) shiftMinutes else minutes(activityMillis, nowMillis)
}
