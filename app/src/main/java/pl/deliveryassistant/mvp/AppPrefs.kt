package pl.deliveryassistant.mvp

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("delivery_assistant", Context.MODE_PRIVATE)

    var targetPackage: String
        get() = prefs.getString("target_package", "") ?: ""
        set(value) = prefs.edit().putString("target_package", value.trim()).apply()

    var vehicleCostPerKm: Double
        get() = getNonNegativeDouble("vehicle_cost_km", DEFAULT_VEHICLE_COST_PER_KM)
        set(value) = putNonNegativeDouble("vehicle_cost_km", value, DEFAULT_VEHICLE_COST_PER_KM)

    var minimumNetPerKm: Double
        get() = getNonNegativeDouble("min_net_km", DEFAULT_MIN_PER_KM)
        set(value) = putNonNegativeDouble("min_net_km", value, DEFAULT_MIN_PER_KM)

    var minimumNetPerHour: Double
        get() = getNonNegativeDouble("min_net_hour", DEFAULT_MIN_PER_HOUR)
        set(value) = putNonNegativeDouble("min_net_hour", value, DEFAULT_MIN_PER_HOUR)

    /**
     * Stare wersje zapisywaly wartosci jako Float, stad np. 0.349999994...
     * Czytamy Number i String. Nieprawidlowa, ujemna, NaN lub nieskonczona
     * wartosc nie moze zatrzymac analizy - wracamy wtedy do wartosci domyslnej.
     */
    private fun getNonNegativeDouble(key: String, defaultValue: Double): Double {
        val parsed = when (val stored = prefs.all[key]) {
            is Number -> stored.toDouble()
            is String -> stored.toDoubleOrNull()
            else -> null
        }

        return parsed
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: defaultValue
    }

    private fun putNonNegativeDouble(key: String, value: Double, defaultValue: Double) {
        val safe = value.takeIf { it.isFinite() && it >= 0.0 } ?: defaultValue
        prefs.edit().putString(key, safe.toString()).apply()
    }

    private companion object {
        const val DEFAULT_VEHICLE_COST_PER_KM = 0.35
        const val DEFAULT_MIN_PER_KM = 2.50
        const val DEFAULT_MIN_PER_HOUR = 35.0
    }
}
