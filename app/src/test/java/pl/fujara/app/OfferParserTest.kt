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

}