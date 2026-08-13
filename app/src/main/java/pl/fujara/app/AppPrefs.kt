package pl.fujara.app

import android.content.Context
import java.util.Locale

class AppPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(
        "delivery_assistant",
        Context.MODE_PRIVATE
    )

    init {
        migrateOverlayDefaultsIfNeeded()
    }

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

    /**
     * Jezyk nie jest na sztywno ustawiony na polski. Dopoki uzytkownik sam go
     * nie zmieni, bierzemy obslugiwany jezyk systemu telefonu.
     */
    var languageCode: String
        get() = prefs.getString("language_code", null) ?: detectSystemLanguage()
        set(value) = prefs.edit().putString("language_code", normalizeLanguage(value)).apply()

    var themeMode: AppThemeMode
        get() = AppThemeMode.fromKey(prefs.getString("theme_mode", AppThemeMode.SYSTEM.key))
        set(value) = prefs.edit().putString("theme_mode", value.key).apply()

    var decisionBasis: DecisionBasis
        get() = DecisionBasis.fromKey(prefs.getString("decision_basis", DecisionBasis.MIXED.key))
        set(value) = prefs.edit().putString("decision_basis", value.key).apply()

    var overlayOpacityPercent: Int
        get() = prefs.getInt("overlay_opacity_percent", 90).coerceIn(45, 100)
        set(value) = prefs.edit().putInt("overlay_opacity_percent", value.coerceIn(45, 100)).apply()

    var overlayFontScalePercent: Int
        get() = prefs.getInt("overlay_font_scale_percent", 115).coerceIn(80, 170)
        set(value) = prefs.edit().putInt("overlay_font_scale_percent", value.coerceIn(80, 170)).apply()

    var showHourly: Boolean
        get() = prefs.getBoolean("overlay_show_hourly", true)
        set(value) = prefs.edit().putBoolean("overlay_show_hourly", value).apply()

    var showPerKm: Boolean
        get() = prefs.getBoolean("overlay_show_per_km", true)
        set(value) = prefs.edit().putBoolean("overlay_show_per_km", value).apply()

    var showAmount: Boolean
        get() = prefs.getBoolean("overlay_show_amount", false)
        set(value) = prefs.edit().putBoolean("overlay_show_amount", value).apply()

    var showAfterCosts: Boolean
        get() = prefs.getBoolean("overlay_show_after_costs", false)
        set(value) = prefs.edit().putBoolean("overlay_show_after_costs", value).apply()

    var showTime: Boolean
        get() = prefs.getBoolean("overlay_show_time", false)
        set(value) = prefs.edit().putBoolean("overlay_show_time", value).apply()

    var showDistance: Boolean
        get() = prefs.getBoolean("overlay_show_distance", false)
        set(value) = prefs.edit().putBoolean("overlay_show_distance", value).apply()

    /** Kwoty i stawka godzinowa sa prezentowane jako pelne PLN. */
    var roundEarnings: Boolean
        get() = prefs.getBoolean("round_earnings", true)
        set(value) = prefs.edit().putBoolean("round_earnings", value).apply()

    var vehicleCostPerKm: Double
        get() = getDouble("vehicle_cost_km", 0.35)
        set(value) = putDouble("vehicle_cost_km", value)

    /** Gorna granica zoltego zakresu. Od niej oferta jest zielona. */
    var minimumNetPerKm: Double
        get() = getDouble("min_net_km", 2.50)
        set(value) = putDouble("min_net_km", value)

    /** Szerokosc zoltego zakresu ponizej zielonego progu. */
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

    fun rulesForCourier(applicationName: String): ProfitabilityCalculator.Rules =
        rulesForPlatform(CourierPlatform.fromDisplayName(applicationName))

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

    private fun migrateOverlayDefaultsIfNeeded() {
        if (prefs.getBoolean("overlay_defaults_v2_applied", false)) return

        prefs.edit()
            .putBoolean("overlay_show_hourly", true)
            .putBoolean("overlay_show_per_km", true)
            .putBoolean("overlay_show_amount", false)
            .putBoolean("overlay_show_after_costs", false)
            .putBoolean("overlay_show_time", false)
            .putBoolean("overlay_show_distance", false)
            .putBoolean("round_earnings", true)
            .putBoolean("overlay_defaults_v2_applied", true)
            .apply()
    }

    private fun detectSystemLanguage(): String {
        val language = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0]?.language
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale?.language
            }
        }.getOrNull() ?: Locale.getDefault().language

        return normalizeLanguage(language)
    }

    private fun normalizeLanguage(language: String?): String = when (language?.lowercase(Locale.ROOT)) {
        "pl" -> "pl"
        "uk", "ua" -> "uk"
        "ru" -> "ru"
        else -> "en"
    }

    private fun getDouble(key: String, defaultValue: Double): Double {
        return when (val stored = prefs.all[key]) {
            is Number -> stored.toDouble()
            is String -> stored.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun putDouble(key: String, value: Double) {
        prefs.edit().putString(key, value.toString()).apply()
    }
}

enum class CourierPlatform(val key: String) {
    GLOBAL("global"),
    UBER("uber"),
    WOLT("wolt"),
    GLOVO("glovo"),
    BOLT("bolt"),
    PYSZNE("pyszne"),
    STUART("stuart");

    companion object {
        fun fromDisplayName(applicationName: String): CourierPlatform {
            val name = applicationName.lowercase(Locale.ROOT)
            return when {
                "uber" in name -> UBER
                "wolt" in name -> WOLT
                "glovo" in name -> GLOVO
                "bolt" in name -> BOLT
                "pyszne" in name || "takeaway" in name || "just eat" in name -> PYSZNE
                "stuart" in name -> STUART
                else -> GLOBAL
            }
        }
    }
}

enum class DecisionBasis(val key: String) {
    HOURLY("hourly"),
    PER_KM("per_km"),
    MIXED("mixed");

    companion object {
        fun fromKey(key: String?): DecisionBasis = entries.firstOrNull { it.key == key } ?: MIXED
    }
}

enum class AppThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): AppThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
