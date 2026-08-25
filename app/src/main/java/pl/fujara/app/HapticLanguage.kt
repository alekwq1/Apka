package pl.fujara.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Spójny, oszczędny język wibracji dla ocen ofert. */
object HapticLanguage {
    fun offer(context: Context, status: ProfitabilityStatus) {
        if (!AppPrefs(context).hapticsEnabled) return
        val (timings, amplitudes) = when (status) {
            ProfitabilityStatus.PROFITABLE ->
                longArrayOf(0, 24, 42, 34) to intArrayOf(0, 85, 0, 140)
            ProfitabilityStatus.ALMOST_PROFITABLE ->
                longArrayOf(0, 32) to intArrayOf(0, 105)
            ProfitabilityStatus.UNPROFITABLE ->
                longArrayOf(0, 52, 42, 52) to intArrayOf(0, 155, 0, 120)
            ProfitabilityStatus.NO_TIME ->
                longArrayOf(0, 22) to intArrayOf(0, 70)
        }
        vibrate(context, timings, amplitudes)
    }

    fun record(context: Context) {
        if (!AppPrefs(context).hapticsEnabled) return
        vibrate(
            context,
            longArrayOf(0, 42, 40, 64, 38, 105),
            intArrayOf(0, 145, 0, 205, 0, 255)
        )
    }

    private fun vibrate(context: Context, timings: LongArray, amplitudes: IntArray) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}
