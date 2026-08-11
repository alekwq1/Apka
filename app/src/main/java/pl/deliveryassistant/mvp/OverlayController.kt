package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class OverlayController(
    private val service: AccessibilityService
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)

    private var root: LinearLayout? = null
    private var appIcon: ImageView? = null
    private var appName: TextView? = null
    private var status: TextView? = null
    private var amountValue: TextView? = null
    private var netValue: TextView? = null
    private var routeValue: TextView? = null
    private var timeValue: TextView? = null
    private var hourlyValue: TextView? = null
    private var perKmValue: TextView? = null
    private var scheduleText: TextView? = null
    private var timeSourceText: TextView? = null

    private val green = Color.rgb(83, 205, 115)
    private val red = Color.rgb(245, 92, 92)
    private val yellow = Color.rgb(255, 214, 64)
    private val amber = Color.rgb(255, 184, 77)
    private val white = Color.rgb(245, 247, 250)
    private val gray = Color.rgb(165, 170, 180)

    fun show(
        result: Profitability,
        applicationName: String,
        applicationIcon: Drawable?
    ) {
        if (root == null) createOverlay()

        val accent = when (result.status) {
            ProfitabilityStatus.PROFITABLE -> green
            ProfitabilityStatus.ALMOST_PROFITABLE -> yellow
            ProfitabilityStatus.UNPROFITABLE -> red
            ProfitabilityStatus.NO_TIME -> amber
        }

        appName?.text = applicationName

        if (applicationIcon != null) {
            appIcon?.setImageDrawable(applicationIcon)
            appIcon?.visibility = View.VISIBLE
        } else {
            appIcon?.visibility = View.GONE
        }

        status?.apply {
            text = when (result.status) {
                ProfitabilityStatus.PROFITABLE -> "OPŁACALNE"
                ProfitabilityStatus.ALMOST_PROFITABLE -> "PRAWIE OPŁACALNE"
                ProfitabilityStatus.UNPROFITABLE -> "NIEOPŁACALNE"
                ProfitabilityStatus.NO_TIME -> "BRAK CZASU"
            }
            setTextColor(accent)
            background = pillBackground(accent)
        }

        amountValue?.text = money(result.grossPln)
        netValue?.text = money(result.netPln)
        routeValue?.text = "${num(result.distanceKm)} km"
        timeValue?.text = result.durationMinutes?.let { "$it min" } ?: "--"
        hourlyValue?.text = result.netPerHour?.let { "${money(it)}/h" } ?: "--"
        perKmValue?.text = result.netPerKm?.let { "${money(it)}/km" } ?: "--"

        netValue?.setTextColor(accent)
        hourlyValue?.setTextColor(if (result.netPerHour != null) accent else gray)
        perKmValue?.setTextColor(if (result.netPerKm != null) accent else gray)
        amountValue?.setTextColor(white)
        routeValue?.setTextColor(white)
        timeValue?.setTextColor(if (result.durationMinutes != null) white else amber)

        val schedule = buildScheduleLabel(result)
        scheduleText?.apply {
            text = schedule
            visibility = if (schedule.isBlank()) View.GONE else View.VISIBLE
        }

        timeSourceText?.apply {
            text = when (result.durationSource) {
                DurationSource.DIRECT_TOTAL -> "czas z oferty"
                DurationSource.PLANNED_DELIVERY -> "czas: teraz → planowana dostawa"
                DurationSource.UNKNOWN -> "brak wiarygodnego czasu dostawy"
            }
            setTextColor(if (result.durationSource == DurationSource.UNKNOWN) amber else gray)
        }

        root?.background = panelBackground(accent)
        root?.visibility = View.VISIBLE
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun destroy() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    /** Dokładny obszar overlayu w pikselach ekranu - używany do maskowania OCR na Androidzie 11-13. */
    fun screenBounds(): Rect? {
        val view = root ?: return null
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return null

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
    }

    private fun createOverlay() {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = panelBackground(green)
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        appIcon = ImageView(service).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        header.addView(
            appIcon,
            LinearLayout.LayoutParams(dp(27), dp(27)).apply {
                marginEnd = dp(7)
            }
        )

        val titleContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }

        appName = TextView(service).apply {
            text = "Dostawa"
            textSize = 12.5f
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }

        val assistantName = TextView(service).apply {
            text = "Delivery Assistant"
            textSize = 8.5f
            setTextColor(gray)
            maxLines = 1
        }

        titleContainer.addView(appName)
        titleContainer.addView(assistantName)
        header.addView(
            titleContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        panel.addView(header)

        status = TextView(service).apply {
            text = "OPŁACALNE"
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setTextColor(green)
            background = pillBackground(green)
        }

        panel.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(5)
            }
        )

        panel.addView(
            metricRow("KWOTA", "PO KOSZTACH").also {
                amountValue = it.first
                netValue = it.second
            }.third
        )

        panel.addView(
            metricRow("TRASA", "DO DOSTAWY").also {
                routeValue = it.first
                timeValue = it.second
            }.third
        )

        panel.addView(
            metricRow("PO KOSZT./H", "PO KOSZT./KM").also {
                hourlyValue = it.first
                perKmValue = it.second
            }.third
        )

        scheduleText = TextView(service).apply {
            textSize = 8.5f
            setTextColor(white)
            maxLines = 1
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        panel.addView(scheduleText)

        timeSourceText = TextView(service).apply {
            text = "czas z oferty"
            textSize = 7.5f
            setTextColor(gray)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        }
        panel.addView(timeSourceText)

        val params = WindowManager.LayoutParams(
            dp(238),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(58)
        }

        windowManager.addView(panel, params)
        root = panel
    }

    private fun metricRow(
        leftLabel: String,
        rightLabel: String
    ): Triple<TextView, TextView, LinearLayout> {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val left = metricCell(leftLabel)
        val right = metricCell(rightLabel)

        row.addView(
            left.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            right.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        return Triple(left.second, right.second, row)
    }

    private fun metricCell(labelText: String): Pair<LinearLayout, TextView> {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), dp(4), dp(3))
        }

        val label = TextView(service).apply {
            text = labelText
            textSize = 7.5f
            setTextColor(gray)
            maxLines = 1
        }

        val value = TextView(service).apply {
            text = "--"
            textSize = 11.5f
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }

        container.addView(label)
        container.addView(value)
        return Pair(container, value)
    }

    private fun buildScheduleLabel(result: Profitability): String {
        val pickup = result.pickupTimeMinutesOfDay?.let(::clock)
        val delivery = result.deliveryTimeMinutesOfDay?.let(::clock)

        return when {
            pickup != null && delivery != null -> "Odbiór $pickup  •  Dostawa $delivery"
            delivery != null -> "Planowana dostawa $delivery"
            pickup != null -> "Planowany odbiór $pickup"
            else -> ""
        }
    }

    private fun panelBackground(borderColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(238, 18, 21, 26))
            cornerRadius = dp(11).toFloat()
            setStroke(dp(1), borderColor)
        }

    private fun pillBackground(accent: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(
                Color.argb(
                    45,
                    Color.red(accent),
                    Color.green(accent),
                    Color.blue(accent)
                )
            )
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), accent)
        }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private fun money(value: Double): String =
        String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł", value)

    private fun num(value: Double): String =
        String.format(Locale.forLanguageTag("pl-PL"), "%.2f", value)

    private fun clock(minutesOfDay: Int): String {
        val normalized = ((minutesOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)
        val hour = normalized / 60
        val minute = normalized % 60
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }
}
