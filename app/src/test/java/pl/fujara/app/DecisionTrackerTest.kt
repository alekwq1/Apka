package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTrackerTest {

    private fun result(offer: Offer, status: ProfitabilityStatus = ProfitabilityStatus.PROFITABLE) =
        ProfitabilityCalculator.calculate(
            offer = offer,
            rules = ProfitabilityCalculator.Rules(),
            currentMinuteOfDay = 14 * 60,
            decisionBasis = DecisionBasis.MIXED
        ).let { if (it.status == status) it else it.copy(status = status) }

    private val woltOffer = Offer(amountPln = 16.85, distanceKm = 2.2, durationMinutes = 15)
    private val pyszneOffer = Offer(amountPln = 10.0, distanceKm = 1.8, pickupTimeMinutesOfDay = 14 * 60 + 12, deliveryTimeMinutesOfDay = 14 * 60 + 16)

    @Test
    fun `same card scanned repeatedly creates one pending offer`() {
        val tracker = DecisionTracker()
        val a = tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 1_000L)
        val b = tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 2_200L)
        assertEquals(a.id, b.id)
        assertTrue(tracker.drainFinished().isEmpty())
    }

    @Test
    fun `click on Akceptuj finalizes as accepted`() {
        val tracker = DecisionTracker()
        tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 1_000L)
        assertEquals(OfferDecision.ACCEPTED, tracker.buttonClicked("Akceptuj", 4_000L))
        val finished = tracker.drainFinished()
        assertEquals(1, finished.size)
        assertEquals(OfferDecision.ACCEPTED, finished.single().decision)
        assertEquals(DecisionSource.CLICK, finished.single().decisionSource)
        assertEquals(3, finished.single().decisionLatencySeconds)
        assertNull(tracker.current)
    }

    @Test
    fun `click on Odrzuc finalizes as rejected`() {
        val tracker = DecisionTracker()
        tracker.offerVisible(CourierPlatform.PYSZNE, pyszneOffer, result(pyszneOffer), 1_000L)
        assertEquals(OfferDecision.REJECTED, tracker.buttonClicked("X Odrzuć", 2_000L))
        assertEquals(OfferDecision.REJECTED, tracker.drainFinished().single().decision)
    }

    @Test
    fun `unrelated click is ignored`() {
        val tracker = DecisionTracker()
        tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 1_000L)
        assertNull(tracker.buttonClicked("Nawiguj", 2_000L))
        assertNotNull(tracker.current)
    }

    @Test
    fun `active delivery screen after vanish means accepted`() {
        val tracker = DecisionTracker()
        tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 1_000L)
        tracker.offerNotVisible(3_000L)
        tracker.activeDeliveryScreenSeen(CourierPlatform.WOLT, 6_000L)
        val event = tracker.drainFinished().single()
        assertEquals(OfferDecision.ACCEPTED, event.decision)
        assertEquals(DecisionSource.NEXT_SCREEN, event.decisionSource)
    }

    @Test
    fun `offer that vanishes without any signal expires after timeout`() {
        val tracker = DecisionTracker(expireAfterMs = 10_000L)
        tracker.offerVisible(CourierPlatform.STUART, woltOffer, result(woltOffer), 1_000L)
        tracker.offerNotVisible(5_000L)
        tracker.tick(9_000L)
        assertTrue(tracker.drainFinished().isEmpty())
        tracker.tick(16_000L)
        val event = tracker.drainFinished().single()
        assertEquals(OfferDecision.EXPIRED, event.decision)
        assertEquals(DecisionSource.TIMEOUT, event.decisionSource)
    }

    @Test
    fun `new offer closes previous undecided one`() {
        val tracker = DecisionTracker()
        tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 1_000L)
        tracker.offerNotVisible(4_000L)
        tracker.offerVisible(CourierPlatform.WOLT, woltOffer.copy(amountPln = 22.23, distanceKm = 5.5), result(woltOffer), 8_000L)
        val finished = tracker.drainFinished()
        assertEquals(1, finished.size)
        assertEquals(16.85, finished.single().amountPln, 0.001)
        assertNotNull(tracker.current)
    }

    @Test
    fun `voice rejection is recorded with voice source`() {
        val tracker = DecisionTracker()
        tracker.offerVisible(CourierPlatform.WOLT, woltOffer, result(woltOffer), 1_000L)
        tracker.rejectedByVoice(3_000L)
        val event = tracker.drainFinished().single()
        assertEquals(OfferDecision.REJECTED, event.decision)
        assertEquals(DecisionSource.VOICE, event.decisionSource)
    }

    @Test
    fun `decision button classifier`() {
        assertEquals(OfferDecision.ACCEPTED, DecisionButtons.classify("Zaakceptuj zlecenie 0:51"))
        assertEquals(OfferDecision.ACCEPTED, DecisionButtons.classify("Jestem zainteresowany"))
        assertEquals(OfferDecision.REJECTED, DecisionButtons.classify("Odrzuć"))
        assertEquals(OfferDecision.REJECTED, DecisionButtons.classify("Decline"))
        assertNull(DecisionButtons.classify("Spodziewany zarobek za pełną dostawę 16,85 zł Akceptuj teraz albo nie, to długi opis bez sensu i limitu"))
        assertNull(DecisionButtons.classify("Nawiguj"))
    }

    @Test
    fun `analytics aggregates accepted and rejected`() {
        val base = 1_700_000_000_000L
        fun event(decision: OfferDecision, status: ProfitabilityStatus, perHour: Double, net: Double, platform: CourierPlatform = CourierPlatform.WOLT) =
            OfferEvent(
                id = "$decision-$perHour-$net", platform = platform, shownAtMillis = base, decidedAtMillis = base + 4_000L,
                decision = decision, decisionSource = DecisionSource.CLICK, amountPln = net + 1, netPln = net,
                distanceKm = 3.0, durationMinutes = 10, netPerHour = perHour, netPerKm = net / 3.0, status = status, restaurant = "KFC"
            )
        val events = listOf(
            event(OfferDecision.ACCEPTED, ProfitabilityStatus.PROFITABLE, 60.0, 15.0),
            event(OfferDecision.ACCEPTED, ProfitabilityStatus.UNPROFITABLE, 20.0, 6.0),
            event(OfferDecision.REJECTED, ProfitabilityStatus.PROFITABLE, 55.0, 14.0),
            event(OfferDecision.EXPIRED, ProfitabilityStatus.UNPROFITABLE, 18.0, 5.0, CourierPlatform.STUART),
            event(OfferDecision.UNKNOWN, ProfitabilityStatus.NO_TIME, 0.0, 0.0)
        )
        val stats = DecisionAnalytics.stats(events)
        assertEquals(5, stats.shown)
        assertEquals(2, stats.accepted)
        assertEquals(1, stats.rejected)
        assertEquals(1, stats.expired)
        assertEquals(50.0, stats.acceptanceRatePercent!!, 0.01)
        assertEquals(40.0, stats.avgNetPerHourAccepted!!, 0.01)
        assertEquals(1, stats.rejectedGoodCount)
        assertEquals(1, stats.acceptedPoorCount)
        assertEquals(14.0, stats.rejectedGoodNetTotal, 0.01)
        assertEquals(21.0, stats.acceptedNetTotal, 0.01)
        assertEquals(4, stats.medianDecisionSeconds)
        val platforms = DecisionAnalytics.byPlatform(events)
        assertEquals(CourierPlatform.WOLT, platforms.first().platform)
        assertEquals(4, platforms.first().stats.shown)
        val restaurants = DecisionAnalytics.byRestaurant(events)
        assertEquals("KFC", restaurants.single().restaurant)
        assertEquals(5, restaurants.single().shown)
    }
}
