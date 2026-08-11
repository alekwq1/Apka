package pl.deliveryassistant.mvp

object ProfitabilityCalculator {
    data class Rules(
        val vehicleCostPerKm: Double = 0.35,
        val minimumNetPerKm: Double = 2.50,
        val minimumNetPerHour: Double = 35.0,
        val fallbackMinutes: Int = 15
    )

    fun calculate(offer: Offer, rules: Rules): Profitability {
        val minutes = (offer.durationMinutes ?: rules.fallbackMinutes).coerceAtLeast(1)
        val net = (offer.amountPln - offer.distanceKm * rules.vehicleCostPerKm).coerceAtLeast(0.0)
        val perKm = net / offer.distanceKm
        val perHour = net / minutes * 60.0
        val profitable = perKm >= rules.minimumNetPerKm && perHour >= rules.minimumNetPerHour

        return Profitability(
            grossPln = offer.amountPln,
            netPln = net,
            distanceKm = offer.distanceKm,
            durationMinutes = minutes,
            netPerKm = perKm,
            netPerHour = perHour,
            profitable = profitable
        )
    }
}
