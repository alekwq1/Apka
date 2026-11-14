package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ActiveJobsAndWoltHistoryTest {

    private val woltDailySummary = """
        Zarobki brutto
        NASTĘPNA WYPŁATA PO 15 LIS
        589,44 zł
        Te niezapłacone zarobki są szacunkowe i mogą ulec zmianie przy ostatecznej wypłacie
        Podsumowanie
        Zrealizowane zamówienia
        589,44 zł
        Podsumowanie dzienne
        14.11.2026
        26,78 zł
        13.11.2026
        243,28 zł
        12.11.2026
        319,38 zł
    """.trimIndent()

    private val woltDayDetails = """
        13 LIS 2026
        243,28 zł
        Zrealizowane zamówienia
        Pizzeria Stopiątka Wrzeszcz
        21:20
        Pełna płatność (w tym dystans: 8.84 km)
        46,04 zł
        Duży Ben - Cieszyńskiego
        20:03
        Pełna płatność (w tym dystans: 9.29 km)
        53,42 zł
        Kebab DRWAL - Warneńska (Morena) (dostawy: 2)
        19:34
        Pełna płatność (w tym dystans: 9.95 km)
        42,85 zł
    """.trimIndent()

    @Test
    fun `wolt daily summary totals`() {
        assertTrue(WoltHistoryParser.isDailySummaryScreen(woltDailySummary))
        val totals = WoltHistoryParser.parseDailySummary(woltDailySummary)
        assertEquals(3, totals.size)
        assertEquals(LocalDate.of(2026, 11, 14), totals[0].date)
        assertEquals(26.78, totals[0].grossPln, 0.001)
        assertEquals(319.38, totals[2].grossPln, 0.001)
        // Snapshot "Zarobki brutto" nadal dziala na tym ekranie.
        assertEquals(589.44, WoltFinanceParser.parse(woltDailySummary)!!.grossPln, 0.001)
    }

    @Test
    fun `wolt day details orders`() {
        assertTrue(WoltHistoryParser.isDayDetailsScreen(woltDayDetails))
        val details = WoltHistoryParser.parseDayDetails(woltDayDetails)!!
        assertEquals(LocalDate.of(2026, 11, 13), details.date)
        assertEquals(243.28, details.totalPln!!, 0.001)
        assertEquals(3, details.orders.size)
        val first = details.orders[0]
        assertEquals("Pizzeria Stopiątka Wrzeszcz", first.venue)
        assertEquals(21 * 60 + 20, first.minuteOfDay)
        assertEquals(46.04, first.grossPln, 0.001)
        assertEquals(8.84, first.distanceKm!!, 0.001)
        val bundle = details.orders[2]
        assertEquals("Kebab DRWAL - Warneńska (Morena)", bundle.venue)
        assertEquals(2, bundle.stops)
    }

    @Test
    fun `wolt active screens from real app`() {
        val eta = "Odbiór za 7 min\nOdrzuć\nWolt"
        assertEquals(ScreenKind.ACTIVE_JOB, ScreenClassifier.classify(eta))
        val parsedEta = WoltActiveDeliveryParser.parse(eta)!!
        assertEquals(7, parsedEta.etaMinutes)
        assertEquals(WoltActiveDelivery.Stage.TO_PICKUP, parsedEta.stage)

        val pickupFrom = "Odbiór za 10 min\nOdbiór z\nSMASHYNSKI\nAmundsena 7C, 80-288 Gdansk"
        val p = WoltActiveDeliveryParser.parse(pickupFrom)!!
        assertEquals("SMASHYNSKI", p.venue)
        assertEquals("Amundsena 7C, 80-288 Gdansk", p.pickupAddress)

        val deliverTo = "Dostawa za 24 min\nDostawa do\nDominika Bober\nJanusza Kusocińskiego 4A, 80-288 Gdańsk"
        val d = WoltActiveDeliveryParser.parse(deliverTo)!!
        assertEquals(WoltActiveDelivery.Stage.TO_CUSTOMER, d.stage)
        assertEquals("Janusza Kusocińskiego 4A, 80-288 Gdańsk", d.dropoffAddress)
        assertEquals(24, d.etaMinutes)

        val details = "Dostawa do\nPiotr Nowicki\nMarii Skłodowskiej-Curie 3A, 80-214 Gdańsk, Polska, 80-210 Gdańsk\nSzczegóły\nSzczegóły zamówienia\nZ\nSMASHYNSKI - smash burgers\n#9454\nZadzwoń do klienta\nZamówienie dostarczone"
        val x = WoltActiveDeliveryParser.parse(details)!!
        assertEquals("SMASHYNSKI - smash burgers", x.venue)
        assertTrue(x.dropoffAddress!!.startsWith("Marii Skłodowskiej-Curie 3A"))
    }

    @Test
    fun `pyszne active screens from real app`() {
        val toPickup = """
            Odbierz na 16:15
            Istanbul Döner Kebap
            Nawiguj
            Istanbul Döner Kebap
            Hynka 71B, 80-465 Gdańsk
            1 zamówienie
            Natalia Lubawska
            Zamówienie #6FG63W
            Bydgoska 4, 80-394 Gdańsk
            Notatki klienta
            (piętro 2)(Flatnumber: 16, Floor: 2)
            Zakończ odbiór
        """.trimIndent()
        assertEquals(ScreenKind.ACTIVE_JOB, ScreenClassifier.classify(toPickup))
        val a = PyszneActiveScreenParser.parse(toPickup)!!
        assertEquals(16 * 60 + 15, a.pickupMinuteOfDay)
        assertEquals("Istanbul Döner Kebap", a.restaurant)
        assertEquals("Hynka 71B, 80-465 Gdańsk", a.pickupAddress)
        assertEquals("Bydgoska 4, 80-394 Gdańsk", a.dropoffAddress)
        assertEquals(false, a.pickedUp)

        val toCustomer = """
            Dostarcz na 16:02
            Profesora Mariana Osiń...
            Nawiguj
            Natalia
            Profesora Mariana Osińskiego 8a, 80-176 Gdańsk
            Notatki klienta
            Dostarcz zamówienie
        """.trimIndent()
        val b = PyszneActiveScreenParser.parse(toCustomer)!!
        assertEquals(true, b.pickedUp)
        assertEquals("Profesora Mariana Osińskiego 8a, 80-176 Gdańsk", b.dropoffAddress)
    }

    @Test
    fun `pyszne offer details when address comes before restaurant name`() {
        val text = "Odrzuć\n19,61 zł\n6,4 km • 2 przystanki\nOdbiór:\nKazimierza Wielkiego 34, 80-180 Gdańsk\nKebab Lamh\nOdbierz na 15:48\nDostawa do klienta\nNiepołomicka 26A, 80-180 Gdańsk\nDostarcz na 15:54\nZaakceptuj zlecenie 0:53"
        val d = PyszneOfferDetailsParser.parse(text)
        assertEquals("Kebab Lamh", d.restaurant)
        assertEquals("Kazimierza Wielkiego 34, 80-180 Gdańsk", d.pickupAddress)
        assertEquals("Niepołomicka 26A, 80-180 Gdańsk", d.dropoffAddress)
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify(text))
    }

    @Test
    fun `job stage buttons`() {
        assertTrue(JobStageButtons.isPickedUp("Zakończ odbiór"))
        assertTrue(JobStageButtons.isPickedUp("Rozpocznij odbiór"))
        assertTrue(JobStageButtons.isDelivered("Dostarcz zamówienie"))
        assertTrue(JobStageButtons.isDelivered("Zamówienie dostarczone"))
        assertTrue(JobStageButtons.isDelivered("Odrzuć dostawę"))
        assertEquals(false, JobStageButtons.isDelivered("Odrzuć"))
    }

    @Test
    fun `tracker keeps one job per platform and finishes on delivered`() {
        val tracker = ActiveJobTracker()
        tracker.onAccepted(CourierPlatform.PYSZNE, Offer(19.61, 6.4, pickupTimeMinutesOfDay = 15 * 60 + 48, deliveryTimeMinutesOfDay = 15 * 60 + 54), 0L, "Kebab Lamh", "Kazimierza Wielkiego 34", "Niepołomicka 26A", 2)
        tracker.onActiveScreen(CourierPlatform.WOLT, 1_000L, null, null, "KFC Gdańsk Auchan", pickedUp = false, etaMinutes = 7)
        assertEquals(2, tracker.activeJobs(2_000L).size)
        tracker.onPickedUp(CourierPlatform.PYSZNE, 3_000L)
        assertEquals(ActiveJob.Stage.TO_CUSTOMER, tracker.activeAt(3_000L, CourierPlatform.PYSZNE)!!.stage)
        assertEquals(listOf("Niepołomicka 26A" to false), tracker.activeAt(3_000L, CourierPlatform.PYSZNE)!!.remainingAddresses())
        tracker.onFinished(CourierPlatform.PYSZNE)
        assertNull(tracker.activeAt(4_000L, CourierPlatform.PYSZNE))
        assertNotNull(tracker.activeAt(4_000L, CourierPlatform.WOLT))
        // Inna restauracja na ekranie aktywnego zlecenia tej samej platformy = nowe zlecenie.
        tracker.onActiveScreen(CourierPlatform.WOLT, 5_000L, null, null, "McDonald's - Szczęśliwa", false)
        assertEquals("McDonald's - Szczęśliwa", tracker.activeAt(5_000L, CourierPlatform.WOLT)!!.restaurant)
    }

    @Test
    fun `spatial advisor on the way vs opposite`() {
        // Gdańsk: ja w Jasieniu, aktywna dostawa na Morene (NE), nowy odbior po drodze (miedzy)
        val me = GeoPoint(54.335, 18.520)
        val activeDrop = GeoPoint(54.365, 18.580)
        val onWayPickup = GeoPoint(54.350, 18.550)
        val onWay = SpatialAdvisor.evaluate(me, listOf(activeDrop), onWayPickup, GeoPoint(54.360, 18.570))
        assertEquals(SpatialVerdict.Verdict.ON_THE_WAY, onWay.verdict)
        assertTrue(onWay.detourKm < 1.5)

        // Nowy odbior daleko na zachod (Kowale) – w przeciwna strone.
        val opposite = SpatialAdvisor.evaluate(me, listOf(activeDrop), GeoPoint(54.310, 18.470), GeoPoint(54.300, 18.450))
        assertEquals(SpatialVerdict.Verdict.OPPOSITE, opposite.verdict)
        assertTrue(opposite.detourKm > 4.0)

        // Bez aktywnego zlecenia – tylko dystans do odbioru.
        val none = SpatialAdvisor.evaluate(me, emptyList(), onWayPickup, null)
        assertEquals(SpatialVerdict.Verdict.UNKNOWN, none.verdict)
        assertNotNull(none.toNewPickupKm)
    }
}
