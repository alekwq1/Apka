package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlacklistMatcherTest {
    @Test
    fun matchesStrictRestaurantNameIgnoringCasePunctuationAndDiacritics() {
        val hits = BlacklistMatcher.findHits(
            screenText = "Odbior: MCDONALD'S ORUNIA GORNA\nBialostocka & Warszawska, Gdansk",
            restaurantsRaw = "McDonald's Orunia Górna",
            customersRaw = ""
        )

        assertEquals("McDonald's Orunia Górna", hits.restaurant)
        assertNull(hits.customer)
    }

    @Test
    fun shortChainNameDoesNotMatchDifferentBranch() {
        val hits = BlacklistMatcher.findHits(
            screenText = "Odbiór: McDonald's Morena\nJaśkowa Dolina 134, Gdańsk",
            restaurantsRaw = "McDonald's",
            customersRaw = ""
        )

        assertNull(hits.restaurant)
    }

    @Test
    fun addressDistinguishesSameRestaurantName() {
        val wanted = BlacklistEntryCodec.serialize(
            listOf(BlacklistEntry("Altin Kebab", "Warszawska 59/a"))
        )
        val hits = BlacklistMatcher.findHits(
            screenText = "Odbiór\nAltin Kebab (Warszawska 59/a)\nGdańsk",
            restaurantsRaw = wanted,
            customersRaw = ""
        )
        assertEquals("Altin Kebab · Warszawska 59/a", hits.restaurant)

        val other = BlacklistMatcher.findHits(
            screenText = "Odbiór\nAltin Kebab (Sucha 18)\nGdańsk",
            restaurantsRaw = wanted,
            customersRaw = ""
        )
        assertNull(other.restaurant)
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
