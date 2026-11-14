package pl.fujara.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Odcinek pracy: Start → Pauza/Stop. Segment bez konca = trwa. */
data class WorkSegment(val startMillis: Long, val endMillis: Long?) {
    val date: LocalDate
        get() = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    fun durationMillis(nowMillis: Long): Long = ((endMillis ?: nowMillis) - startMillis).coerceAtLeast(0L)
}

enum class WorkState { STOPPED, RUNNING, PAUSED }

/** Czysta logika zmiany (Start / Pauza / Stop) – testowalna bez Androida. */
class WorkShift(initial: List<WorkSegment> = emptyList(), initialState: WorkState = WorkState.STOPPED) {
    private val segments = initial.toMutableList()
    var state: WorkState = initialState
        private set

    val all: List<WorkSegment>
        get() = segments.toList()

    fun start(nowMillis: Long) {
        if (state == WorkState.RUNNING) return
        segments += WorkSegment(nowMillis, null)
        state = WorkState.RUNNING
    }

    fun pause(nowMillis: Long) {
        if (state != WorkState.RUNNING) return
        closeOpen(nowMillis)
        state = WorkState.PAUSED
    }

    fun stop(nowMillis: Long) {
        if (state == WorkState.STOPPED) return
        closeOpen(nowMillis)
        state = WorkState.STOPPED
    }

    private fun closeOpen(nowMillis: Long) {
        val idx = segments.indexOfLast { it.endMillis == null }
        if (idx >= 0) segments[idx] = segments[idx].copy(endMillis = nowMillis)
    }

    fun minutesForDate(date: LocalDate, nowMillis: Long): Int = WorkAnalytics.minutesForDate(segments, date, nowMillis)

    /** Czas pracy dzisiaj (minuty), lacznie z trwajacym odcinkiem. */
    fun todayMinutes(nowMillis: Long): Int =
        minutesForDate(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate(), nowMillis)
}

object WorkAnalytics {
    fun minutesForDate(segments: List<WorkSegment>, date: LocalDate, nowMillis: Long): Int =
        (segments.filter { it.date == date }.sumOf { it.durationMillis(nowMillis) } / 60_000L).toInt()

    fun minutesBetween(segments: List<WorkSegment>, from: LocalDate, to: LocalDate, nowMillis: Long): Int =
        (segments.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sumOf { it.durationMillis(nowMillis) } / 60_000L).toInt()

    fun workedDates(segments: List<WorkSegment>): Set<LocalDate> = segments.map { it.date }.toSet()

    fun formatHours(minutes: Int): String = "${minutes / 60}h ${String.format(java.util.Locale.ROOT, "%02d", minutes % 60)}m"
}

/** Zestawienie "godzinowki" za okres: przychod ze wszystkich zrodel vs przepracowany czas. */
data class HourlyReport(
    val workedMinutes: Int,
    val pyszneGrossPln: Double,
    val woltGrossPln: Double,
    val woltIsOfficial: Boolean,
    val otherGrossPln: Double,
    val offersShown: Int,
    val offersAccepted: Int
) {
    val totalGrossPln: Double
        get() = pyszneGrossPln + woltGrossPln + otherGrossPln

    val grossPerHour: Double?
        get() = workedMinutes.takeIf { it >= 10 }?.let { totalGrossPln / (it / 60.0) }

    val offersPerHour: Double?
        get() = workedMinutes.takeIf { it >= 10 }?.let { offersShown / (it / 60.0) }

    val acceptedPerHour: Double?
        get() = workedMinutes.takeIf { it >= 10 }?.let { offersAccepted / (it / 60.0) }
}

object HourlyReportCalculator {
    /**
     * @param pyszneByDate kwoty z historii Pyszne (zapisane zlecenia) per dzien
     * @param woltOfficialByDate oficjalne sumy dnia z ekranu "Zarobki brutto"
     * @param events zdarzenia ofert (do szacunku Wolt/innych, gdy brak oficjalnych kwot)
     */
    fun build(
        from: LocalDate,
        to: LocalDate,
        segments: List<WorkSegment>,
        nowMillis: Long,
        pyszneByDate: Map<LocalDate, Double>,
        woltOfficialByDate: Map<LocalDate, Double>,
        events: List<OfferEvent>
    ): HourlyReport {
        val inRange = events.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        var wolt = 0.0
        var official = true
        var day = from
        while (!day.isAfter(to)) {
            val o = woltOfficialByDate[day]
            if (o != null) {
                wolt += o
            } else {
                val estimate = inRange.filter { it.date == day && it.platform == CourierPlatform.WOLT && it.decision == OfferDecision.ACCEPTED }.sumOf { it.amountPln }
                if (estimate > 0.0) official = false
                wolt += estimate
            }
            day = day.plusDays(1)
        }
        val other = inRange.filter { it.platform != CourierPlatform.WOLT && it.platform != CourierPlatform.PYSZNE && it.decision == OfferDecision.ACCEPTED }.sumOf { it.amountPln }
        val pyszne = pyszneByDate.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sum()
        return HourlyReport(
            workedMinutes = WorkAnalytics.minutesBetween(segments, from, to, nowMillis),
            pyszneGrossPln = pyszne,
            woltGrossPln = wolt,
            woltIsOfficial = official,
            otherGrossPln = other,
            offersShown = inRange.size,
            offersAccepted = inRange.count { it.decision == OfferDecision.ACCEPTED }
        )
    }
}
