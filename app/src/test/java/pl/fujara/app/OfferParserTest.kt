package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class OfferParserTest {

    @Test
    fun parsesPolishOfferWithDirectMinutes() {
        val text = """
            17,52 zł
            3,8 km · 2 przystanki
            Odbiór: McDonald's Opole, Ozimska
            15 min
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(17.52, offer!!.amountPln, 0.001)
        assertEquals(3.8, offer.distanceKm, 0.001)
        assertEquals(15, offer.durationMinutes)
    }

    @Test
    fun parsesPysznePickupAndDeliveryClockTimes() {
        val text = """
            17,52 zł
            3,8 km · 2 przystanki
            Odbiór: McDonald's Opole, Ozimska
            Ozimska 197, 45-310 Opole
            Odbierz na 20:54
            Dostawa: Zakrzchenko
            Kielecka 12, 46-020 Opole
            Dostarcz na 20:57
            Zaakceptuj zlecenie 0:49
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(17.52, offer!!.amountPln, 0.001)
        assertEquals(3.8, offer.distanceKm, 0.001)
        assertNull(offer.durationMinutes)
        assertEquals(20 * 60 + 54, offer.pickupTimeMinutesOfDay)
        assertEquals(20 * 60 + 57, offer.deliveryTimeMinutesOfDay)
    }

    @Test
    fun parsesPyszneTimesWhenOcrUsesDots() {
        val text = """
            17.52 PLN
            3.80 km
            Odbierz na 20.54
            Dostarcz na 20.57
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(20 * 60 + 54, offer!!.pickupTimeMinutesOfDay)
        assertEquals(20 * 60 + 57, offer.deliveryTimeMinutesOfDay)
    }

    @Test
    fun parsesPyszneOfferFromRealGdanskCard() {
        val text = """
            20,56 zł
            5,3 km · 2 przystanki
            Odbiór: Pasibus
            Stągiewna 27, 80-750 Gdańsk
            Odbierz na 13:26
            Dostawa: Katarzyna Lewicz
            Świętego Ducha 105/107 m. 7, 80-834 Gdańsk
            Dostarcz na 13:37
            Zaakceptuj zlecenie 0:39
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)

        assertNotNull(offer)
        assertEquals(20.56, offer!!.amountPln, 0.001)
        assertEquals(5.3, offer.distanceKm, 0.001)
        assertNull(offer.durationMinutes)
        assertEquals(13 * 60 + 26, offer.pickupTimeMinutesOfDay)
        assertEquals(13 * 60 + 37, offer.deliveryTimeMinutesOfDay)
    }

    @Test
    fun pyszneFindsDeliveryTimeEvenWhenOcrPlacesItFarFromAmount() {
        val filler = (1..90).joinToString("\n") { "etykieta mapy numer $it" }
        val text = """
            20,56 zł
            5,3 km · 2 przystanki
            $filler
            Odbierz na 13:26
            Dostarcz na 13:37
            Zaakceptuj zlecenie 0:39
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)

        assertNotNull(offer)
        assertEquals(13 * 60 + 26, offer!!.pickupTimeMinutesOfDay)
        assertEquals(13 * 60 + 37, offer.deliveryTimeMinutesOfDay)
        assertNull(offer.durationMinutes)
    }

    @Test
    fun pyszneAcceptsOcrClockWithoutColon() {
        val text = """
            20,56 zł
            5,3 km
            Odbierz na 13 26
            Dostarcz na 1337
            Zaakceptuj zlecenie 0:39
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)

        assertNotNull(offer)
        assertEquals(13 * 60 + 26, offer!!.pickupTimeMinutesOfDay)
        assertEquals(13 * 60 + 37, offer.deliveryTimeMinutesOfDay)
    }

    @Test
    fun parsesUberTotalDuration() {
        val text = """
            PLN25.42
            26 min (5.4 km) total
            Delivery
            Confirm
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(25.42, offer!!.amountPln, 0.001)
        assertEquals(5.4, offer.distanceKm, 0.001)
        assertEquals(26, offer.durationMinutes)
        assertNull(offer.deliveryTimeMinutesOfDay)
    }

    @Test
    fun prefersUberTotalDurationOverPickupEta() {
        val text = """
            PLN25.42
            Pickup in 3 min
            26 min (5.4 km) total
            Delivery
            Confirm
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(25.42, offer!!.amountPln, 0.001)
        assertEquals(5.4, offer.distanceKm, 0.001)

        // Ma być 26 minut całej dostawy,
        // a nie 3 minuty do odbioru.
        assertEquals(26, offer.durationMinutes)
    }

    @Test
    fun usesLastOfferWhenMultipleOffersAreVisible() {
        val text = """
            10,00 zł
            2,0 km
            10 min

            21,50 zł
            5,0 km
            30 min
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(21.50, offer!!.amountPln, 0.001)
        assertEquals(5.0, offer.distanceKm, 0.001)
        assertEquals(30, offer.durationMinutes)
    }

    @Test
    fun ignoresHiddenSmallAmountInsideUberAccessibilityTree() {
        val text = """
            Uber
            Delivery
            PLN17.71
            1.00 PLN
            31 min (22.4 km) total
            KFC (Matarnia)
            Confirm
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(17.71, offer!!.amountPln, 0.001)
        assertEquals(22.4, offer.distanceKm, 0.001)
        assertEquals(31, offer.durationMinutes)
    }

    @Test
    fun parsesUberFloatingOfferCardShownOverLauncher() {
        val text = """
            22:59
            Uber
            Delivery
            PLN15.10
            27 min (14.9 km) total
            SUBWAY Gdańsk Ikea
            Cicha & Północna, Gdansk
            Confirm
        """.trimIndent()

        val offer = OfferParser.parse(text)

        assertNotNull(offer)
        assertEquals(15.10, offer!!.amountPln, 0.001)
        assertEquals(14.9, offer.distanceKm, 0.001)
        assertEquals(27, offer.durationMinutes)
    }

    @Test
    fun boltUsesWholeRouteSummaryInsteadOfPickupLeg() {
        val text = """
            Bolt Food
            Pizza Hut - Worcella
            ~3.3 km, ~17 min
            ~4.4 km, ~18 min
            7.7 km, 35 min, 20,62 zł
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.BOLT)

        assertNotNull(offer)
        assertEquals(20.62, offer!!.amountPln, 0.001)
        assertEquals(7.7, offer.distanceKm, 0.001)
        assertEquals(35, offer.durationMinutes)
    }

    @Test
    fun woltUsesExpectedEarningsAndUpperEtaNotCashChange() {
        val text = """
            48,12 zł
            Spodziewany zarobek za pełną dostawę
            Dostawa od Warzywina
            Odległość 24.7 km
            Szacowany 45 - 48 min
            Zamówienie gotówkowe
            Całkowita kwota reszty 1,10 zł
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.WOLT)

        assertNotNull(offer)
        assertEquals(48.12, offer!!.amountPln, 0.001)
        assertEquals(24.7, offer.distanceKm, 0.001)
        assertEquals(48, offer.durationMinutes)
    }

    @Test
    fun pyszneHistoryIsNotAnActiveOffer() {
        val text = """
            Job history
            246 jobs
            167.00 tips (PLN)
            26.9 km
            Wed, Aug 12
            ID: 257325094 5.4 km
            17:48 Trakt Św. Wojciecha
            17:58 Spadzista
        """.trimIndent()

        assertNull(OfferParser.parse(text, CourierPlatform.PYSZNE))
    }

    @Test
    fun discordConversationMentioningPyszneIsNotAnOffer() {
        val text = """
            # ogólne
            Ale mnie pyszne wkurwiło teraz
            jechałem do niego 8 km
            za anulowane zamówienie jest 3,80
            paliwo nawet
        """.trimIndent()

        assertNull(OfferParser.parse(text, CourierPlatform.PYSZNE))
    }

    @Test
    fun stuartConvertsMilesToKilometresAndAllowsMissingTime() {
        val text = """
            23.66zł
            Estimated earnings
            4.55 mi total · Extra large
            Ugry bbq spot
            25B Chabrowa
            Accept
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.STUART)

        assertNotNull(offer)
        assertEquals(23.66, offer!!.amountPln, 0.001)
        assertEquals(7.3225, offer.distanceKm, 0.001)
        assertNull(offer.durationMinutes)
    }

    @Test
    fun woltEnglishCardUsesPrefixedPlnAndIgnoresRoadNumber() {
        val text = """
            19:28
            221
            PLN 10.27
            Expected earnings for the full delivery
            Delivery from
            McDonald's - Gdańsk Świętokrzyska
            Route distance
            4.1 km
            Estimated
            11 - 14 min
            Accept
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.WOLT)

        assertNotNull(offer)
        assertEquals(10.27, offer!!.amountPln, 0.001)
        assertEquals(4.1, offer.distanceKm, 0.001)
        assertEquals(14, offer.durationMinutes)
    }

    @Test
    fun currencyMarkerDoesNotAttachToNumberFromAnotherLine() {
        val text = """
            PLN
            221
            Route distance 4.1 km
            Estimated 14 min
        """.trimIndent()

        assertNull(OfferParser.parse(text, CourierPlatform.GLOVO))
    }

    @Test
    fun woltPolishSzacowaneZarobkiIsParsed() {
        val text = """
            17,42zł
            Szacowane zarobki
            8,3 km total · Średnia
            PH - 104259 - 34 Kołobrzeska, Gdańsk
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.WOLT)

        assertNotNull(offer)
        assertEquals(17.42, offer!!.amountPln, 0.001)
        assertEquals(8.3, offer.distanceKm, 0.001)
        assertNull(offer.durationMinutes)
    }

    @Test
    fun stuartPolishSzacowaneZarobkiSupportsKilometres() {
        val text = """
            17,42zł
            Szacowane zarobki
            8,3 km total · Średnia
            Restauracja Test
            Klient Test
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.STUART)

        assertNotNull(offer)
        assertEquals(17.42, offer!!.amountPln, 0.001)
        assertEquals(8.3, offer.distanceKm, 0.001)
        assertNull(offer.durationMinutes)
    }

    @Test
    fun uberPolishCardWithLacznieBeforeMinutesIsParsed() {
        val text = """
            Dostawa
            11,31 zł
            Łącznie 20 min (5.1 km)
            McDonald's Orunia Górna
            Białostocka & Warszawska, Gdańsk
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.UBER)

        assertNotNull(offer)
        assertEquals(11.31, offer!!.amountPln, 0.001)
        assertEquals(5.1, offer.distanceKm, 0.001)
        assertEquals(20, offer.durationMinutes)
    }

    @Test
    fun pyszneEnglishOfferReadsPickupAndDeliveryTimes() {
        val text = """
            12,34 PLN
            3.0 km · 2 stops
            Pick up: Pierogarnia Jedynka
            Pick up at 17:33
            Delivery: Weronika Brzuszkiewicz
            Deliver at 17:38
            Accept offer
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)

        assertNotNull(offer)
        assertEquals(12.34, offer!!.amountPln, 0.001)
        assertEquals(3.0, offer.distanceKm, 0.001)
        assertEquals(17 * 60 + 33, offer.pickupTimeMinutesOfDay)
        assertEquals(17 * 60 + 38, offer.deliveryTimeMinutesOfDay)
        assertNull(offer.durationMinutes)
    }

    @Test
    fun stuartPolishKilometresAreNotMistakenForMinutesAsMiles() {
        val text = """
            13,29 zł
            Szacowany zarobek za pełną dostawę
            Dostawa od THAI SUN Express
            Odległość 4.5 km
            Szacowany 13 - 16 min
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.STUART)

        assertNotNull(offer)
        assertEquals(13.29, offer!!.amountPln, 0.001)
        assertEquals(4.5, offer.distanceKm, 0.001)
        assertEquals(16, offer.durationMinutes)
    }

    @Test
    fun woltPolishFullDeliveryCardIsParsed() {
        val text = """
            13,29 zł
            Spodziewany zarobek za pełną dostawę
            Dostawa od
            THAI SUN Express
            Odległość
            4.5 km
            Szacowany
            13 - 16 min
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.WOLT)

        assertNotNull(offer)
        assertEquals(13.29, offer!!.amountPln, 0.001)
        assertEquals(4.5, offer.distanceKm, 0.001)
        assertEquals(16, offer.durationMinutes)
    }

}

// 0.8.1 field-test regressions are kept in a separate class so the original
// test file remains easy to compare with previous releases.
class OfferParserFieldTest081 {
    @Test
    fun parsesUberPolishCardFromFieldTest() {
        val text = """
            Dostawa
            11,31 zł
            Łącznie 20 min (5.1 km)
            McDonald's Orunia Górna
            Białostocka & Warszawska, Gdańsk
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.UBER)
        assertNotNull(offer)
        assertEquals(11.31, offer!!.amountPln, 0.001)
        assertEquals(5.1, offer.distanceKm, 0.001)
        assertEquals(20, offer.durationMinutes)
    }

    @Test
    fun parsesWoltPolishCardFromFieldTest() {
        val text = """
            13.29 zł
            Spodziewany zarobek za pełną dostawę
            Dostawa od THAI SON Express
            Odległość
            4.5 km
            Szacowany
            13 - 16 min
            Akceptuj
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.WOLT)
        assertNotNull(offer)
        assertEquals(13.29, offer!!.amountPln, 0.001)
        assertEquals(4.5, offer.distanceKm, 0.001)
        assertEquals(16, offer.durationMinutes)
    }

    @Test
    fun parsesWoltLayoutWithoutPolishOrEnglishEarningsLabel() {
        val text = """
            13.29 zł
            Lieferverdienst
            THAI SON Express
            4.5 km
            13 - 16 min
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.WOLT)
        assertNotNull(offer)
        assertEquals(13.29, offer!!.amountPln, 0.001)
        assertEquals(4.5, offer.distanceKm, 0.001)
        assertEquals(16, offer.durationMinutes)
    }

    @Test
    fun parsesPyszneLiveOfferAndScheduleFromFieldTest() {
        val text = """
            36,69 zł
            12,2 km · 2 przystanki
            Odbiór: McDonald's Morena
            Jaśkowa Dolina 134, 80-286 Gdańsk
            Odbierz na 22:32
            Dostawa: Kryska
            Szara 5B/55, 80-116 Gdańsk
            Dostarcz na 22:43
            Zaakceptuj zlecenie 0:51
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)
        assertNotNull(offer)
        assertEquals(36.69, offer!!.amountPln, 0.001)
        assertEquals(12.2, offer.distanceKm, 0.001)
        assertNull(offer.durationMinutes)
        assertEquals(22 * 60 + 32, offer.pickupTimeMinutesOfDay)
        assertEquals(22 * 60 + 43, offer.deliveryTimeMinutesOfDay)
    }

    @Test
    fun parsesPyszneDeliveredOrderDetails() {
        val text = """
            Szczegóły zlecenia
            Suma przychodów
            11,00 zł
            7D983K
            Dostarczone
            Zlecenie przyjęte 23 sierpnia 2026 7:36 PM
            Zlecenie dostarczone 23 sierpnia 2026 7:46 PM
            Odbiór Altin Kebab (Warszawska 59/a)
            Czas aktywności 10 min 7 sec
            Szacowana odległość 2,1 km
            Szczegóły przychodów
            Stawka bazowa 10,00 zł
            Przyznany napiwek 1,00 zł
            Suma przychodów 11,00 zł
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)
        assertNotNull(offer)
        assertEquals(11.0, offer!!.amountPln, 0.001)
        assertEquals(2.1, offer.distanceKm, 0.001)
        assertEquals(607, offer.durationSeconds)
    }

    @Test
    fun parsesPyszneCancelledOrderDetailsWithZeroRevenue() {
        val text = """
            Szczegóły zlecenia
            Suma przychodów
            0,00 zł
            GMZ33C
            Anulowane
            Odbiór Rogi Smash (Sucha 18)
            Czas aktywności 26 min 9 sec
            Szacowana odległość 3,8 km
            Stawka bazowa 0,00 zł
        """.trimIndent()

        val offer = OfferParser.parse(text, CourierPlatform.PYSZNE)
        assertNotNull(offer)
        assertEquals(0.0, offer!!.amountPln, 0.001)
        assertEquals(3.8, offer.distanceKm, 0.001)
        assertEquals(1569, offer.durationSeconds)
    }
}
