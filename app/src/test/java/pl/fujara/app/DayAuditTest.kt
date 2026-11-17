package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayAuditTest {
    private val day = LocalDate.of(2026, 11, 15)
    private val rules = ProfitabilityCalculator.Rules(vehicleCostPerKm = 0.5)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun log(id: String, amount: Double, km: Double, minute: Int) = PyszneDeliveryLog(
        key = id, fingerprint = id, orderId = id, date = day, acceptedMinuteOfDay = minute,
        restaurant = "R$id", amountPln = amount, distanceKm = km, durationSeconds = 20 * 60
    )

    @Test
    fun `pyszne official reference wins and missing km are scaled`() {
        val logs = listOf(log("1", 20.0, 4.0, 600), log("2", 22.0, 6.0, 640), log("2", 22.0, 6.0, 640))
        val ref = PyszneDayReference(day, 4, 90.0)
        val p = DayAuditCalculator.pyszne(day, logs, ref, emptyList(), rules, 0.0)
        assertEquals(4, p.orders)
        assertEquals(90.0, p.grossPln, 0.001)
        assertEquals(20.0, p.distanceKm, 0.001) // 10 km / 2 zapisane * 4
        assertEquals(90.0 - 10.0, p.netPln, 0.001)
        assertTrue(p.warnings.any { it.contains("2 z 4") })
    }

    @Test
    fun `net never exceeds gross and hourly uses work time`() {
        val p = DayAuditCalculator.pyszne(day, listOf(log("1", 30.0, 5.0, 600)), PyszneDayReference(day, 1, 30.0), emptyList(), rules, 0.0)
        val woltOrders = listOf(
            WoltOrder(date = day, minuteOfDay = 700, venue = "KFC", grossPln = 18.0, distanceKm = 4.0, stops = 1),
            WoltOrder(date = day, minuteOfDay = 760, venue = "Sushi", grossPln = 22.0, distanceKm = null, stops = 1)
        )
        val w = DayAuditCalculator.wolt(day, woltOrders, 40.0, emptyList(), rules, 0.0)
        assertEquals(2, w.orders)
        assertEquals(8.0, w.distanceKm, 0.001)
        val a = DayAuditCalculator.audit(day, p, w, shiftMinutes = 120, activityMillis = emptyList(), nowMillis = at(14, 0), isToday = false)
        assertEquals(70.0, a.grossPln, 0.001)
        assertTrue(a.netPln <= a.grossPln)
        assertEquals((70.0 - 6.5) / 2.0, a.netPerHour!!, 0.001)
        assertEquals(DayAudit.WorkSource.SHIFT, a.workSource)
    }

    @Test
    fun `wolt official total mismatch is reported`() {
        val orders = listOf(WoltOrder(date = day, minuteOfDay = 700, venue = "KFC", grossPln = 18.0, distanceKm = 4.0, stops = 1))
        val w = DayAuditCalculator.wolt(day, orders, 55.0, emptyList(), rules, 0.0)
        assertEquals(55.0, w.grossPln, 0.001)
        assertTrue(w.warnings.isNotEmpty())
    }

    @Test
    fun `activity used when no shift and forgotten start detected`() {
        val p = DayAuditCalculator.pyszne(day, listOf(log("1", 30.0, 5.0, 600), log("2", 30.0, 5.0, 640)), null, emptyList(), rules, 0.0)
        val w = DayAuditCalculator.wolt(day, emptyList(), null, emptyList(), rules, 0.0)
        val acts = DayAuditCalculator.activityMillis(day, emptyList(), listOf(log("1", 30.0, 5.0, 600), log("2", 30.0, 5.0, 640)), emptyList())
        val a = DayAuditCalculator.audit(day, p, w, 0, acts, at(12, 0), isToday = false)
        assertEquals(DayAudit.WorkSource.ACTIVITY, a.workSource)
        assertEquals(60, a.workMinutes) // 10:00 – 11:00
        val withShift = DayAuditCalculator.audit(day, p, w, 15, acts, at(12, 0), isToday = false)
        assertTrue(withShift.warnings.any { it.contains("Start") })
    }
}
