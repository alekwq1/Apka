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

/**
 * 1.7: weryfikowany bilans dnia Pyszne + Wolt. Kazda platforma ma kwote z najlepszego zrodla
 * (oficjalna suma z aplikacji > zapisane zlecenia > przyjete oferty) i liste niezgodnosci,
 * a czas pracy jest porownany z czasem samych dostaw.
 */
data class DayPlatformPart(
    val platform: CourierPlatform,
    val orders: Int,
    val grossPln: Double,
    val distanceKm: Double,
    /** Czas samych dostaw (Pyszne: z historii, Wolt: zmierzony akceptacja → dostarczone). */
    val deliveryMinutes: Int,
    val netPln: Double,
    val source: Source,
    val warnings: List<String>
) {
    enum class Source { OFFICIAL, SAVED, LIVE, ESTIMATE, NONE }
}

data class DayAudit(
    val date: LocalDate,
    val pyszne: DayPlatformPart,
    val wolt: DayPlatformPart,
    val workMinutes: Int,
    val workSource: WorkSource,
    val warnings: List<String>
) {
    enum class WorkSource { SHIFT, ACTIVITY, DELIVERIES, NONE }

    val orders: Int get() = pyszne.orders + wolt.orders
    val grossPln: Double get() = pyszne.grossPln + wolt.grossPln
    val netPln: Double get() = pyszne.netPln + wolt.netPln
    val distanceKm: Double get() = pyszne.distanceKm + wolt.distanceKm
    val deliveryMinutes: Int get() = pyszne.deliveryMinutes + wolt.deliveryMinutes
    val netPerHour: Double? get() = if (workMinutes >= 10) netPln / (workMinutes / 60.0) else null
    val grossPerHour: Double? get() = if (workMinutes >= 10) grossPln / (workMinutes / 60.0) else null
    val netPerKm: Double? get() = if (distanceKm > 0.0) netPln / distanceKm else null
    /** Jaka czesc czasu pracy to realne dostawy (reszta: czekanie, dojazdy do strefy). */
    val utilization: Double? get() = if (workMinutes >= 10 && deliveryMinutes > 0) (deliveryMinutes.toDouble() / workMinutes).coerceAtMost(1.5) else null
    val allWarnings: List<String> get() = pyszne.warnings + wolt.warnings + warnings

    /** Zgodnosc z dotychczasowymi ekranami (cel dnia, prognoza). */
    fun asSummary(base: PyszneDaySummary): PyszneDaySummary = base.copy(
        orderCount = orders,
        grossPln = grossPln,
        distanceKm = distanceKm,
        durationSeconds = workMinutes * 60,
        netPln = netPln,
        netPerHour = netPerHour,
        netPerKm = netPerKm
    )
}

object DayAuditCalculator {
    private fun money(v: Double) = String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.2f zł", v)

    fun net(gross: Double, km: Double, rules: ProfitabilityCalculator.Rules, zusPercent: Double): Double {
        val cost = rules.vehicleCostPerKm.takeIf { it.isFinite() && it >= 0.0 } ?: 0.35
        val zus = zusPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        return gross * (1.0 - zus / 100.0) - km * cost
    }

    fun pyszne(
        date: LocalDate,
        logs: List<PyszneDeliveryLog>,
        reference: PyszneDayReference?,
        acceptedOffers: List<OfferEvent>,
        rules: ProfitabilityCalculator.Rules,
        zusPercent: Double,
        cashTipsPln: Double = 0.0
    ): DayPlatformPart {
        val day = logs.filter { it.date == date }.distinctBy { it.orderId ?: it.key }
        val offers = acceptedOffers.filter { it.date == date && it.platform == CourierPlatform.PYSZNE && it.decision == OfferDecision.ACCEPTED }
        val warnings = mutableListOf<String>()
        val logsGross = day.sumOf { it.amountPln }
        val logsKm = day.sumOf { it.distanceKm }
        val minutes = day.sumOf { it.durationSeconds } / 60
        val tips = cashTipsPln.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val count: Int
        var gross: Double
        var km: Double
        val source: DayPlatformPart.Source
        when {
            reference != null && reference.orderCount > 0 -> {
                count = reference.orderCount
                gross = reference.amountPln
                km = logsKm
                source = DayPlatformPart.Source.OFFICIAL
                if (day.size < reference.orderCount) {
                    warnings += "Pyszne: zapisano ${day.size} z ${reference.orderCount} zleceń – kwota z podsumowania Pyszne, km tylko z zapisanych"
                    // Brakujace km szacujemy srednia z zapisanych, zeby koszt auta nie byl zanizony.
                    if (day.isNotEmpty()) km = logsKm / day.size * reference.orderCount
                }
                if (day.size > reference.orderCount) warnings += "Pyszne: zapisanych zleceń (${day.size}) więcej niż w podsumowaniu (${reference.orderCount}) – sprawdź duplikaty"
                if (day.size == reference.orderCount && kotlin.math.abs(logsGross - reference.amountPln) > 1.0) {
                    warnings += "Pyszne: suma zleceń ${money(logsGross)} ≠ podsumowanie ${money(reference.amountPln)}"
                }
            }
            day.isNotEmpty() -> {
                count = day.size; gross = logsGross; km = logsKm
                source = DayPlatformPart.Source.SAVED
                warnings += "Pyszne: brak podsumowania dnia z aplikacji – otwórz Historia → dzień, żeby potwierdzić kwotę"
            }
            offers.isNotEmpty() -> {
                count = offers.size; gross = offers.sumOf { it.amountPln }; km = offers.sumOf { it.distanceKm }
                source = DayPlatformPart.Source.ESTIMATE
                warnings += "Pyszne: kwota szacunkowa z przyjętych ofert (${offers.size})"
            }
            else -> return DayPlatformPart(CourierPlatform.PYSZNE, 0, tips, 0.0, 0, tips, if (tips > 0) DayPlatformPart.Source.SAVED else DayPlatformPart.Source.NONE, emptyList())
        }
        gross += tips
        return DayPlatformPart(CourierPlatform.PYSZNE, count, gross, km, minutes, net(gross, km, rules, zusPercent), source, warnings)
    }

    fun wolt(
        date: LocalDate,
        orders: List<WoltOrder>,
        officialDayTotal: Double?,
        acceptedOffers: List<OfferEvent>,
        rules: ProfitabilityCalculator.Rules,
        zusPercent: Double
    ): DayPlatformPart {
        val day = orders.filter { it.date == date }
        val offers = acceptedOffers.filter { it.date == date && it.platform == CourierPlatform.WOLT && it.decision == OfferDecision.ACCEPTED }
        val warnings = mutableListOf<String>()
        val ordersGross = day.sumOf { it.grossPln }
        val measured = day.mapNotNull { it.durationMinutes }.sum()
        val official = day.filter { it.source == WoltOrder.SOURCE_OFFICIAL }
        val live = day.filter { it.source == WoltOrder.SOURCE_LIVE }
        val offersKm = offers.sumOf { it.distanceKm }
        fun kmOf(list: List<WoltOrder>): Double {
            val known = list.mapNotNull { it.distanceKm }
            if (known.isEmpty()) return offersKm
            // Brak km przy czesci zlecen – uzupelniamy srednia.
            return known.sum() / known.size * list.size
        }
        val count: Int
        val gross: Double
        val km: Double
        val source: DayPlatformPart.Source
        when {
            officialDayTotal != null && officialDayTotal > 0.0 -> {
                gross = officialDayTotal
                count = maxOf(day.size, offers.size.takeIf { day.isEmpty() } ?: 0, 1)
                km = kmOf(day)
                source = DayPlatformPart.Source.OFFICIAL
                if (official.isNotEmpty() && kotlin.math.abs(official.sumOf { it.grossPln } - officialDayTotal) > 1.0) {
                    warnings += "Wolt: suma dnia ${money(officialDayTotal)}, a z listy zleceń ${money(official.sumOf { it.grossPln })} – przewiń listę dnia w Wolt"
                }
                if (day.isEmpty()) warnings += "Wolt: jest suma dnia, ale brak listy zleceń – otwórz dzień w Zarobkach Wolt"
            }
            official.isNotEmpty() -> {
                gross = ordersGross; count = day.size; km = kmOf(day)
                source = DayPlatformPart.Source.OFFICIAL
                if (live.isNotEmpty()) warnings += "Wolt: ${live.size} zleceń tylko z pomiaru FUJARY (brak w historii Wolt)"
            }
            day.isNotEmpty() -> {
                gross = ordersGross; count = day.size; km = kmOf(day)
                source = DayPlatformPart.Source.LIVE
                warnings += "Wolt: kwoty z pomiaru FUJARY – potwierdź w Wolt → Zarobki"
            }
            offers.isNotEmpty() -> {
                gross = offers.sumOf { it.amountPln }; count = offers.size; km = offersKm
                source = DayPlatformPart.Source.ESTIMATE
                warnings += "Wolt: kwota szacunkowa z przyjętych ofert (${offers.size})"
            }
            else -> return DayPlatformPart(CourierPlatform.WOLT, 0, 0.0, 0.0, 0, 0.0, DayPlatformPart.Source.NONE, emptyList())
        }
        if (offers.size > count && source != DayPlatformPart.Source.ESTIMATE) {
            warnings += "Wolt: przyjęto ${offers.size} ofert, policzono $count zleceń"
        }
        return DayPlatformPart(CourierPlatform.WOLT, count, gross, km, measured, net(gross, km, rules, zusPercent), source, warnings)
    }

    fun audit(
        date: LocalDate,
        pyszne: DayPlatformPart,
        wolt: DayPlatformPart,
        shiftMinutes: Int,
        activityMillis: List<Long>,
        nowMillis: Long,
        isToday: Boolean
    ): DayAudit {
        val warnings = mutableListOf<String>()
        val deliveries = pyszne.deliveryMinutes + wolt.deliveryMinutes
        val activityEnd = if (isToday) nowMillis else (activityMillis.maxOrNull() ?: nowMillis)
        val activity = ActivityWorkTime.minutes(activityMillis, activityEnd)
        val (work, source) = when {
            shiftMinutes > 0 -> shiftMinutes to DayAudit.WorkSource.SHIFT
            activity > 0 -> maxOf(activity, deliveries) to DayAudit.WorkSource.ACTIVITY
            deliveries > 0 -> deliveries to DayAudit.WorkSource.DELIVERIES
            else -> 0 to DayAudit.WorkSource.NONE
        }
        if (source == DayAudit.WorkSource.SHIFT && deliveries > shiftMinutes + 15) {
            warnings += "Czas dostaw (${WorkAnalytics.formatHours(deliveries)}) dłuższy niż czas pracy (${WorkAnalytics.formatHours(shiftMinutes)}) – czy Start był włączony od początku?"
        }
        if (source == DayAudit.WorkSource.SHIFT && activity > shiftMinutes + 30) {
            warnings += "Aktywność w aplikacjach ${WorkAnalytics.formatHours(activity)}, a Start/Pauza ${WorkAnalytics.formatHours(shiftMinutes)} – możliwe, że zapomniałeś włączyć Start"
        }
        if (source != DayAudit.WorkSource.SHIFT && work > 0) {
            warnings += "Czas pracy liczony z aktywności (bez Start) – przerwy > 25 min pominięte"
        }
        val gross = pyszne.grossPln + wolt.grossPln
        if (work >= 30 && gross / (work / 60.0) > 150.0) {
            warnings += "Podejrzanie wysoko: ${"%.0f".format(gross / (work / 60.0))} zł/h brutto – sprawdź kwoty dnia"
        }
        return DayAudit(date, pyszne, wolt, work, source, warnings)
    }

    /** Znaczniki aktywnosci dnia: oferty/decyzje, akceptacje zlecen Pyszne, akceptacje i dostawy Wolt. */
    fun activityMillis(date: LocalDate, events: List<OfferEvent>, pyszneLogs: List<PyszneDeliveryLog>, woltOrders: List<WoltOrder>): List<Long> {
        val zone = java.time.ZoneId.systemDefault()
        fun at(minute: Int) = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        return buildList {
            events.filter { it.date == date }.forEach { e -> add(e.shownAtMillis); e.decidedAtMillis?.let(::add) }
            pyszneLogs.filter { it.date == date }.forEach { l ->
                l.acceptedMinuteOfDay?.let { m -> add(at(m)); add(at(m) + l.durationSeconds * 1000L) }
            }
            woltOrders.filter { it.date == date }.forEach { o ->
                o.acceptedAtMillis?.let(::add); o.deliveredAtMillis?.let(::add)
                if (o.acceptedAtMillis == null) add(at(o.minuteOfDay))
            }
        }
    }
}
