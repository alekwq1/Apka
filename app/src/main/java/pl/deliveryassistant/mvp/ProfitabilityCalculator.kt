package pl.deliveryassistant.mvp

object ProfitabilityCalculator {
    data class Rules(
        val vehicleCostPerKm: Double = 0.35,
        val minimumNetPerKm: Double = 2.50,
        val toleranceNetPerKm: Double = 0.50,
        val minimumNetPerHour: Double = 35.0,
        val toleranceNetPerHour: Double = 5.0
    )

    fun calculate(
        offer: Offer,
        rules: Rules,
        currentMinuteOfDay: Int
    ): Profitability {
        val duration = resolveDuration(offer, currentMinuteOfDay)
        val net = offer.amountPln - offer.distanceKm * rules.vehicleCostPerKm
        val perKm = if (offer.distanceKm > 0.0) net / offer.distanceKm else null
        val perHour = duration.minutes?.takeIf { it > 0 }?.let { net / it * 60.0 }

        val status = resolveStatus(
            perKm = perKm,
            perHour = perHour,
            rules = rules
        )

        val profitable = when (status) {
            ProfitabilityStatus.PROFITABLE -> true
            ProfitabilityStatus.ALMOST_PROFITABLE,
            ProfitabilityStatus.UNPROFITABLE -> false
            ProfitabilityStatus.NO_TIME -> null
        }

        return Profitability(
            grossPln = offer.amountPln,
            netPln = net,
            distanceKm = offer.distanceKm,
            durationMinutes = duration.minutes,
            netPerKm = perKm,
            netPerHour = perHour,
            profitable = profitable,
            status = status,
            pickupTimeMinutesOfDay = offer.pickupTimeMinutesOfDay,
            deliveryTimeMinutesOfDay = offer.deliveryTimeMinutesOfDay,
            durationSource = duration.source
        )
    }

    private fun resolveStatus(
        perKm: Double?,
        perHour: Double?,
        rules: Rules
    ): ProfitabilityStatus {
        if (perKm == null || perHour == null) {
            return ProfitabilityStatus.NO_TIME
        }

        val minimumKm = rules.minimumNetPerKm.coerceAtLeast(0.0)
        val minimumHour = rules.minimumNetPerHour.coerceAtLeast(0.0)
        val toleranceKm = rules.toleranceNetPerKm.coerceAtLeast(0.0)
        val toleranceHour = rules.toleranceNetPerHour.coerceAtLeast(0.0)

        if (
            perKm >= minimumKm &&
            perHour >= minimumHour
        ) {
            return ProfitabilityStatus.PROFITABLE
        }

        val almostKmThreshold = (minimumKm - toleranceKm).coerceAtLeast(0.0)
        val almostHourThreshold = (minimumHour - toleranceHour).coerceAtLeast(0.0)

        if (
            perKm >= almostKmThreshold &&
            perHour >= almostHourThreshold
        ) {
            return ProfitabilityStatus.ALMOST_PROFITABLE
        }

        return ProfitabilityStatus.UNPROFITABLE
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

        return if (delta == 0) 1 else delta
    }

    private fun Int.floorModDay(): Int =
        ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private data class DurationResolution(
        val minutes: Int?,
        val source: DurationSource
    )

    private const val MINUTES_PER_DAY = 24 * 60
}
