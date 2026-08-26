package pl.fujara.app

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PyszneShareTextTest {
    @Test
    fun shareTextIsProfessionalAndUsesActualBestAndWorstOrder() {
        val weak = order(
            name = "Restauracja Słaba",
            orderKey = "weak",
            netPln = 8.0,
            netPerHour = 24.0,
            netPerKm = 1.60,
            status = ProfitabilityStatus.UNPROFITABLE
        )
        val best = order(
            name = "Restauracja Dobra",
            orderKey = "best",
            netPln = 22.0,
            netPerHour = 66.0,
            netPerKm = 3.10,
            status = ProfitabilityStatus.PROFITABLE
        )
        val summary = PyszneDaySummary(
            date = LocalDate.of(2026, 8, 26),
            orderCount = 2,
            grossPln = 70.0,
            distanceKm = 12.0,
            durationSeconds = 3600,
            netPln = 30.0,
            netPerHour = 30.0,
            netPerKm = 2.50,
            status = ProfitabilityStatus.ALMOST_PROFITABLE,
            goodOrders = 1,
            borderlineOrders = 0,
            poorOrders = 1,
            cancelledOrders = 0,
            restaurants = listOf(weak, best),
            cashTipsPln = 10.0,
            extraPauseMinutes = 0
        )

        val text = summary.shareText("Kurier")

        assertTrue(text.contains("FUJARA | PODSUMOWANIE DNIA"))
        assertTrue(text.contains("WYNIK PO KOSZTACH"))
        assertTrue(text.contains("• Opłacalne: 1"))
        assertTrue(text.contains("• Na granicy: 0"))
        assertTrue(text.contains("• Nieopłacalne: 1"))
        assertTrue(text.contains("NAJLEPSZE ZLECENIE\nRestauracja Dobra"))
        assertTrue(text.contains("NAJSŁABSZE ZLECENIE\nRestauracja Słaba"))
        assertFalse(text.contains("🔴 FUJARA"))
    }

    private fun order(
        name: String,
        orderKey: String,
        netPln: Double,
        netPerHour: Double,
        netPerKm: Double,
        status: ProfitabilityStatus
    ) = PyszneRestaurantSummary(
        name = name,
        orderCount = 1,
        grossPln = 35.0,
        distanceKm = 6.0,
        durationSeconds = 1800,
        netPln = netPln,
        netPerHour = netPerHour,
        netPerKm = netPerKm,
        status = status,
        goodOrders = if (status == ProfitabilityStatus.PROFITABLE) 1 else 0,
        borderlineOrders = if (status == ProfitabilityStatus.ALMOST_PROFITABLE) 1 else 0,
        poorOrders = if (status == ProfitabilityStatus.UNPROFITABLE) 1 else 0,
        cancelledOrders = 0,
        orderKey = orderKey,
        orderId = null,
        acceptedMinuteOfDay = null
    )
}
