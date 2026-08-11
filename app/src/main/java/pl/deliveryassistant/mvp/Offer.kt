package pl.deliveryassistant.mvp

data class Offer(
    val amountPln: Double,
    val distanceKm: Double,
    val durationMinutes: Int?
)

data class Profitability(
    val grossPln: Double,
    val netPln: Double,
    val distanceKm: Double,
    val durationMinutes: Int,
    val netPerKm: Double,
    val netPerHour: Double,
    val profitable: Boolean
)
