package pl.fujara.app

object ProfitabilityCalculator {

    data class Rules(
        val vehicleCostPerKm: Double = 0.35,
        val minimumNetPerKm: Double = 2.50,
        val toleranceNetPerKm: Double = 0.50,
        val minimumNetPerHour: Double = 35.0,
        val toleranceNetPerHour: Double = 5.0,
        /** Realny zapas na parking, wejscie do lokalu, oczekiwanie i wydanie zamowienia. */
        val extraTimeMinutes: Int = 0
    )

    fun calculate(
        offer: Offer,
        rules: Rules,
        currentMinuteOfDay: Int,
        decisionBasis: DecisionBasis = DecisionBasis.MIXED,
        zusPercent: Double = 0.0
    ): Profitability {

        val resolvedDuration = resolveDuration(
            offer = offer,
            currentMinuteOfDay = currentMinuteOfDay
        )

        val vehicleCostPerKm =
            rules.vehicleCostPerKm.nonNegativeOr(DEFAULT_VEHICLE_COST)

        val minimumPerKm =
            rules.minimumNetPerKm.nonNegativeOr(DEFAULT_MIN_PER_KM)

        val tolerancePerKm =
            rules.toleranceNetPerKm.nonNegativeOr(DEFAULT_TOLERANCE_PER_KM)

        val minimumPerHour =
            rules.minimumNetPerHour.nonNegativeOr(DEFAULT_MIN_PER_HOUR)

        val tolerancePerHour =
            rules.toleranceNetPerHour.nonNegativeOr(DEFAULT_TOLERANCE_PER_HOUR)

        val extraTimeMinutes = rules.extraTimeMinutes.coerceIn(0, MAX_EXTRA_TIME_MINUTES)
        val durationMinutes = resolvedDuration.minutes?.let { base ->
            (base + extraTimeMinutes).coerceAtMost(MAX_TOTAL_DURATION_MINUTES)
        }

        val safeZusPercent = zusPercent
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
            ?: 0.0

        val afterZus = offer.amountPln * (1.0 - safeZusPercent / 100.0)

        /*
         * Realny wynik = kwota po ustawionym procencie ZUS - koszt kilometrow.
         * Przy ZUS = 0 zachowuje sie identycznie jak wersje 0.7.x.
         */
        val afterCosts = afterZus - offer.distanceKm * vehicleCostPerKm

        val perKm =
            if (offer.distanceKm > 0.0) {
                afterCosts / offer.distanceKm
            } else {
                null
            }

        val perHour = durationMinutes
            ?.takeIf { it > 0 }
            ?.let { minutes -> afterCosts / minutes * 60.0 }

        val status = resolveStatus(
            perKm = perKm,
            perHour = perHour,
            minimumPerKm = minimumPerKm,
            tolerancePerKm = tolerancePerKm,
            minimumPerHour = minimumPerHour,
            tolerancePerHour = tolerancePerHour,
            decisionBasis = decisionBasis
        )

        val profitable = when (status) {
            ProfitabilityStatus.PROFITABLE -> true
            ProfitabilityStatus.ALMOST_PROFITABLE,
            ProfitabilityStatus.UNPROFITABLE -> false
            ProfitabilityStatus.NO_TIME -> null
        }

        return Profitability(
            grossPln = offer.amountPln,
            afterZusPln = afterZus,
            netPln = afterCosts,
            distanceKm = offer.distanceKm,
            durationMinutes = durationMinutes,
            extraTimeMinutes = extraTimeMinutes,
            zusPercent = safeZusPercent,
            netPerKm = perKm,
            netPerHour = perHour,
            profitable = profitable,
            status = status,
            pickupTimeMinutesOfDay = offer.pickupTimeMinutesOfDay,
            deliveryTimeMinutesOfDay = offer.deliveryTimeMinutesOfDay,
            durationSource = resolvedDuration.source
        )
    }

    private fun resolveStatus(
        perKm: Double?,
        perHour: Double?,
        minimumPerKm: Double,
        tolerancePerKm: Double,
        minimumPerHour: Double,
        tolerancePerHour: Double,
        decisionBasis: DecisionBasis
    ): ProfitabilityStatus {
        val almostKmThreshold = (minimumPerKm - tolerancePerKm).coerceAtLeast(0.0)
        val almostHourThreshold = (minimumPerHour - tolerancePerHour).coerceAtLeast(0.0)

        fun statusForKm(): ProfitabilityStatus {
            val value = perKm?.takeIf { it.isFinite() } ?: return ProfitabilityStatus.NO_TIME
            return when {
                value >= minimumPerKm -> ProfitabilityStatus.PROFITABLE
                value >= almostKmThreshold -> ProfitabilityStatus.ALMOST_PROFITABLE
                else -> ProfitabilityStatus.UNPROFITABLE
            }
        }

        fun statusForHour(): ProfitabilityStatus {
            val value = perHour?.takeIf { it.isFinite() } ?: return ProfitabilityStatus.NO_TIME
            return when {
                value >= minimumPerHour -> ProfitabilityStatus.PROFITABLE
                value >= almostHourThreshold -> ProfitabilityStatus.ALMOST_PROFITABLE
                else -> ProfitabilityStatus.UNPROFITABLE
            }
        }

        return when (decisionBasis) {
            DecisionBasis.PER_KM -> statusForKm()
            DecisionBasis.HOURLY -> statusForHour()
            DecisionBasis.MIXED -> {
                val kmStatus = statusForKm()
                val hourStatus = statusForHour()
                if (kmStatus == ProfitabilityStatus.NO_TIME || hourStatus == ProfitabilityStatus.NO_TIME) {
                    ProfitabilityStatus.NO_TIME
                } else if (kmStatus == ProfitabilityStatus.UNPROFITABLE || hourStatus == ProfitabilityStatus.UNPROFITABLE) {
                    ProfitabilityStatus.UNPROFITABLE
                } else if (kmStatus == ProfitabilityStatus.ALMOST_PROFITABLE || hourStatus == ProfitabilityStatus.ALMOST_PROFITABLE) {
                    ProfitabilityStatus.ALMOST_PROFITABLE
                } else {
                    ProfitabilityStatus.PROFITABLE
                }
            }
        }
    }

    private fun resolveDuration(
        offer: Offer,
        currentMinuteOfDay: Int
    ): DurationResolution {
        offer.durationMinutes
            ?.takeIf { it in 1..MAX_REASONABLE_DURATION_MINUTES }
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

            if (minutes in 1..MAX_REASONABLE_DURATION_MINUTES) {
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

    private fun Double.nonNegativeOr(defaultValue: Double): Double =
        takeIf { it.isFinite() && it >= 0.0 } ?: defaultValue

    private fun Int.floorModDay(): Int =
        ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private data class DurationResolution(
        val minutes: Int?,
        val source: DurationSource
    )

    private const val MINUTES_PER_DAY = 24 * 60
    private const val MAX_REASONABLE_DURATION_MINUTES = 360
    private const val MAX_EXTRA_TIME_MINUTES = 120
    private const val MAX_TOTAL_DURATION_MINUTES = 480
    private const val DEFAULT_VEHICLE_COST = 0.35
    private const val DEFAULT_MIN_PER_KM = 2.50
    private const val DEFAULT_TOLERANCE_PER_KM = 0.50
    private const val DEFAULT_MIN_PER_HOUR = 35.0
    private const val DEFAULT_TOLERANCE_PER_HOUR = 5.0
}
