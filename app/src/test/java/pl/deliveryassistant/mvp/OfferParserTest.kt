package pl.deliveryassistant.mvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OfferParserTest {
    @Test
    fun parsesPolishOffer() {
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
}
