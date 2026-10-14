package pl.fujara.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Transcription of the supplied 13 October screenshot, not a live device capture. */
class PyszneOctoberRegressionTest {
    private val screen = """
        Historia przychodów
        13 października
        2026
        Przychody
        290,94 zł
        10 przyjętych zleceń
        Szczegóły przychodów
        13 października 2026
        Stawka bazowa
        278,94 zł
        Dodatkowe korzyści
        0,00 zł
        Przyznany napiwek
        12,00 zł
        Inne
        0,00 zł
        Suma przychodów
        290,94 zł
        Podsumowanie dnia
        10 przyjętych zleceń
        Mobile Pizza Italiana (Władysława Cieszyńskiego 1)
        46,00 zł
        CD3KT7
        McDonald's Gdańsk, Świętokrzyska (Świętokrzyska 2)
        21,56 zł
        43PF7J
    """.trimIndent()

    @Test fun readsOctoberScreenshot() {
        val result = PyszneDayReferenceParser.parse(screen)!!
        assertEquals(LocalDate.of(2026, 10, 13), result.date)
        assertEquals(10, result.orderCount)
        assertEquals(290.94, result.amountPln, 0.001)
        assertEquals(listOf("CD3KT7", "43PF7J"), result.orderIds)
    }
    @Test fun supportsOldAndNewCountLabels() {
        for (label in listOf("10 zleceń", "10 przyjetych zlecen", "10 zaakceptowanych zleceń", "10 accepted orders", "10 offers accepted")) {
            assertEquals(label, 10, PyszneDayReferenceParser.parse(screen.replace("10 przyjętych zleceń", label))!!.orderCount)
        }
    }
    @Test fun readsSplitCountAndCurrencyNodes() {
        val result = PyszneDayReferenceParser.parse(screen.replace("10 przyjętych zleceń", "10\nprzyjętych\nzleceń").replace("290,94 zł", "290,94\nzł"))!!
        assertEquals(290.94, result.amountPln, 0.001)
    }
    @Test fun readsNonBreakingSpaces() {
        assertNotNull(PyszneDayReferenceParser.parse(screen.replace(' ', '\u00a0')))
        assertNotNull(PyszneDayReferenceParser.parse(screen.replace(' ', '\u202f')))
    }
    @Test fun readsGroupedThousands() {
        for (amount in listOf("1 290,94 zł", "1.290,94 zł", "1\u202f290,94 zł", "1290.94 PLN")) {
            assertEquals(amount, 1290.94, PyszneDayReferenceParser.parse(screen.replace("290,94 zł", amount))!!.amountPln, 0.001)
        }
    }
    @Test fun readsBareAllLetterIdsFromList() {
        val ids = PyszneDayReferenceParser.parseOrderIds("""
            Historia przychodów
            Pizzeria u Skrzypka (Jaśkowa Dolina 130c)
            26,59 zł
            CPPXDD
            Banditos (Lisia Grobla 7 loc C6)
            30,65 zł
            DBXRJ7
            Ristorante I Pazzi
            33,75 zł
            CBYWRH
        """.trimIndent())
        assertEquals(listOf("CPPXDD", "DBXRJ7", "CBYWRH"), ids)
    }
    @Test fun doesNotReadOrdinaryWordsAsIds() {
        assertEquals(emptyList<String>(), PyszneDayReferenceParser.parseOrderIds("Przychody\n290,94 zł\nFUJARA\nPyszne\norders\nINCOME\nORDERS"))
    }
    @Test fun deduplicatesPrefixedAndBareIds() {
        assertEquals(listOf("CD3KT7"), PyszneDayReferenceParser.parseOrderIds("#CD3KT7\n46,00 zł\nCD3KT7"))
    }
    @Test fun refusesWeekEvenWithLeakedDayMarker() {
        val week = """
            Historia przychodów
            12 pa - 19 października 2026
            Przychody w tym tygodniu
            547,54 zł
            19 zleceń
            Suma przychodów
            547,54 zł
            Podsumowanie tygodnia
            poniedziałek, 12 października
            9 zleceń
            256,60 zł
            wtorek, 13 października
            10 zleceń
            290,94 zł
            Podsumowanie dnia
        """.trimIndent()
        assertTrue(PyszneDayReferenceParser.isWeeklySummary(week))
        assertNull(PyszneDayReferenceParser.parse(week))
    }
    @Test fun refusesConflictingAmounts() {
        assertNull(PyszneDayReferenceParser.parse(screen.replace("Suma przychodów\n290,94", "Suma przychodów\n291,94")))
    }
    @Test fun refusesConflictingCounts() {
        assertNull(PyszneDayReferenceParser.parse(screen.replace("Podsumowanie dnia\n10", "Podsumowanie dnia\n11")))
    }
    @Test fun refusesDuplicateConflictingCards() {
        assertNull(PyszneDayReferenceParser.parse(screen + "\nPrzychody\n400,00 zł\n10 przyjętych zleceń"))
    }
    @Test fun refusesMissingDate() {
        assertNull(PyszneDayReferenceParser.parse(screen.replace("13 października\n2026", "").replace("13 października 2026", "")))
    }
    @Test fun refusesDifferentDates() {
        assertNull(PyszneDayReferenceParser.parse(screen + "\n14 października 2026"))
    }
    @Test fun refusesInvalidDate() {
        assertNull(PyszneDayReferenceParser.parse(screen.replace("13 października", "32 października")))
    }
    @Test fun doesNotTakeFirstRestaurantAsTotal() {
        val text = "13 października 2026\nPrzychody\nSuma przychodów\nPodsumowanie dnia\n10 przyjętych zleceń\nMobile Pizza\n46,00 zł\nCD3KT7"
        assertNull(PyszneDayReferenceParser.parse(text))
    }
    @Test fun acceptsTotalWhenTopCardIsOffscreen() {
        val text = screen.substring(screen.indexOf("Szczegóły przychodów"))
        assertEquals(290.94, PyszneDayReferenceParser.parse(text)!!.amountPln, 0.001)
    }
    @Test fun rejectsBottomListWithoutDayHeader() {
        assertNull(PyszneDayReferenceParser.parse("Historia przychodów\nBanditos\n30,65 zł\nDBXRJ7"))
    }
}
