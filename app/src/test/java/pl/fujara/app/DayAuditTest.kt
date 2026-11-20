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
        assertEquals(18.0, w.grossPln, 0.001) // jak Pyszne: suma zapisanych zlecen
        assertTrue(w.warnings.any { it.contains("55,00") })
    }

    @Test
    fun `hourly only from timer and forgotten start detected`() {
        val p = DayAuditCalculator.pyszne(day, listOf(log("1", 30.0, 5.0, 600), log("2", 30.0, 5.0, 640)), null, emptyList(), rules, 0.0)
        val w = DayAuditCalculator.wolt(day, emptyList(), null, emptyList(), rules, 0.0)
        val acts = DayAuditCalculator.activityMillis(day, emptyList(), listOf(log("1", 30.0, 5.0, 600), log("2", 30.0, 5.0, 640)), emptyList())
        val a = DayAuditCalculator.audit(day, p, w, 0, acts, at(12, 0), isToday = false)
        assertEquals(DayAudit.WorkSource.NONE, a.workSource)
        assertEquals(0, a.workMinutes)
        assertEquals(null, a.netPerHour)
        assertTrue(a.warnings.any { it.contains("Timer") && it.contains("1h 00m") })
        val withShift = DayAuditCalculator.audit(day, p, w, 15, acts, at(12, 0), isToday = false)
        assertTrue(withShift.warnings.any { it.contains("Start") })
    }
}

class WoltParserSanityTest {
    @Test
    fun `day header and status bar are not orders`() {
        val text = """
            20:42 A• 5.! 82
            18 LIS 2026
            74,32 zł
            Zrealizowane zamówienia
            Pizzeria Stopiątka Fabryka 19:05
            Pełna płatność (w tym dystans: 5.29 km) 38,60 zł
            McDonald's - Szczęśliwa 18:30
            Pełna płatność (w tym dystans: 4.51 km) 35,72 zł
            ↑ Kieruj się na południowy wschód
            11:26
        """.trimIndent()
        val d = WoltHistoryParser.parseDayDetails(text)!!
        assertEquals(2, d.orders.size)
        assertEquals(74.32, d.ordersSum, 0.001)
        assertTrue(d.matchesTotal)
    }

    @Test
    fun `clean drops order equal to day total`() {
        val day = LocalDate.of(2026, 11, 18)
        val orders = listOf(
            WoltOrder(day, 1110, "KFC", 37.96, 5.6, 1),
            WoltOrder(day, 1145, "Pizzeria", 38.60, 5.3, 1),
            WoltOrder(day, 1242, "A 5 83 18 LIS 2026", 76.56, null, 1),
            WoltOrder(day, 1236, "SUCHANINO", 0.0, null, 1, source = WoltOrder.SOURCE_LIVE)
        )
        val c = WoltOrderSanity.clean(orders, 76.56)
        assertEquals(2, c.size)
    }

    @Test
    fun `live order merges with official by amount`() {
        val day = LocalDate.of(2026, 11, 20)
        val merged = WoltOrderMerger.merge(listOf(
            WoltOrder(day, 750, "McDonald's - Szczęśliwa", 36.56, 9.3, 1),
            WoltOrder(day, 735, "Morena 12", 36.56, null, 1, source = WoltOrder.SOURCE_LIVE, durationMinutes = 25)
        ))
        assertEquals(1, merged.size)
        assertEquals(25, merged[0].durationMinutes)
    }
}
