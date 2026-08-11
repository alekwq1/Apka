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