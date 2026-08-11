package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
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

    private val windowManager =
        service.getSystemService(WindowManager::class.java)

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

    private val green = Color.rgb(83, 205, 115)
    private val red = Color.rgb(245, 92, 92)
    private val white = Color.rgb(245, 247, 250)
    private val gray = Color.rgb(165, 170, 180)

    fun show(
        result: Profitability,
        applicationName: String,
        applicationIcon: Drawable?
    ) {
        if (root == null) {
            createOverlay()
        }

        val profitable = result.profitable
        val accent = if (profitable) green else red

        appName?.text = applicationName

        if (applicationIcon != null) {
            appIcon?.setImageDrawable(applicationIcon)
            appIcon?.visibility = View.VISIBLE
        } else {
            appIcon?.visibility = View.GONE
        }

        status?.apply {
            text = if (profitable) {
                "OPŁACALNE"
            } else {
                "NIEOPŁACALNE"
            }

            setTextColor(accent)
            background = pillBackground(accent)
        }

        amountValue?.text = money(result.grossPln)
        netValue?.text = money(result.netPln)
        routeValue?.text = "${num(result.distanceKm)} km"
        timeValue?.text = "${result.durationMinutes} min"
        hourlyValue?.text = "${money(result.netPerHour)}/h"
        perKmValue?.text = "${money(result.netPerKm)}/km"

        // Najważniejsze wyniki kolorujemy wg opłacalności.
        netValue?.setTextColor(accent)
        hourlyValue?.setTextColor(accent)
        perKmValue?.setTextColor(accent)

        amountValue?.setTextColor(white)
        routeValue?.setTextColor(white)
        timeValue?.setTextColor(white)

        root?.background = panelBackground(accent)
        root?.visibility = View.VISIBLE
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun destroy() {
        root?.let {
            runCatching {
                windowManager.removeView(it)
            }
        }

        root = null
    }

    private fun createOverlay() {

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(10),
                dp(9),
                dp(10),
                dp(9)
            )

            background = panelBackground(green)
        }

        // ---------- HEADER ----------

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        appIcon = ImageView(service).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        header.addView(
            appIcon,
            LinearLayout.LayoutParams(
                dp(28),
                dp(28)
            ).apply {
                marginEnd = dp(7)
            }
        )

        val titleContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }

        appName = TextView(service).apply {
            text = "Delivery"
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
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        panel.addView(header)

        // ---------- STATUS ----------

        status = TextView(service).apply {
            text = "OPŁACALNE"
            textSize = 9f

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setPadding(
                dp(7),
                dp(3),
                dp(7),
                dp(3)
            )

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

        // ---------- WIERSZ 1 ----------

        panel.addView(
            metricRow(
                leftLabel = "KWOTA",
                rightLabel = "NETTO"
            ).also {
                amountValue = it.first
                netValue = it.second
            }.third
        )

        // ---------- WIERSZ 2 ----------

        panel.addView(
            metricRow(
                leftLabel = "TRASA",
                rightLabel = "CZAS"
            ).also {
                routeValue = it.first
                timeValue = it.second
            }.third
        )

        // ---------- WIERSZ 3 ----------

        panel.addView(
            metricRow(
                leftLabel = "NA GODZ.",
                rightLabel = "PLN/KM"
            ).also {
                hourlyValue = it.first
                perKmValue = it.second
            }.third
        )

        val params = WindowManager.LayoutParams(
            dp(205),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

            PixelFormat.TRANSLUCENT
        ).apply {

            gravity =
                Gravity.TOP or Gravity.END

            x = dp(8)
            y = dp(58)
        }

        windowManager.addView(
            panel,
            params
        )

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
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            right.first,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        return Triple(
            left.second,
            right.second,
            row
        )
    }

    private fun metricCell(
        labelText: String
    ): Pair<LinearLayout, TextView> {

        val container =
            LinearLayout(service).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    dp(2),
                    dp(4),
                    dp(3)
                )
            }

        val label =
            TextView(service).apply {

                text = labelText
                textSize = 7.5f
                setTextColor(gray)
                maxLines = 1
            }

        val value =
            TextView(service).apply {

                text = "--"
                textSize = 11.5f
                setTextColor(white)

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                maxLines = 1
            }

        container.addView(label)
        container.addView(value)

        return Pair(
            container,
            value
        )
    }

    private fun panelBackground(
        borderColor: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(
                Color.argb(
                    238,
                    18,
                    21,
                    26
                )
            )

            cornerRadius =
                dp(11).toFloat()

            setStroke(
                dp(1),
                borderColor
            )
        }
    }

    private fun pillBackground(
        accent: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(
                Color.argb(
                    45,
                    Color.red(accent),
                    Color.green(accent),
                    Color.blue(accent)
                )
            )

            cornerRadius =
                dp(12).toFloat()

            setStroke(
                dp(1),
                accent
            )
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                service.resources
                    .displayMetrics
                    .density
            ).toInt()
    }

    private fun money(
        value: Double
    ): String {

        return String.format(
            Locale.forLanguageTag("pl-PL"),
            "%.2f zł",
            value
        )
    }

    private fun num(
        value: Double
    ): String {

        return String.format(
            Locale.forLanguageTag("pl-PL"),
            "%.2f",
            value
        )
    }
}