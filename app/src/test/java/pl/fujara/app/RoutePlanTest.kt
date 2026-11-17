package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlanTest {
    private val me = GeoPoint(54.352, 18.646)

    private fun job(stage: ActiveJob.Stage = ActiveJob.Stage.TO_PICKUP) = ActiveJob(
        platform = CourierPlatform.PYSZNE,
        acceptedAtMillis = 1_000L,
        restaurant = "Sushi Bar",
        pickupAddress = "Długa 1, 80-827 Gdańsk",
        dropoffAddress = "Granitowa 6, 80-299 Gdańsk",
        pickupMinuteOfDay = null,
        deliveryMinuteOfDay = 13 * 60 + 20,
        estimatedMinutes = null,
        distanceKm = 4.0,
        amountPln = 20.0,
        stage = stage
    )

    @Test
    fun `polyline decodes google example`() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5)
        assertEquals(-120.2, pts[0].lon, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5)
        assertEquals(-126.453, pts[2].lon, 1e-5)
    }

    @Test
    fun `address key dedupes same street and postal`() {
        val list = AddressKey.distinct(listOf("Granitowa 6, 80-299 Gdańsk", "granitowa 6 80-299 Gdansk", "Długa 1, 80-827 Gdańsk"))
        assertEquals(2, list.size)
        assertEquals("80-299", AddressKey.postalCode("Granitowa 6, 80-299 Gdańsk"))
        assertEquals(AddressKey.of("Granitowa 6 Gdańsk"), AddressKey.of("ul. Granitowa 6, Gdańsk"))
        assertEquals("grunwaldzka12a/3", AddressKey.of("Grunwaldzka 12a/3, Gdańsk"))
    }

    @Test
    fun `ui noise names are filtered`() {
        assertTrue(UiNoise.isNoise("button"))
        assertTrue(UiNoise.isNoise("copyIcon"))
        assertFalse(UiNoise.isNoise("KFC Galeria Bałtycka"))
        val j = job().copy(restaurant = "button")
        assertEquals("Długa 1", j.label)
    }

    @Test
    fun `job progress pickup then drop finishes`() {
        val pickup = GeoPoint(54.3500, 18.6500)
        val drop = GeoPoint(54.3800, 18.6000)
        var j = job()
        // przy restauracji
        var u = JobProgress.update(j, GeoPoint(54.3501, 18.6501), pickup, drop, 10_000L)
        assertTrue(u.changed); j = u.job
        assertEquals(ActiveJob.Stage.TO_PICKUP, j.stage)
        // odjechal > 450 m
        u = JobProgress.update(j, GeoPoint(54.3600, 18.6400), pickup, drop, 200_000L)
        j = u.job
        assertEquals(ActiveJob.Stage.TO_CUSTOMER, j.stage)
        assertTrue(j.remainingAddresses().none { it.second })
        // u klienta
        u = JobProgress.update(j, GeoPoint(54.3800, 18.6001), pickup, drop, 400_000L)
        j = u.job; assertFalse(u.finished)
        // za krotko u klienta -> nie konczy
        u = JobProgress.update(j, GeoPoint(54.3700, 18.6200), pickup, drop, 410_000L)
        assertFalse(u.finished)
        u = JobProgress.update(j, GeoPoint(54.3700, 18.6200), pickup, drop, 460_000L)
        assertTrue(u.finished)
    }

    @Test
    fun `no location means no progress`() {
        val u = JobProgress.update(job(), null, GeoPoint(54.35, 18.65), null, 1L)
        assertFalse(u.changed)
    }

    @Test
    fun `planner marks on the way and opposite`() {
        val drop = GeoPoint(54.400, 18.646)        // ~5 km na polnoc
        val active = listOf(PlanStop(drop, false, false, ""))
        val onWay = RoutePlanner.plan(me, active, listOf(PlanStop(GeoPoint(54.375, 18.646), true, true, ""), PlanStop(GeoPoint(54.398, 18.646), false, true, ""))) { RoutePlanner.estimate(it) }
        assertEquals(SpatialVerdict.Verdict.ON_THE_WAY, onWay.verdict)
        val opposite = RoutePlanner.plan(me, active, listOf(PlanStop(GeoPoint(54.300, 18.646), true, true, ""), PlanStop(GeoPoint(54.280, 18.646), false, true, ""))) { RoutePlanner.estimate(it) }
        assertEquals(SpatialVerdict.Verdict.OPPOSITE, opposite.verdict)
        assertTrue(opposite.addedMinutes > onWay.addedMinutes)
        val text = RoutePlanner.describe(onWay, null, 12 * 60, "pl")
        assertTrue(text.startsWith("PO DRODZE"))
    }

    @Test
    fun `planner without active jobs is unknown`() {
        val p = RoutePlanner.plan(me, emptyList(), listOf(PlanStop(GeoPoint(54.36, 18.65), true, true, ""))) { RoutePlanner.estimate(it) }
        assertEquals(SpatialVerdict.Verdict.UNKNOWN, p.verdict)
        assertNull(p.base)
    }

    @Test
    fun `pyszne idle screen detected`() {
        assertTrue(ScreenClassifier.isPyszneIdle("Szukamy dla Ciebie zleceń\nPowiadomimy Cię, gdy pojawi się nowe zlecenie"))
        assertFalse(ScreenClassifier.isPyszneIdle("Dostarcz na 13:20\nGranitowa 6"))
    }
}

class ActivityWorkTimeTest {
    private val m = 60_000L

    @Test
    fun `breaks longer than 25 min are not counted`() {
        val t0 = 1_000_000_000L
        val acts = listOf(t0, t0 + 10 * m, t0 + 20 * m, t0 + 80 * m, t0 + 95 * m)
        // 20 min + (przerwa 60 min pominieta) + 15 min + ogon 5 min
        assertEquals(40, ActivityWorkTime.minutes(acts, t0 + 100 * m))
        // po dlugiej ciszy ogon nie rosnie
        assertEquals(35, ActivityWorkTime.minutes(acts, t0 + 200 * m))
        assertEquals(90, ActivityWorkTime.effective(90, acts, t0 + 100 * m))
        assertEquals(0, ActivityWorkTime.minutes(emptyList(), t0))
    }
}
