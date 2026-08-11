package pl.deliveryassistant.mvp

data class Offer(
    val amountPln: Double,
    val distanceKm: Double,
    val durationMinutes: Int? = null,
    val pickupTimeMinutesOfDay: Int? = null,
    val deliveryTimeMinutesOfDay: Int? = null
)

enum class DurationSource {
    DIRECT_TOTAL,
    PLANNED_DELIVERY,
    UNKNOWN
}

enum class ProfitabilityStatus {
    PROFITABLE,
    ALMOST_PROFITABLE,
    UNPROFITABLE,
    NO_TIME
}

data class Profitability(
    val grossPln: Double,
    val netPln: Double,
    val distanceKm: Double,
    val durationMinutes: Int?,
    val netPerKm: Double?,
    val netPerHour: Double?,
    val profitable: Boolean?,
    val status: ProfitabilityStatus,
    val pickupTimeMinutesOfDay: Int?,
    val deliveryTimeMinutesOfDay: Int?,
    val durationSource: DurationSource
)
