package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlacklistMatcherTest {
    @Test
    fun matchesRestaurantIgnoringCasePunctuationAndDiacritics() {
        val hits = BlacklistMatcher.findHits(
            screenText = "Odbior: MCDONALD'S ORUNIA GORNA, Gdansk",
            restaurantsRaw = "McDonald's Orunia Górna\nRestauracja XYZ",
            customersRaw = ""
        )

        assertEquals("McDonald's Orunia Górna", hits.restaurant)
        assertNull(hits.customer)
    }

    @Test
    fun matchesRestaurantAndCustomerSeparately() {
        val hits = BlacklistMatcher.findHits(
            screenText = "Odbior: Sushi Świętokrzyska\nDostawa: Miroslaw Borowiecki",
            restaurantsRaw = "Sushi Swietokrzyska",
            customersRaw = "Mirosław Borowiecki"
        )

        assertEquals("Sushi Swietokrzyska", hits.restaurant)
        assertEquals("Mirosław Borowiecki", hits.customer)
    }
}
