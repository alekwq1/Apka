package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AnalysisCalendarTest {
    @Test
    fun weekIsCalendarMondayThroughSunday() {
        val wednesday = LocalDate.of(2026, 8, 26)

        assertEquals(LocalDate.of(2026, 8, 24), startOfCalendarWeek(wednesday))
        assertEquals(LocalDate.of(2026, 8, 30), endOfCalendarWeek(wednesday))
    }

    @Test
    fun sundayStillBelongsToWeekThatStartedOnPreviousMonday() {
        val sunday = LocalDate.of(2026, 8, 30)

        assertEquals(LocalDate.of(2026, 8, 24), startOfCalendarWeek(sunday))
        assertEquals(sunday, endOfCalendarWeek(sunday))
    }
}
