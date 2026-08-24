package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PyszneHistoryParserTest {

    @Test
    fun parsesPolishPyszneHistoryDetailsAndCreatesStableKey() {
        val text = """
            Szczegóły zlecenia
            Suma przychodów
            20,97 zł
            XX96G4
            Dostarczone
            Zlecenie przyjęte
            20 sierpnia 2026 5:31 PM
            Zlecenie dostarczone
            20 sierpnia 2026 5:56 PM
            Odbiór
            Faloviec (Obrońców Wybrzeża 2)
            Czas aktywności
            24 min 52 sec
            Szacowana odległość
            7,1 km
            Szczegóły przychodów
            Stawka bazowa
            20,97 zł
            Suma przychodów
            20,97 zł
        """.trimIndent()

        val offer = Offer(
            amountPln = 20.97,
            distanceKm = 7.1,
            durationSeconds = 24 * 60 + 52,
            applyExtraTimeBuffer = false
        )

        val first = PyszneHistoryParser.parse(text, offer)
        val second = PyszneHistoryParser.parse(text, offer)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(LocalDate.of(2026, 8, 20), first!!.date)
        assertEquals(17 * 60 + 31, first.acceptedMinuteOfDay)
        assertEquals("Faloviec (Obrońców Wybrzeża 2)", first.restaurant)
        assertEquals(20.97, first.amountPln, 0.001)
        assertEquals(7.1, first.distanceKm, 0.001)
        assertEquals(1492, first.durationSeconds)
        assertFalse(first.cancelled)
        assertEquals(first.key, second!!.key)
        assertTrue(first.key.length >= 16)
    }

    @Test
    fun ignoresMoneyBetweenPickupLabelAndRestaurant() {
        val text = """
            Szczegóły zlecenia
            XX96G4
            Dostarczone
            Zlecenie przyjęte
            20 sierpnia 2026 5:31 PM
            Zlecenie dostarczone
            20 sierpnia 2026 5:56 PM
            Odbiór
            24,51 zł
            Lekko Good Food & Friends (Aleja Zwycięstwa 15)
            Czas aktywności
            24 min 52 sec
            Szacowana odległość
            7,1 km
            Szczegóły przychodów
            Suma przychodów
            20,97 zł
        """.trimIndent()
        val offer = Offer(20.97, 7.1, durationSeconds = 1492, applyExtraTimeBuffer = false)

        val parsed = PyszneHistoryParser.parse(text, offer)!!

        assertEquals("Lekko Good Food & Friends (Aleja Zwycięstwa 15)", parsed.restaurant)
    }

    @Test
    fun parsesCancelledPyszneHistoryEntry() {
        val text = """
            Szczegóły zlecenia
            GMZ33C
            Anulowane
            Zlecenie przyjęte
            20 sierpnia 2026 8:57 PM
            Zlecenie anulowane
            20 sierpnia 2026 9:23 PM
            Odbiór
            Rogi Smash (Sucha 18)
            Czas aktywności
            26 min 9 sec
            Szacowana odległość
            3,8 km
            Szczegóły przychodów
            Stawka bazowa
            0,00 zł
            Suma przychodów
            0,00 zł
        """.trimIndent()
        val offer = Offer(0.0, 3.8, durationSeconds = 26 * 60 + 9, applyExtraTimeBuffer = false)

        val parsed = PyszneHistoryParser.parse(text, offer)

        assertNotNull(parsed)
        assertTrue(parsed!!.cancelled)
        assertEquals("Rogi Smash (Sucha 18)", parsed.restaurant)
        assertEquals(0.0, parsed.amountPln, 0.001)
    }

    @Test
    fun doesNotOfferSaveOnLivePyszneOffer() {
        val text = """
            Pyszne.pl
            21,00 zł
            7,1 km
            25 min
            Faloviec
        """.trimIndent()
        val offer = Offer(21.0, 7.1, durationSeconds = 1500, applyExtraTimeBuffer = false)

        assertNull(PyszneHistoryParser.parse(text, offer))
    }

    @Test
    fun fallbackIdentityDistinguishesOrdersByAcceptedTime() {
        val base = """
            Szczegóły zlecenia
            Zlecenie przyjęte
            20 sierpnia 2026 %s
            Zlecenie dostarczone
            20 sierpnia 2026 3:00 PM
            Odbiór
            Lekko Good Food & Friends (Aleja Zwycięstwa 15)
            Czas aktywności
            10 min 0 sec
            Szacowana odległość
            3,0 km
            Suma przychodów
            15,00 zł
        """.trimIndent()
        val offer = Offer(15.0, 3.0, durationSeconds = 600, applyExtraTimeBuffer = false)

        val one = PyszneHistoryParser.parse(base.format("1:10 PM"), offer)!!
        val two = PyszneHistoryParser.parse(base.format("2:10 PM"), offer)!!

        assertTrue(one.key != two.key)
    }
}

class PyszneDayReferenceParserTest {
    @Test
    fun parsesPyszneDailySummaryControlValuesFromSameHeader() {
        val text = """
            20 sierpnia 2026
            Przychody
            211,85 zł
            10 offers accepted
            Szczegóły przychodów
            Stawka bazowa
            211,85 zł
            Suma przychodów
            211,85 zł
            Podsumowanie dnia
            10 offers accepted
            Faloviec (Obrońców Wybrzeża 2)
            20,97 zł
            Lekko Good Food & Friends
            24,51 zł
        """.trimIndent()

        val reference = PyszneDayReferenceParser.parse(text)

        assertNotNull(reference)
        assertEquals(LocalDate.of(2026, 8, 20), reference!!.date)
        assertEquals(10, reference.orderCount)
        assertEquals(211.85, reference.amountPln, 0.001)
    }

    @Test
    fun refusesSummaryWithoutExplicitDate() {
        val text = """
            Przychody
            621,95 zł
            32 offers accepted
            Podsumowanie dnia
            32 offers accepted
        """.trimIndent()

        assertNull(PyszneDayReferenceParser.parse(text))
    }

    @Test
    fun refusesMixedDatesInsteadOfCombiningCountAndAmount() {
        val text = """
            20 sierpnia 2026
            Przychody
            211,85 zł
            10 offers accepted
            21 sierpnia 2026
            Przychody
            621,95 zł
            32 offers accepted
            Podsumowanie dnia
        """.trimIndent()

        assertNull(PyszneDayReferenceParser.parse(text))
    }
}
