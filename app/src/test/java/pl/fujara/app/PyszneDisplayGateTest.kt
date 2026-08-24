package pl.fujara.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PyszneDisplayGateTest {
    @Test
    fun hidesOldOrderDuringDirectTransitionAndWaitsForNewStableId() {
        val gate = PyszneDisplayGate(requiredConfirmations = 2, maxConfirmationGapMs = 3_000L)

        assertFalse(gate.shouldShow("P3F9D7", 1_000L))
        assertTrue(gate.shouldShow("P3F9D7", 1_500L))

        gate.beginDirectTransition()
        assertFalse(gate.shouldShow("P3F9D7", 1_800L))
        assertFalse(gate.shouldShow("ABC123", 2_000L))
        assertTrue(gate.shouldShow("ABC123", 2_500L))
    }

    @Test
    fun reopeningSameOrderAfterNavigationStillRequiresFreshConfirmation() {
        val gate = PyszneDisplayGate(requiredConfirmations = 2, maxConfirmationGapMs = 3_000L)

        assertFalse(gate.shouldShow("P3F9D7", 1_000L))
        assertTrue(gate.shouldShow("P3F9D7", 1_500L))
        gate.noteNavigationScreen()

        assertFalse(gate.shouldShow("P3F9D7", 2_000L))
        assertTrue(gate.shouldShow("P3F9D7", 2_500L))
    }
}
