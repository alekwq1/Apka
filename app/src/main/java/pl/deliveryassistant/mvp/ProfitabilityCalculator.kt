package pl.deliveryassistant.mvp

object ProfitabilityCalculator {
    data class Rules(
        val vehicleCostPerKm: Double = 0.35,
        val minimumNetPerKm: Double = 2.50,
        val minimumNetPerHour: Double = 35.0
    )

    fun calculate(
        offer: Offer,
        rules: Rules,
        currentMinuteOfDay: Int
    ): Profitability {
        val duration = resolveDuration(offer, currentMinuteOfDay)

        // Progi nie maja prawa zatrzymac analizy oferty. Nawet gdy do kalkulatora
        // trafi nieprawidlowa wartosc, uzywamy bezpiecznego fallbacku.
        val vehicleCostPerKm = rules.vehicleCostPerKm.nonNegativeOr(0.35)
        val minimumPerKm = rules.minimumNetPerKm.nonNegativeOr(2.50)
        val minimumPerHour = rules.minimumNetPerHour.nonNegativeOr(35.0)

        val afterCosts = offer.amountPln - offer.distanceKm * vehicleCostPerKm
        val perKm = if (offer.distanceKm > 0.0) afterCosts / offer.distanceKm else null
        val perHour = duration.minutes?.takeIf { it > 0 }?.let { afterCosts / it * 60.0 }

        val profitable = if (perKm != null && perHour != null) {
            perKm >= minimumPerKm && perHour >= minimumPerHour
        } else {
            null
        }

        return Profitability(
            grossPln = offer.amountPln,
            netPln = afterCosts,
            distanceKm = offer.distanceKm,
            durationMinutes = duration.minutes,
            netPerKm = perKm,
            netPerHour = perHour,
            profitable = profitable,
            pickupTimeMinutesOfDay = offer.pickupTimeMinutesOfDay,
            deliveryTimeMinutesOfDay = offer.deliveryTimeMinutesOfDay,
            durationSource = duration.source
        )
    }

    private fun resolveDuration(
        offer: Offer,
        currentMinuteOfDay: Int
    ): DurationResolution {
        offer.durationMinutes
            ?.takeIf { it in 1..360 }
            ?.let {
                return DurationResolution(
                    minutes = it,
                    source = DurationSource.DIRECT_TOTAL
                )
            }

        offer.deliveryTimeMinutesOfDay?.let { plannedDelivery ->
            val minutes = minutesUntil(
                currentMinuteOfDay = currentMinuteOfDay,
                targetMinuteOfDay = plannedDelivery
            )

            if (minutes in 1..360) {
                return DurationResolution(
                    minutes = minutes,
                    source = DurationSource.PLANNED_DELIVERY
                )
            }
        }

        return DurationResolution(
            minutes = null,
            source = DurationSource.UNKNOWN
        )
    }

    internal fun minutesUntil(
        currentMinuteOfDay: Int,
        targetMinuteOfDay: Int
    ): Int {
        val now = currentMinuteOfDay.floorModDay()
        val target = targetMinuteOfDay.floorModDay()
        val delta = (target - now + MINUTES_PER_DAY) % MINUTES_PER_DAY

        // Gdy termin wypada dokladnie teraz, przyjmujemy 1 minute,
        // zeby nie dzielic przez zero przy stawce godzinowej.
        return if (delta == 0) 1 else delta
    }

    private fun Double.nonNegativeOr(defaultValue: Double): Double =
        takeIf { it.isFinite() && it >= 0.0 } ?: defaultValue

    private fun Int.floorModDay(): Int =
        ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private data class DurationResolution(
        val minutes: Int?,
        val source: DurationSource
    )

    private const val MINUTES_PER_DAY = 24 * 60
}
