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
        assertEquals("XX96G4", first.orderId)
        assertEquals(first.key, PyszneHistoryParser.orderKeyForId("XX96G4"))
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

    @Test
    fun acceptsOrderDetailsThatCrossMidnightAndUsesAcceptedDate() {
        val text = """
            Szczegóły zlecenia
            Suma przychodów
            21,87 zł
            MP63FY
            Dostarczone
            Zlecenie przyjęte
            21 sierpnia 2026
            11:34 PM
            Zlecenie dostarczone
            22 sierpnia 2026
            12:08 AM
            Odbiór
            McDonald's Gdańsk, Uczniowska (Uczniowska 30A)
            Czas aktywności
            34 min 5 sec
            Szacowana odległość
            7,1 km
            Szczegóły przychodów
            Suma przychodów
            21,87 zł
        """.trimIndent()
        val offer = Offer(21.87, 7.1, durationSeconds = 34 * 60 + 5, applyExtraTimeBuffer = false)

        assertTrue(PyszneHistoryParser.hasCoherentOrderDetailDates(text))
        val parsed = PyszneHistoryParser.parse(text, offer)

        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 8, 21), parsed!!.date)
        assertEquals("MP63FY", parsed.orderId)
        assertEquals(23 * 60 + 34, parsed.acceptedMinuteOfDay)
    }

    @Test
    fun rejectsOrderDetailsWithDatesMoreThanOneDayApart() {
        val text = """
            Szczegóły zlecenia
            Zlecenie przyjęte
            20 sierpnia 2026 11:34 PM
            Zlecenie dostarczone
            22 sierpnia 2026 12:08 AM
        """.trimIndent()

        assertFalse(PyszneHistoryParser.hasCoherentOrderDetailDates(text))
    }
}

class PyszneWorkDayResolverTest {
    @Test
    fun mapsAfterMidnightOrderToPreviousPyszneDayWhenIdIsOnThatList() {
        val entry = PyszneDeliveryLog(
            key = "key",
            fingerprint = "fingerprint",
            orderId = "MHPYBR",
            date = LocalDate.of(2026, 8, 22),
            acceptedMinuteOfDay = 9,
            restaurant = "Kebab King",
            amountPln = 0.0,
            distanceKm = 8.3,
            durationSeconds = 23 * 60 + 3,
            cancelled = true
        )
        val previousDay = PyszneDayReference(
            date = LocalDate.of(2026, 8, 21),
            orderCount = 32,
            amountPln = 621.95,
            orderIds = listOf("MP63FY", "MHPYBR")
        )

        assertEquals(
            LocalDate.of(2026, 8, 21),
            PyszneWorkDayResolver.resolveDate(entry, listOf(previousDay))
        )
    }

    @Test
    fun doesNotMoveOrderWhenIdIsNotOnPreviousDayList() {
        val entry = PyszneDeliveryLog(
            key = "key",
            fingerprint = "fingerprint",
            orderId = "MHPYBR",
            date = LocalDate.of(2026, 8, 22),
            acceptedMinuteOfDay = 9,
            restaurant = "Kebab King",
            amountPln = 0.0,
            distanceKm = 8.3,
            durationSeconds = 23 * 60 + 3,
            cancelled = true
        )
        val previousDay = PyszneDayReference(
            date = LocalDate.of(2026, 8, 21),
            orderCount = 32,
            amountPln = 621.95,
            orderIds = listOf("MP63FY")
        )

        assertNull(PyszneWorkDayResolver.resolveDate(entry, listOf(previousDay)))
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
            #XX96G4
            Lekko Good Food & Friends
            24,51 zł
            #CWPRCG
        """.trimIndent()

        val reference = PyszneDayReferenceParser.parse(text)

        assertNotNull(reference)
        assertEquals(LocalDate.of(2026, 8, 20), reference!!.date)
        assertEquals(10, reference.orderCount)
        assertEquals(211.85, reference.amountPln, 0.001)
        assertEquals(listOf("XX96G4", "CWPRCG"), reference.orderIds)
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


class PyszneDaySummaryCalculatorTest {
    @Test
    fun calculatesHourlyRateForDayLongerThanSixHours() {
        val date = LocalDate.of(2026, 8, 23)
        val totalSeconds = 11 * 3600 + 47 * 60
        val entries = List(30) { index ->
            PyszneDeliveryLog(
                key = "key-$index",
                fingerprint = "fp-$index",
                orderId = "A${index.toString().padStart(5, '0')}",
                date = date,
                acceptedMinuteOfDay = null,
                restaurant = "Test",
                amountPln = 590.67 / 30.0,
                distanceKm = 183.8 / 30.0,
                durationSeconds = totalSeconds / 30
            )
        }

        val summary = PyszneDaySummaryCalculator.calculate(
            date = date,
            entries = entries,
            rules = ProfitabilityCalculator.Rules(vehicleCostPerKm = 0.35),
            decisionBasis = DecisionBasis.MIXED,
            zusPercent = 0.0
        )

        assertNotNull(summary.netPerHour)
        assertTrue(summary.netPerHour!! > 40.0)
        assertTrue(summary.netPerHour!! < 50.0)
    }

    @Test
    fun keepsTwoOrdersFromSameRestaurantAsSeparateRows() {
        val date = LocalDate.of(2026, 8, 24)
        val entries = listOf(
            PyszneDeliveryLog(
                key = "first",
                fingerprint = "fp-first",
                orderId = "ABC123",
                date = date,
                acceptedMinuteOfDay = 12 * 60,
                restaurant = "Lena Grill Kebab",
                amountPln = 20.0,
                distanceKm = 4.0,
                durationSeconds = 20 * 60
            ),
            PyszneDeliveryLog(
                key = "second",
                fingerprint = "fp-second",
                orderId = "DEF456",
                date = date,
                acceptedMinuteOfDay = 13 * 60,
                restaurant = "Lena Grill Kebab",
                amountPln = 15.0,
                distanceKm = 7.0,
                durationSeconds = 35 * 60
            )
        )

        val summary = PyszneDaySummaryCalculator.calculate(
            date = date,
            entries = entries,
            rules = ProfitabilityCalculator.Rules(vehicleCostPerKm = 0.35),
            decisionBasis = DecisionBasis.MIXED,
            zusPercent = 0.0
        )

        assertEquals(2, summary.orderCount)
        assertEquals(2, summary.restaurants.size)
        assertEquals(setOf("ABC123", "DEF456"), summary.restaurants.mapNotNull { it.orderId }.toSet())
        assertTrue(summary.restaurants.all { it.orderCount == 1 })
    }

    @Test
    fun cashTipsAndExtraPauseAffectOnlyDayTotals() {
        val date = LocalDate.of(2026, 8, 24)
        val entry = PyszneDeliveryLog(
            key = "one",
            fingerprint = "fp-one",
            orderId = "ABC123",
            date = date,
            acceptedMinuteOfDay = 12 * 60,
            restaurant = "Test",
            amountPln = 20.0,
            distanceKm = 4.0,
            durationSeconds = 20 * 60
        )

        val summary = PyszneDaySummaryCalculator.calculate(
            date = date,
            entries = listOf(entry),
            rules = ProfitabilityCalculator.Rules(vehicleCostPerKm = 0.35),
            decisionBasis = DecisionBasis.MIXED,
            zusPercent = 0.0,
            cashTipsPln = 5.0,
            extraPauseMinutes = 10
        )

        assertEquals(25.0, summary.grossPln, 0.001)
        assertEquals(30 * 60, summary.durationSeconds)
        assertEquals(5.0, summary.cashTipsPln, 0.001)
        assertEquals(10, summary.extraPauseMinutes)
        assertEquals(20.0, summary.restaurants.single().grossPln, 0.001)
        assertEquals(20 * 60, summary.restaurants.single().durationSeconds)
    }
}
