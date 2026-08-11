package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DeliveryAccessibilityService : AccessibilityService() {
    private lateinit var overlay: OverlayController
    private lateinit var prefs: AppPrefs
    private val handler = Handler(Looper.getMainLooper())
    private var pendingScan: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPrefs(this)
        overlay = OverlayController(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val configuredPackage = prefs.targetPackage
        val activePackage = event.packageName?.toString().orEmpty()
        if (configuredPackage.isNotBlank() && activePackage != configuredPackage) {
            overlay.hide()
            return
        }

        pendingScan?.let(handler::removeCallbacks)
        pendingScan = Runnable { scanActiveWindow() }.also {
            handler.postDelayed(it, 220)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingScan?.let(handler::removeCallbacks)
        overlay.destroy()
        super.onDestroy()
    }

    private fun scanActiveWindow() {
        val root = rootInActiveWindow ?: run {
            overlay.hide()
            return
        }

        val text = buildString {
            collectText(root, this, 0)
        }

        val offer = OfferParser.parse(text) ?: run {
            overlay.hide()
            return
        }

        val rules = ProfitabilityCalculator.Rules(
            vehicleCostPerKm = prefs.vehicleCostPerKm,
            minimumNetPerKm = prefs.minimumNetPerKm,
            minimumNetPerHour = prefs.minimumNetPerHour,
            fallbackMinutes = prefs.fallbackMinutes
        )
        overlay.show(ProfitabilityCalculator.calculate(offer, rules))
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 40) return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            out.append(it).append('\n')
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            out.append(it).append('\n')
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectText(child, out, depth + 1)
            }
        }
    }
}
