package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PyszneDayZusOrderTest {
    @Test
    fun dayAppliesZusBeforeVehicleKilometerCost() {
        val date = LocalDate.of(2026, 8, 26)
        val entry = PyszneDeliveryLog(
            key = "zus-order",
            fingerprint = "zus-order-fp",
            date = date,
            acceptedMinuteOfDay = 12 * 60,
            restaurant = "Test",
            amountPln = 20.0,
            distanceKm = 4.0,
            durationSeconds = 20 * 60
        )

        val summary = PyszneDaySummaryCalculator.calculate(
            date = date,
            entries = listOf(entry),
            rules = ProfitabilityCalculator.Rules(vehicleCostPerKm = 0.5),
            decisionBasis = DecisionBasis.MIXED,
            zusPercent = 25.0
        )

        // 20.00 - 25% ZUS = 15.00; dopiero potem 4 km * 0.50 = 2.00 kosztu.
        assertEquals(13.0, summary.netPln, 0.001)
    }
}
