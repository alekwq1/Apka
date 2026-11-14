package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltAndActiveJobParserTest {

    private val woltFinance = """
        Finanse
        ZAROBKI BRUTTO
        386,51 zł
        Następna wypłata po 15 lis
        Ostatnie transakcje
        Tutaj zobaczysz dokonane przez Ciebie transakcje finansowe oraz wszystkie regularne płatności.
        Zobacz więcej
    """.trimIndent()

    private val woltOffer = """
        Odrzuć
        Gotowe
        16,85 zł
        Spodziewany zarobek za pełną dostawę
        Dostawa od
        Carrefour Przywidzka
        Odległość
        2.2 km
        Szacowany
        12 - 15 min
        Akceptuj
    """.trimIndent()

    private val woltActive = """
        ERGO ARENA
        Odbierz na 18:04
        KFC, Galeria Bałtycka
        Nawiguj
    """.trimIndent()

    private val pyszneOffer = """
        Odrzuć
        Nawiguj
        10,00 zł
        1,8 km • 2 przystanki
        Odbiór: Restauracja Orzeł
        Warszawska 88, 80-180 Gdańsk
        Odbierz na 14:12
        Dostawa do klienta
        Cedrowa 41/173, 80-126 Gdańsk
        Dostarcz na 14:16
        Zaakceptuj zlecenie 0:51
    """.trimIndent()

    private val pyszneActive = """
        Odbierz na 19:26
        Sakhawat Kebab
        Nawiguj
    """.trimIndent()

    private val stuartOffer = """
        Nowe zlecenie • 16... Od KFC - 103003 • 7,3...
        16,27zł Szacow...
        Łącznie 7,35 km x1 zamówienia
        KFC - 103003 - 19 Al. Jana Pawła II, 80-462 Gdańsk 4,21 km
        20E UL. CHŁOPSKA, 80-375 Gdańsk GDAŃSK 3,14 km
        medium
        Akceptuj
        10 sekund do automatycznego odrzucenia
    """.trimIndent()

    @Test
    fun `wolt finance screen is parsed`() {
        assertTrue(WoltFinanceParser.isFinanceScreen(woltFinance))
        val snapshot = WoltFinanceParser.parse(woltFinance, nowMillis = 5L)
        assertNotNull(snapshot)
        assertEquals(386.51, snapshot!!.grossPln, 0.001)
        assertEquals("Następna wypłata po 15 lis", snapshot.nextPayoutLabel)
        assertNull(WoltFinanceParser.parse(woltOffer))
    }

    @Test
    fun `wolt finance with thousands separator`() {
        val snapshot = WoltFinanceParser.parse("ZAROBKI BRUTTO\n1 420,35 zł\nNastępna wypłata po 30 lis")
        assertEquals(1420.35, snapshot!!.grossPln, 0.001)
    }

    @Test
    fun `wolt active delivery screen is parsed`() {
        assertTrue(WoltActiveDeliveryParser.isActiveDeliveryScreen(woltActive))
        assertFalse(WoltActiveDeliveryParser.isActiveDeliveryScreen(woltOffer))
        val active = WoltActiveDeliveryParser.parse(woltActive)!!
        assertEquals(18 * 60 + 4, active.pickupMinuteOfDay)
        assertEquals("KFC, Galeria Bałtycka", active.venue)
        assertEquals(WoltActiveDelivery.Stage.TO_PICKUP, active.stage)
    }

    @Test
    fun `wolt offer details restaurant and stops`() {
        assertEquals("Carrefour Przywidzka", WoltOfferDetailsParser.restaurant(woltOffer))
        assertEquals(1, WoltOfferDetailsParser.stops(woltOffer))
        val bundle = "34,45 zł\nSpodziewany zarobek za pełną dostawę\n3 dostaw(y) od\nPrzystanek - Crêpes & Wine, Restauracj... +1\nOdległość\n6.6 km\nAkceptuj"
        assertEquals(3, WoltOfferDetailsParser.stops(bundle))
        assertEquals("Przystanek - Crêpes & Wine, Restauracj...", WoltOfferDetailsParser.restaurant(bundle))
    }

    @Test
    fun `screen classifier distinguishes offer, active job and history`() {
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify(woltOffer))
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify(pyszneOffer))
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify(stuartOffer))
        assertEquals(ScreenKind.ACTIVE_JOB, ScreenClassifier.classify(woltActive))
        assertEquals(ScreenKind.ACTIVE_JOB, ScreenClassifier.classify(pyszneActive))
        assertEquals(ScreenKind.HISTORY_DETAILS, ScreenClassifier.classify("Szczegóły zlecenia\nZlecenie dostarczone\nCzas aktywności\n10 min 7 sec"))
        assertEquals(ScreenKind.OTHER, ScreenClassifier.classify(woltFinance))
    }

    @Test
    fun `pyszne offer details with addresses`() {
        val details = PyszneOfferDetailsParser.parse(pyszneOffer)
        assertEquals("Restauracja Orzeł", details.restaurant)
        assertEquals("Warszawska 88, 80-180 Gdańsk", details.pickupAddress)
        assertEquals("Cedrowa 41/173, 80-126 Gdańsk", details.dropoffAddress)
        assertEquals(2, details.stops)
    }

    @Test
    fun `feasibility with explicit pickup time`() {
        val job = ActiveJob(
            platform = CourierPlatform.PYSZNE,
            acceptedAtMillis = 0L,
            restaurant = "Orzeł",
            pickupAddress = null,
            dropoffAddress = null,
            pickupMinuteOfDay = 14 * 60,
            deliveryMinuteOfDay = 14 * 60 + 16,
            estimatedMinutes = null,
            distanceKm = 1.8,
            amountPln = 10.0
        )
        val tooEarly = Offer(amountPln = 12.0, distanceKm = 2.0, pickupTimeMinutesOfDay = 14 * 60 + 12)
        val conflict = OfferFeasibilityCalculator.evaluate(job, tooEarly, currentMinuteOfDay = 14 * 60 + 5, travelToNewPickupMinutes = 5)
        assertEquals(OfferFeasibility.Verdict.CONFLICT, conflict.verdict)
        assertEquals(-9, conflict.slackMinutes)

        val later = Offer(amountPln = 12.0, distanceKm = 2.0, pickupTimeMinutesOfDay = 14 * 60 + 30)
        val ok = OfferFeasibilityCalculator.evaluate(job, later, currentMinuteOfDay = 14 * 60 + 5, travelToNewPickupMinutes = 5)
        assertEquals(OfferFeasibility.Verdict.OK, ok.verdict)
        assertEquals(9, ok.slackMinutes)
    }

    @Test
    fun `feasibility without pickup time uses remaining minutes`() {
        val job = ActiveJob(
            platform = CourierPlatform.WOLT,
            acceptedAtMillis = 0L,
            restaurant = "KFC",
            pickupAddress = null,
            dropoffAddress = null,
            pickupMinuteOfDay = null,
            deliveryMinuteOfDay = 18 * 60 + 30,
            estimatedMinutes = 15,
            distanceKm = 2.2,
            amountPln = 16.85
        )
        val offer = Offer(amountPln = 6.89, distanceKm = 2.7, durationMinutes = 11)
        val conflict = OfferFeasibilityCalculator.evaluate(job, offer, currentMinuteOfDay = 18 * 60 + 4, travelToNewPickupMinutes = null)
        assertEquals(OfferFeasibility.Verdict.CONFLICT, conflict.verdict)
        val ok = OfferFeasibilityCalculator.evaluate(job, offer, currentMinuteOfDay = 18 * 60 + 28, travelToNewPickupMinutes = null)
        assertEquals(OfferFeasibility.Verdict.OK, ok.verdict)
    }

    @Test
    fun `active job tracker expires stale jobs`() {
        val tracker = ActiveJobTracker(staleAfterMs = 1_000L)
        tracker.onAccepted(CourierPlatform.WOLT, Offer(10.0, 2.0, 12), 0L, "KFC", null, null, 1)
        assertNotNull(tracker.activeAt(500L))
        assertNull(tracker.activeAt(2_000L))
    }
}
