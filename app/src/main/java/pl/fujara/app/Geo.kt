package pl.fujara.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

data class RouteLeg(
    val distanceKm: Double,
    val minutes: Int,
    /** Opoznienie z powodu ruchu (tylko dostawcy z danymi o ruchu, np. TomTom). */
    val trafficDelayMinutes: Int?,
    val provider: String
)

enum class RouteProfile(val key: String, val osrm: String, val tomtom: String, val avgKmh: Double) {
    CAR("car", "driving", "car", 28.0),
    SCOOTER("scooter", "driving", "motorcycle", 26.0),
    BIKE("bike", "cycling", "bicycle", 15.0);

    companion object {
        fun fromKey(key: String?): RouteProfile = entries.firstOrNull { it.key == key } ?: CAR
    }
}

object Geo {
    fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
