package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkShiftTest {
    private val day = LocalDate.of(2026, 11, 14)
    private fun at(hour: Int, minute: Int): Long = day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `start pause resume stop accumulate minutes`() {
        val shift = WorkShift()
        shift.start(at(10, 0))
        assertEquals(WorkState.RUNNING, shift.state)
        shift.pause(at(11, 30))
        assertEquals(WorkState.PAUSED, shift.state)
        assertEquals(90, shift.minutesForDate(day, at(12, 0)))
        shift.start(at(12, 0))
        shift.stop(at(13, 15))
        assertEquals(WorkState.STOPPED, shift.state)
        assertEquals(165, shift.minutesForDate(day, at(14, 0)))
        // Trwajacy odcinek liczy sie do "teraz".
        shift.start(at(15, 0))
        assertEquals(165 + 30, shift.minutesForDate(day, at(15, 30)))
    }

    @Test
    fun `hourly report combines sources`() {
        val segments = listOf(WorkSegment(at(10, 0), at(14, 0)))
        val events = listOf(
            OfferEvent("a", CourierPlatform.WOLT, at(11, 0), at(11, 0), OfferDecision.ACCEPTED, DecisionSource.CLICK, 20.0, 15.0, 3.0, 12, 60.0, 5.0, ProfitabilityStatus.PROFITABLE),
            OfferEvent("b", CourierPlatform.WOLT, at(12, 0), at(12, 0), OfferDecision.REJECTED, DecisionSource.CLICK, 8.0, 5.0, 3.0, 12, 20.0, 1.6, ProfitabilityStatus.UNPROFITABLE)
        )
        val estimate = HourlyReportCalculator.build(day, day, segments, at(15, 0), mapOf(day to 100.0), emptyMap(), events)
        assertEquals(240, estimate.workedMinutes)
        assertEquals(120.0, estimate.totalGrossPln, 0.001)
        assertEquals(false, estimate.woltIsOfficial)
        assertEquals(30.0, estimate.grossPerHour!!, 0.001)
        assertEquals(0.5, estimate.offersPerHour!!, 0.001)

        val official = HourlyReportCalculator.build(day, day, segments, at(15, 0), mapOf(day to 100.0), mapOf(day to 26.78), events)
        assertEquals(true, official.woltIsOfficial)
        assertEquals(126.78, official.totalGrossPln, 0.001)
    }
}
