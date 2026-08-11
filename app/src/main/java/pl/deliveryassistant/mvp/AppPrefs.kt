package pl.deliveryassistant.mvp

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(
        "delivery_assistant",
        Context.MODE_PRIVATE
    )

    /**
     * To jest nasz wewnetrzny wlacznik analizy.
     * Nie odbiera on uprawnienia Accessibility w Androidzie.
     */
    var analysisEnabled: Boolean
        get() = prefs.getBoolean("analysis_enabled", true)
        set(value) = prefs.edit()
            .putBoolean("analysis_enabled", value)
            .apply()

    var targetPackage: String
        get() = prefs.getString("target_package", "") ?: ""
        set(value) = prefs.edit()
            .putString("target_package", value.trim())
            .apply()

    var vehicleCostPerKm: Double
        get() = getDouble("vehicle_cost_km", 0.35)
        set(value) = putDouble("vehicle_cost_km", value)

    var minimumNetPerKm: Double
        get() = getDouble("min_net_km", 2.50)
        set(value) = putDouble("min_net_km", value)

    var minimumNetPerHour: Double
        get() = getDouble("min_net_hour", 35.0)
        set(value) = putDouble("min_net_hour", value)

    /**
     * Starsze wersje zapisywaly wartosci jako Float.
     * Czytamy zarowno Number, jak i String.
     */
    private fun getDouble(
        key: String,
        defaultValue: Double
    ): Double {
        return when (val stored = prefs.all[key]) {
            is Number -> stored.toDouble()
            is String -> stored.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun putDouble(
        key: String,
        value: Double
    ) {
        prefs.edit()
            .putString(key, value.toString())
            .apply()
    }
}
