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
    fun `wolt daily summary when amount precedes date in accessibility tree`() {
        // Uklad z drzewa dostepnosci: kwota wiersza PRZED data – 1.3 przypisal 243,28 zł do 14.11.
        val text = "Zarobki brutto\nNASTĘPNA WYPŁATA PO 15 LIS\n625,10 zł\nPodsumowanie\nZrealizowane zamówienia\n625,10 zł\nPodsumowanie dzienne\n62,44 zł\n14.11.2026\n243,28 zł\n13.11.2026\n319,38 zł\n11.11.2026"
        val totals = WoltHistoryParser.parseDailySummary(text).associate { it.date to it.grossPln }
        assertEquals(62.44, totals[LocalDate.of(2026, 11, 14)]!!, 0.001)
        assertEquals(243.28, totals[LocalDate.of(2026, 11, 13)]!!, 0.001)
        assertEquals(319.38, totals[LocalDate.of(2026, 11, 11)]!!, 0.001)
        // Ten sam ekran w ukladzie "data, kwota" tez daje poprawny wynik.
        val normal = WoltHistoryParser.parseDailySummary(woltDailySummary).associate { it.date to it.grossPln }
        assertEquals(26.78, normal[LocalDate.of(2026, 11, 14)]!!, 0.001)
    }

    @Test
    fun `wolt day details with venue split across lines`() {
        val text = "14 LIS 2026\n62,44 zł\nZrealizowane zamówienia\nSMASHYNSKI, SMASHYNSKI - smash\nburgers\n18:42\nPełna płatność (w tym dystans: 10.13 km)\n35,66 zł\nSushi Dla Mnie Galeria Bałtycka\n17:12\nPełna płatność (w tym dystans: 8.22 km)\n26,78 zł"
        val d = WoltHistoryParser.parseDayDetails(text)!!
        assertEquals(2, d.orders.size)
        assertEquals("SMASHYNSKI, SMASHYNSKI - smash burgers", d.orders[0].venue)
        assertEquals(10.13, d.orders[0].distanceKm!!, 0.001)
        // Ten sam kurs odczytany inaczej (OCR) ma ten sam klucz – brak duplikatu.
        assertEquals(d.orders[0].key, d.orders[0].copy(venue = "burgers").key)
    }

    @Test
    fun `wolt expanded timeline with several drops`() {
        val text = "25,76 zł\nSpodziewany zarobek za pełną dostawę\n2 dostaw(y) od\nKebab DRWAL - Warneńska (Morena)\nOdległość\n11.1 km\nSzacowany\n31 - 34 min\nRamy czasowe\n2\nKebab DRWAL - Warneńsk…\nGotowe\nWarneńska 8C, 80-288 Gdansk\nKamila D.\n-30 min\nMyśliwskie Wzgórze 8c, 80-283 Gdańsk\nAdam S.\n41 min\nJanusza Kusocińskiego 5/106, 80-288 Gdańsk\nAkceptuj"
        val stops = WoltOfferDetailsParser.stopsFromTimeline(text)
        assertEquals(3, stops.size)
        assertEquals(listOf(true, false, false), stops.map { it.isPickup })
        assertEquals("Warneńska 8C, 80-288 Gdansk", stops[0].address)
        assertEquals("Janusza Kusocińskiego 5/106, 80-288 Gdańsk", stops[2].address)
        val single = "30,70 zł\nSpodziewany zarobek\nDostawa od\nKFC Gdańsk Orlen Elblaska\nRamy czasowe\nKFC Gdańsk Orlen Elblaska\n1 min\nMiałki Szlak 2, 80-717 Gdańsk\nHanna S\nPasieczna 4, 80-180 Gdańsk\nAkceptuj"
        val s2 = WoltOfferDetailsParser.stopsFromTimeline(single)
        assertEquals(listOf(true, false), s2.map { it.isPickup })
        assertEquals("Miałki Szlak 2, 80-717 Gdańsk", s2[0].address)
    }

    @Test
    fun `reject confirm dialog and standalone reject are offer screens`() {
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify("Na pewno chcesz odrzucić tę dostawę?\nStracisz zarobki\nOdrzuć dostawę\nRozpocznij odbiór"))
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify("X Odrzuć\nNawiguj\nOdbierz na 22:27\nHolyBurgerGdansk"))
        assertEquals(ScreenKind.ACTIVE_JOB, ScreenClassifier.classify("Dostawa do\nPiotr Nowicki\nMarii Skłodowskiej-Curie 3A, 80-214 Gdańsk\nProblemy z dostawą?\nZamówienie dostarczone"))
    }

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
        // Dymek "Odbiór za 7 min" + "Odrzuć" to PODGLAD OFERTY, nie aktywne zlecenie (blad 1.3).
        val eta = "Odbiór za 7 min\nOdrzuć\nWolt"
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify(eta))
        assertNull(WoltActiveDeliveryParser.parse(eta))
        assertEquals(ScreenKind.OTHER, ScreenClassifier.classify("Odbiór za 1 min\nGdańsk"))

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
        assertTrue(JobStageButtons.isCancelled("Odrzuć dostawę"))
        assertEquals(false, JobStageButtons.isDelivered("Odrzuć dostawę"))
        assertEquals(false, JobStageButtons.isDelivered("Odrzuć"))
    }

    @Test
    fun `tracker keeps one job per platform and finishes on delivered`() {
        val tracker = ActiveJobTracker()
        tracker.onAccepted(CourierPlatform.PYSZNE, Offer(19.61, 6.4, pickupTimeMinutesOfDay = 15 * 60 + 48, deliveryTimeMinutesOfDay = 15 * 60 + 54), 0L, "Kebab Lamh", "Kazimierza Wielkiego 34", "Niepołomicka 26A", 2)
        tracker.onActiveScreen(CourierPlatform.WOLT, 1_000L, null, null, "KFC Gdańsk Auchan", pickedUp = false, etaMinutes = 7)
        assertEquals(2, tracker.activeJobs(2_000L).size)
        // Wolt widziany tylko raz na ekranie – jeszcze niepotwierdzony, nie pokazujemy go.
        assertEquals(1, tracker.displayJobs(2_000L).size)
        tracker.onActiveScreen(CourierPlatform.WOLT, 4_500L, null, null, "KFC Gdańsk Auchan", pickedUp = false)
        assertEquals(2, tracker.displayJobs(4_500L).size)
        tracker.onPickedUp(CourierPlatform.PYSZNE, 3_000L)
        assertEquals(ActiveJob.Stage.TO_CUSTOMER, tracker.activeAt(3_000L, CourierPlatform.PYSZNE)!!.stage)
        assertEquals(listOf("Niepołomicka 26A" to false), tracker.activeAt(3_000L, CourierPlatform.PYSZNE)!!.remainingAddresses())
        tracker.onFinished(CourierPlatform.PYSZNE)
        assertNull(tracker.activeAt(5_000L, CourierPlatform.PYSZNE))
        assertNotNull(tracker.activeAt(5_000L, CourierPlatform.WOLT))
        // Inna restauracja na ekranie aktywnego zlecenia tej samej platformy = nowe zlecenie.
        tracker.onActiveScreen(CourierPlatform.WOLT, 6_000L, null, null, "McDonald's - Szczęśliwa", false)
        assertEquals("McDonald's - Szczęśliwa", tracker.activeAt(6_000L, CourierPlatform.WOLT)!!.restaurant)
        // Zlecenie bez nazwy i adresu nigdy nie jest pokazywane ("W TRAKCIE: Wolt Wolt").
        val ghost = ActiveJobTracker()
        ghost.onActiveScreen(CourierPlatform.WOLT, 0L, null, null, null, false)
        ghost.onActiveScreen(CourierPlatform.WOLT, 10_000L, null, null, null, false)
        assertTrue(ghost.displayJobs(10_000L).isEmpty())
    }

    @Test
    fun `pyszne offer card with both schedules is never an active job`() {
        // Czesciowy odczyt karty bez przycisku "Zaakceptuj" (OCR) – kiedys tworzyl widmowe zlecenie.
        val partial = "Odrzuć\nNawiguj\n19,61 zł\n6,4 km • 2 przystanki\nOdbiór: Lena Grill Kebab\nLelewela 18a, 80-422 Gdańsk\nOdbierz na 21:41\nDostawa do klienta\nBiała 12, 80-435 Gdańsk\nDostarcz na 21:45"
        assertEquals(ScreenKind.OFFER_CARD, ScreenClassifier.classify(partial))
        assertNull(PyszneActiveScreenParser.parse(partial))
        // Prawdziwy ekran aktywny ma tylko jeden termin.
        assertEquals(ScreenKind.ACTIVE_JOB, ScreenClassifier.classify("Odbierz na 21:41\nLena Grill Kebab\nNawiguj"))
    }

    @Test
    fun `wolt expanded offer addresses`() {
        val text = "Odrzuć\n43,17 zł\nSpodziewany zarobek za pełną dostawę\n2 dostaw(y) od\nMcDonald's - Morena\nOdbiór\nPiekarnicza 1, 80-126 Gdańsk\nDostawa\nMyśliwska 33/5, 80-283 Gdańsk\nOdległość\n11.7 km\nAkceptuj"
        val (pickup, drop) = WoltOfferDetailsParser.addresses(text)
        assertEquals("Piekarnicza 1, 80-126 Gdańsk", pickup)
        assertEquals("Myśliwska 33/5, 80-283 Gdańsk", drop)
        val noLabels = "Spodziewany zarobek\nDostawa od\nKFC\nJana Pawła II 19, 80-462 Gdańsk\nChłopska 20E, 80-375 Gdańsk\nAkceptuj"
        val (p2, d2) = WoltOfferDetailsParser.addresses(noLabels)
        assertEquals("Jana Pawła II 19, 80-462 Gdańsk", p2)
        assertEquals("Chłopska 20E, 80-375 Gdańsk", d2)
    }

    @Test
    fun `live wolt order merges with official row and keeps measured duration`() {
        val day = LocalDate.of(2026, 11, 14)
        val live = WoltOrder(day, 18 * 60 + 35, "SMASHYNSKI - smash burgers", 35.66, 10.1, 1, WoltOrder.SOURCE_LIVE, durationMinutes = 27)
        val official = WoltOrder(day, 18 * 60 + 42, "SMASHYNSKI, SMASHYNSKI - smash burgers", 35.66, 10.13, 1)
        val other = WoltOrder(day, 17 * 60 + 12, "Sushi Dla Mnie Galeria Bałtycka", 26.78, 8.22, 1)
        val merged = WoltOrderMerger.merge(listOf(live, official, other))
        assertEquals(2, merged.size)
        val smash = merged.first { it.grossPln == 35.66 }
        assertEquals(WoltOrder.SOURCE_OFFICIAL, smash.source)
        assertEquals(27, smash.durationMinutes)
        assertEquals(10.13, smash.distanceKm!!, 0.001)
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
