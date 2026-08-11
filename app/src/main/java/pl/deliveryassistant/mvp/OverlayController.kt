package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class OverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var amount: TextView? = null
    private var net: TextView? = null
    private var route: TextView? = null
    private var time: TextView? = null
    private var hourly: TextView? = null
    private var perKm: TextView? = null

    fun show(result: Profitability) {
        if (root == null) createOverlay()

        val ok = result.profitable
        status?.text = if (ok) "OPŁACALNE" else "NIEOPŁACALNE"
        status?.setTextColor(if (ok) Color.rgb(116, 220, 126) else Color.rgb(255, 140, 120))
        amount?.text = "Kwota   ${money(result.grossPln)}"
        net?.text = "Netto   ${money(result.netPln)}"
        route?.text = "Trasa   ${num(result.distanceKm)} km"
        time?.text = "Czas    ${result.durationMinutes} min"
        hourly?.text = "Na godz. ${money(result.netPerHour)}"
        perKm?.text = "Na km   ${money(result.netPerKm)}"

        val border = if (ok) Color.rgb(52, 155, 80) else Color.rgb(200, 80, 60)
        root?.background = panelBackground(border)
        root?.visibility = View.VISIBLE
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun destroy() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun createOverlay() {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = panelBackground(Color.rgb(52, 155, 80))
        }

        fun label(text: String, size: Float, bold: Boolean = false): TextView = TextView(service).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        panel.addView(label("Delivery Assistant", 12f, true))
        status = label("OPŁACALNE", 11f, true).also(panel::addView)
        amount = label("Kwota", 11f).also(panel::addView)
        net = label("Netto", 11f).also(panel::addView)
        route = label("Trasa", 11f).also(panel::addView)
        time = label("Czas", 11f).also(panel::addView)
        hourly = label("Na godz.", 11f).also(panel::addView)
        perKm = label("Na km", 11f).also(panel::addView)

        val params = WindowManager.LayoutParams(
            dp(170),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(60)
        }

        windowManager.addView(panel, params)
        root = panel
    }

    private fun panelBackground(borderColor: Int) = GradientDrawable().apply {
        setColor(Color.argb(225, 20, 24, 29))
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), borderColor)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
    private fun money(value: Double) = String.format(Locale("pl", "PL"), "%.2f zł", value)
    private fun num(value: Double) = String.format(Locale("pl", "PL"), "%.2f", value)
}
