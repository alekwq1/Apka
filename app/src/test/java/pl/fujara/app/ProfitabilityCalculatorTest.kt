package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProfitabilityCalculatorTest {
    private val rules = ProfitabilityCalculator.Rules(
        vehicleCostPerKm = 0.35,
        minimumNetPerKm = 2.50,
        minimumNetPerHour = 35.0
    )

    @Test
    fun pyszneUsesCurrentTimeToPlannedDelivery() {
        val offer = Offer(
            amountPln = 17.52,
            distanceKm = 3.8,
            pickupTimeMinutesOfDay = 20 * 60 + 54,
            deliveryTimeMinutesOfDay = 20 * 60 + 57
        )

        val result = ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = 20 * 60 + 42
        )

        assertEquals(15, result.durationMinutes)
        assertEquals(DurationSource.PLANNED_DELIVERY, result.durationSource)
        assertEquals(16.19, result.netPln, 0.01)
        assertEquals(64.76, result.netPerHour!!, 0.02)
    }

    @Test
    fun plannedDeliveryWorksAcrossMidnight() {
        val offer = Offer(
            amountPln = 20.0,
            distanceKm = 4.0,
            deliveryTimeMinutesOfDay = 10
        )

        val result = ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = 23 * 60 + 58
        )

        assertEquals(12, result.durationMinutes)
        assertEquals(DurationSource.PLANNED_DELIVERY, result.durationSource)
    }

    @Test
    fun missingTimeIsNotInvented() {
        val offer = Offer(
            amountPln = 20.0,
            distanceKm = 4.0
        )

        val result = ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = 12 * 60
        )

        assertNull(result.durationMinutes)
        assertNull(result.netPerHour)
        assertNull(result.profitable)
        assertEquals(DurationSource.UNKNOWN, result.durationSource)
    }

    @Test
    fun negativeNetIsPreservedInsteadOfClampedToZero() {
        val result = ProfitabilityCalculator.calculate(
            offer = Offer(
                amountPln = 1.0,
                distanceKm = 10.0,
                durationMinutes = 20
            ),
            rules = rules,
            currentMinuteOfDay = 12 * 60
        )

        assertEquals(-2.5, result.netPln, 0.001)
        assertFalse(result.profitable!!)
    }

    @Test
    fun absurdPastDeadlineIsRejectedInsteadOfBecomingAlmost24Hours() {
        val result = ProfitabilityCalculator.calculate(
            offer = Offer(
                amountPln = 20.0,
                distanceKm = 4.0,
                deliveryTimeMinutesOfDay = 12 * 60 - 10
            ),
            rules = rules,
            currentMinuteOfDay = 12 * 60
        )

        assertNull(result.durationMinutes)
        assertNull(result.netPerHour)
        assertNull(result.profitable)
    }
    @Test
    fun veryHighThresholdsStillReturnVisibleUnprofitableResult() {
        val result = ProfitabilityCalculator.calculate(
            offer = Offer(
                amountPln = 25.42,
                distanceKm = 5.4,
                durationMinutes = 26
            ),
            rules = ProfitabilityCalculator.Rules(
                vehicleCostPerKm = 0.35,
                minimumNetPerKm = 999.0,
                minimumNetPerHour = 999.0
            ),
            currentMinuteOfDay = 12 * 60
        )

        assertEquals(26, result.durationMinutes)
        assertFalse(result.profitable!!)
    }

    @Test
    fun invalidRulesFallBackInsteadOfPoisoningCalculation() {
        val result = ProfitabilityCalculator.calculate(
            offer = Offer(
                amountPln = 25.42,
                distanceKm = 5.4,
                durationMinutes = 26
            ),
            rules = ProfitabilityCalculator.Rules(
                vehicleCostPerKm = Double.NaN,
                minimumNetPerKm = Double.POSITIVE_INFINITY,
                minimumNetPerHour = -1.0
            ),
            currentMinuteOfDay = 12 * 60
        )

        assertEquals(23.53, result.netPln, 0.01)
        assertEquals(26, result.durationMinutes)
    }

}
