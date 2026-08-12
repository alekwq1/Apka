package pl.deliveryassistant.mvp

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(
        "delivery_assistant",
        Context.MODE_PRIVATE
    )

    var analysisEnabled: Boolean
        get() = prefs.getBoolean("analysis_enabled", true)
        set(value) = prefs.edit().putBoolean("analysis_enabled", value).apply()

    var privacyAccepted: Boolean
        get() = prefs.getBoolean("privacy_accepted", false)
        set(value) = prefs.edit().putBoolean("privacy_accepted", value).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    var targetPackage: String
        get() = prefs.getString("target_package", "") ?: ""
        set(value) = prefs.edit().putString("target_package", value.trim()).apply()

    var languageCode: String
        get() = prefs.getString("language_code", "pl") ?: "pl"
        set(value) = prefs.edit().putString("language_code", value).apply()

    var overlayOpacityPercent: Int
        get() = prefs.getInt("overlay_opacity_percent", 88).coerceIn(35, 100)
        set(value) = prefs.edit().putInt("overlay_opacity_percent", value.coerceIn(35, 100)).apply()

    var showHourly: Boolean
        get() = prefs.getBoolean("overlay_show_hourly", true)
        set(value) = prefs.edit().putBoolean("overlay_show_hourly", value).apply()

    var showPerKm: Boolean
        get() = prefs.getBoolean("overlay_show_per_km", true)
        set(value) = prefs.edit().putBoolean("overlay_show_per_km", value).apply()

    var showAmount: Boolean
        get() = prefs.getBoolean("overlay_show_amount", true)
        set(value) = prefs.edit().putBoolean("overlay_show_amount", value).apply()

    var showAfterCosts: Boolean
        get() = prefs.getBoolean("overlay_show_after_costs", true)
        set(value) = prefs.edit().putBoolean("overlay_show_after_costs", value).apply()

    var showTime: Boolean
        get() = prefs.getBoolean("overlay_show_time", true)
        set(value) = prefs.edit().putBoolean("overlay_show_time", value).apply()

    var showDistance: Boolean
        get() = prefs.getBoolean("overlay_show_distance", true)
        set(value) = prefs.edit().putBoolean("overlay_show_distance", value).apply()

    var vehicleCostPerKm: Double
        get() = getDouble("vehicle_cost_km", 0.35)
        set(value) = putDouble("vehicle_cost_km", value)

    var minimumNetPerKm: Double
        get() = getDouble("min_net_km", 2.50)
        set(value) = putDouble("min_net_km", value)

    var toleranceNetPerKm: Double
        get() = getDouble("tolerance_net_km", 0.50)
        set(value) = putDouble("tolerance_net_km", value)

    var minimumNetPerHour: Double
        get() = getDouble("min_net_hour", 35.0)
        set(value) = putDouble("min_net_hour", value)

    var toleranceNetPerHour: Double
        get() = getDouble("tolerance_net_hour", 5.0)
        set(value) = putDouble("tolerance_net_hour", value)

    fun globalRules(): ProfitabilityCalculator.Rules = ProfitabilityCalculator.Rules(
        vehicleCostPerKm = vehicleCostPerKm,
        minimumNetPerKm = minimumNetPerKm,
        toleranceNetPerKm = toleranceNetPerKm,
        minimumNetPerHour = minimumNetPerHour,
        toleranceNetPerHour = toleranceNetPerHour
    )

    fun rulesForPlatform(platform: CourierPlatform): ProfitabilityCalculator.Rules {
        if (platform == CourierPlatform.GLOBAL || !hasCustomRules(platform)) {
            return globalRules()
        }

        val global = globalRules()
        val prefix = "rules_${platform.key}_"

        return ProfitabilityCalculator.Rules(
            vehicleCostPerKm = getDouble(prefix + "vehicle", global.vehicleCostPerKm),
            minimumNetPerKm = getDouble(prefix + "km", global.minimumNetPerKm),
            toleranceNetPerKm = getDouble(prefix + "km_tolerance", global.toleranceNetPerKm),
            minimumNetPerHour = getDouble(prefix + "hour", global.minimumNetPerHour),
            toleranceNetPerHour = getDouble(prefix + "hour_tolerance", global.toleranceNetPerHour)
        )
    }

    fun rulesForCourier(applicationName: String): ProfitabilityCalculator.Rules {
        val name = applicationName.lowercase()
        val platform = when {
            "uber" in name -> CourierPlatform.UBER
            "wolt" in name -> CourierPlatform.WOLT
            "glovo" in name -> CourierPlatform.GLOVO
            "bolt" in name -> CourierPlatform.BOLT
            "pyszne" in name || "takeaway" in name || "just eat" in name -> CourierPlatform.PYSZNE
            else -> CourierPlatform.GLOBAL
        }
        return rulesForPlatform(platform)
    }

    fun hasCustomRules(platform: CourierPlatform): Boolean {
        if (platform == CourierPlatform.GLOBAL) return true
        return prefs.getBoolean("rules_${platform.key}_custom", false)
    }

    fun setRules(
        platform: CourierPlatform,
        rules: ProfitabilityCalculator.Rules
    ) {
        if (platform == CourierPlatform.GLOBAL) {
            vehicleCostPerKm = rules.vehicleCostPerKm
            minimumNetPerKm = rules.minimumNetPerKm
            toleranceNetPerKm = rules.toleranceNetPerKm
            minimumNetPerHour = rules.minimumNetPerHour
            toleranceNetPerHour = rules.toleranceNetPerHour
            return
        }

        val prefix = "rules_${platform.key}_"
        prefs.edit()
            .putBoolean("rules_${platform.key}_custom", true)
            .putString(prefix + "vehicle", rules.vehicleCostPerKm.toString())
            .putString(prefix + "km", rules.minimumNetPerKm.toString())
            .putString(prefix + "km_tolerance", rules.toleranceNetPerKm.toString())
            .putString(prefix + "hour", rules.minimumNetPerHour.toString())
            .putString(prefix + "hour_tolerance", rules.toleranceNetPerHour.toString())
            .apply()
    }

    fun clearCustomRules(platform: CourierPlatform) {
        if (platform == CourierPlatform.GLOBAL) return
        prefs.edit().putBoolean("rules_${platform.key}_custom", false).apply()
    }

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
        prefs.edit().putString(key, value.toString()).apply()
    }
}

enum class CourierPlatform(val key: String) {
    GLOBAL("global"),
    UBER("uber"),
    WOLT("wolt"),
    GLOVO("glovo"),
    BOLT("bolt"),
    PYSZNE("pyszne")
}
