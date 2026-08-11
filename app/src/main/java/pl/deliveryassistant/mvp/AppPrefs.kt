package pl.deliveryassistant.mvp

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("delivery_assistant", Context.MODE_PRIVATE)

    var targetPackage: String
        get() = prefs.getString("target_package", "") ?: ""
        set(value) = prefs.edit().putString("target_package", value.trim()).apply()

    var vehicleCostPerKm: Double
        get() = prefs.getFloat("vehicle_cost_km", 0.35f).toDouble()
        set(value) = prefs.edit().putFloat("vehicle_cost_km", value.toFloat()).apply()

    var minimumNetPerKm: Double
        get() = prefs.getFloat("min_net_km", 2.50f).toDouble()
        set(value) = prefs.edit().putFloat("min_net_km", value.toFloat()).apply()

    var minimumNetPerHour: Double
        get() = prefs.getFloat("min_net_hour", 35f).toDouble()
        set(value) = prefs.edit().putFloat("min_net_hour", value.toFloat()).apply()

    var fallbackMinutes: Int
        get() = prefs.getInt("fallback_minutes", 15)
        set(value) = prefs.edit().putInt("fallback_minutes", value).apply()
}
