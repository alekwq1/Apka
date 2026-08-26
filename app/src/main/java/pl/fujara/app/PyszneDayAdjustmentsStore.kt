package pl.fujara.app

import android.content.Context
import java.time.LocalDate

/**
 * Lightweight, editable adjustments for a day before the final result is confirmed.
 * Cash tips are persisted immediately so adding a tip during an active shift does not
 * require waiting for the end-of-day confirmation flow.
 */
class PyszneDayAdjustmentsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasCashTips(date: LocalDate): Boolean = prefs.contains(tipsKey(date))

    fun cashTips(date: LocalDate): Double =
        java.lang.Double.longBitsToDouble(
            prefs.getLong(tipsKey(date), java.lang.Double.doubleToRawLongBits(0.0))
        ).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

    fun setCashTips(date: LocalDate, value: Double) {
        val safe = value.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        prefs.edit().putLong(tipsKey(date), java.lang.Double.doubleToRawLongBits(safe)).apply()
    }

    fun addCashTip(date: LocalDate, amount: Double): Double {
        val safeAmount = amount.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val updated = cashTips(date) + safeAmount
        setCashTips(date, updated)
        return updated
    }

    fun clear(date: LocalDate) {
        prefs.edit().remove(tipsKey(date)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun tipsKey(date: LocalDate) = "tips_$date"

    companion object {
        const val PREFS_NAME = "pyszne_day_adjustments"
    }
}
