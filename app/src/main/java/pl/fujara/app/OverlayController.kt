package pl.fujara.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
    private val prefs = AppPrefs(service)
    private var root: LinearLayout? = null
    private var appIcon: ImageView? = null
    private var appName: TextView? = null
    private var status: TextView? = null
    private var amountRow: MetricRow? = null
    private var distanceRow: MetricRow? = null
    private var rateRow: MetricRow? = null
    private var scheduleText: TextView? = null
    private var timeSourceText: TextView? = null
    private var overlayLanguage = ""
    private var fujaraGauge: FujaraGaugeView? = null

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
        val language = prefs.languageCode
        if (root == null || overlayLanguage != language) {
            removeOverlayView()
            createOverlay(language)
            overlayLanguage = language
        }

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
                ProfitabilityStatus.PROFITABLE -> tr(language, "DOBRA", "GOOD", "ДОБРЕ", "ХОРОШО")
                ProfitabilityStatus.ALMOST_PROFITABLE -> tr(language, "NA STYK", "BORDERLINE", "НА МЕЖІ", "НА ГРАНИ")
                ProfitabilityStatus.UNPROFITABLE -> tr(language, "SŁABA", "POOR", "СЛАБА", "СЛАБАЯ")
                ProfitabilityStatus.NO_TIME -> tr(language, "BRAK CZASU", "NO TIME", "НЕМАЄ ЧАСУ", "НЕТ ВРЕМЕНИ")
            }
            setTextColor(accent)
            background = pillBackground(accent)
        }

        amountRow?.leftValue?.text = money(result.grossPln, language)
        amountRow?.rightValue?.text = money(result.netPln, language)
        distanceRow?.leftValue?.text = "${num(result.distanceKm, language)} km"
        distanceRow?.rightValue?.text = result.durationMinutes?.let { "$it min" } ?: "--"
        rateRow?.leftValue?.text = result.netPerHour?.let { "${money(it, language)}/h" } ?: "--"
        rateRow?.rightValue?.text = result.netPerKm?.let { "${money(it, language)}/km" } ?: "--"

        amountRow?.rightValue?.setTextColor(accent)
        rateRow?.leftValue?.setTextColor(if (result.netPerHour != null) accent else gray)
        rateRow?.rightValue?.setTextColor(if (result.netPerKm != null) accent else gray)
        amountRow?.leftValue?.setTextColor(white)
        distanceRow?.leftValue?.setTextColor(white)
        distanceRow?.rightValue?.setTextColor(if (result.durationMinutes != null) white else amber)

        applyMetricVisibility(
            amountRow,
            prefs.showAmount,
            prefs.showAfterCosts
        )
        applyMetricVisibility(
            distanceRow,
            prefs.showDistance,
            prefs.showTime
        )
        applyMetricVisibility(
            rateRow,
            prefs.showHourly,
            prefs.showPerKm
        )

        val schedule = buildScheduleLabel(result, language)
        scheduleText?.apply {
            text = schedule
            visibility = if (prefs.showTime && schedule.isNotBlank()) View.VISIBLE else View.GONE
        }

        timeSourceText?.apply {
            text = when (result.durationSource) {
                DurationSource.DIRECT_TOTAL -> tr(language, "czas z oferty", "time from offer", "час з пропозиції", "время из предложения")
                DurationSource.PLANNED_DELIVERY -> tr(language, "czas: teraz → planowana dostawa", "time: now → planned delivery", "час: зараз → планова доставка", "время: сейчас → плановая доставка")
                DurationSource.UNKNOWN -> tr(language, "brak wiarygodnego czasu dostawy", "no reliable delivery time", "немає надійного часу доставки", "нет надежного времени доставки")
            }
            setTextColor(if (result.durationSource == DurationSource.UNKNOWN) amber else gray)
            visibility = if (prefs.showTime) View.VISIBLE else View.GONE
        }

        fujaraGauge?.setStatus(result.status)
        root?.background = panelBackground(accent, prefs.overlayOpacityPercent)
        root?.visibility = View.VISIBLE
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun isVisible(): Boolean = root?.visibility == View.VISIBLE

    fun destroy() {
        removeOverlayView()
    }

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

    private fun removeOverlayView() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        amountRow = null
        distanceRow = null
        rateRow = null
        fujaraGauge = null
    }

    private fun createOverlay(language: String) {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = panelBackground(green, prefs.overlayOpacityPercent)
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fujaraGauge = FujaraGaugeView(service).also { gauge ->
            header.addView(
                gauge,
                LinearLayout.LayoutParams(dp(20), dp(48)).apply { marginEnd = dp(7) }
            )
        }

        appIcon = ImageView(service).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = iconBackground()
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        header.addView(
            appIcon,
            LinearLayout.LayoutParams(dp(25), dp(25)).apply { marginEnd = dp(7) }
        )

        val titleContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }

        appName = TextView(service).apply {
            text = tr(language, "Dostawa", "Delivery", "Доставка", "Доставка")
            textSize = 11.5f
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val assistantName = TextView(service).apply {
            text = tr(language, "FUJARA · policz zanim przyjmiesz", "FUJARA · check before accepting", "FUJARA · перевір до прийняття", "FUJARA · проверь до принятия")
            textSize = 7.5f
            setTextColor(gray)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        titleContainer.addView(appName)
        titleContainer.addView(assistantName)
        header.addView(
            titleContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        )

        status = TextView(service).apply {
            text = tr(language, "DOBRA", "GOOD", "ДОБРЕ", "ХОРОШО")
            textSize = 7.8f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            setTextColor(green)
            background = pillBackground(green)
            maxLines = 1
        }
        header.addView(status)
        panel.addView(header)

        val divider = View(service).apply { setBackgroundColor(Color.rgb(48, 53, 60)) }
        panel.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(7)
                bottomMargin = dp(7)
            }
        )

        amountRow = metricRow(
            tr(language, "KWOTA", "AMOUNT", "СУМА", "СУММА"),
            tr(language, "PO KOSZTACH", "AFTER COSTS", "ПІСЛЯ ВИТРАТ", "ПОСЛЕ РАСХОДОВ")
        ).also { panel.addView(it.row) }

        rateRow = metricRow(
            "PLN/H",
            "PLN/KM"
        ).also { panel.addView(it.row) }

        distanceRow = metricRow(
            tr(language, "DYSTANS", "DISTANCE", "ВІДСТАНЬ", "РАССТОЯНИЕ"),
            tr(language, "CZAS", "TIME", "ЧАС", "ВРЕМЯ")
        ).also { panel.addView(it.row) }

        scheduleText = TextView(service).apply {
            textSize = 8f
            setTextColor(white)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(2), dp(5), dp(2), 0)
        }
        panel.addView(scheduleText)

        timeSourceText = TextView(service).apply {
            text = tr(language, "czas z oferty", "time from offer", "час з пропозиції", "время из предложения")
            textSize = 7f
            setTextColor(gray)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
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
    ): MetricRow {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(5))
        }

        val left = metricCell(leftLabel)
        val right = metricCell(rightLabel)

        row.addView(
            left.container,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3) }
        )
        row.addView(
            right.container,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3) }
        )

        return MetricRow(
            row = row,
            leftCell = left.container,
            leftValue = left.value,
            rightCell = right.container,
            rightValue = right.value
        )
    }

    private fun metricCell(labelText: String): MetricCell {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = metricBackground()
        }

        val label = TextView(service).apply {
            text = labelText
            textSize = 7f
            setTextColor(gray)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }

        val value = TextView(service).apply {
            text = "--"
            textSize = 11.8f
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        container.addView(label)
        container.addView(value)
        return MetricCell(container, value)
    }

    private fun applyMetricVisibility(
        metricRow: MetricRow?,
        leftVisible: Boolean,
        rightVisible: Boolean
    ) {
        metricRow ?: return
        metricRow.row.visibility = if (leftVisible || rightVisible) View.VISIBLE else View.GONE
        metricRow.leftCell.visibility = if (leftVisible) View.VISIBLE else View.GONE
        metricRow.rightCell.visibility = if (rightVisible) View.VISIBLE else View.GONE
    }

    private fun buildScheduleLabel(result: Profitability, language: String): String {
        val pickup = result.pickupTimeMinutesOfDay?.let(::clock)
        val delivery = result.deliveryTimeMinutesOfDay?.let(::clock)

        return when {
            pickup != null && delivery != null -> tr(
                language,
                "Odbiór $pickup  •  Dostawa $delivery",
                "Pickup $pickup  •  Delivery $delivery",
                "Забір $pickup  •  Доставка $delivery",
                "Забрать $pickup  •  Доставка $delivery"
            )
            delivery != null -> tr(language, "Planowana dostawa $delivery", "Planned delivery $delivery", "Планова доставка $delivery", "Плановая доставка $delivery")
            pickup != null -> tr(language, "Planowany odbiór $pickup", "Planned pickup $pickup", "Плановий забір $pickup", "Плановый забор $pickup")
            else -> ""
        }
    }

    private fun panelBackground(borderColor: Int, opacityPercent: Int): GradientDrawable =
        GradientDrawable().apply {
            val alpha = (255 * opacityPercent.coerceIn(35, 100) / 100f).toInt()
            setColor(Color.argb(alpha, 11, 15, 18))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(190, Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)))
        }

    private fun metricBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(150, 27, 32, 38))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.argb(150, 53, 60, 68))
        }

    private fun iconBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(135, 255, 255, 255))
            cornerRadius = dp(7).toFloat()
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

    private fun money(value: Double, language: String): String =
        String.format(localeFor(language), "%.2f zł", value)

    private fun num(value: Double, language: String): String =
        String.format(localeFor(language), "%.2f", value)

    private fun localeFor(language: String): Locale = when (language) {
        "en" -> Locale.US
        "uk" -> Locale.forLanguageTag("uk-UA")
        "ru" -> Locale.forLanguageTag("ru-RU")
        else -> Locale.forLanguageTag("pl-PL")
    }

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String =
        when (language) {
            "en" -> en
            "uk" -> uk
            "ru" -> ru
            else -> pl
        }

    private fun clock(minutesOfDay: Int): String {
        val normalized = ((minutesOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)
        val hour = normalized / 60
        val minute = normalized % 60
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    private data class MetricCell(
        val container: LinearLayout,
        val value: TextView
    )


    /**
     * Mały znak FUJARA: abstrakcyjna fujarka / wskaźnik opłacalności.
     * Wypełnia się od dołu: czerwony = słabo, żółty = na styk, zielony = dobrze.
     */
    private class FujaraGaugeView(context: android.content.Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(70, 255, 255, 255)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(11, 15, 18)
        }
        private var level = 1f
        private var statusColor = Color.rgb(83, 205, 115)

        fun setStatus(status: ProfitabilityStatus) {
            when (status) {
                ProfitabilityStatus.PROFITABLE -> {
                    level = 1f
                    statusColor = Color.rgb(83, 205, 115)
                }
                ProfitabilityStatus.ALMOST_PROFITABLE -> {
                    level = 0.66f
                    statusColor = Color.rgb(255, 216, 74)
                }
                ProfitabilityStatus.UNPROFITABLE -> {
                    level = 0.33f
                    statusColor = Color.rgb(255, 101, 101)
                }
                ProfitabilityStatus.NO_TIME -> {
                    level = 0.50f
                    statusColor = Color.rgb(255, 184, 77)
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val tubeWidth = 9f * density
            val cx = width / 2f
            val top = 3f * density
            val bottom = height - 7f * density
            val left = cx - tubeWidth / 2f
            val right = cx + tubeWidth / 2f
            val radius = tubeWidth / 2f
            val track = RectF(left, top, right, bottom)
            canvas.drawRoundRect(track, radius, radius, trackPaint)

            val fullHeight = bottom - top
            val gap = 1.2f * density
            val segmentHeight = (fullHeight - gap * 2f) / 3f
            val fillBoundary = bottom - fullHeight * level
            val segments = listOf(
                Triple(bottom - segmentHeight, bottom, Color.rgb(255, 101, 101)),
                Triple(bottom - segmentHeight * 2f - gap, bottom - segmentHeight - gap, Color.rgb(255, 216, 74)),
                Triple(top, top + segmentHeight, Color.rgb(83, 205, 115))
            )

            segments.forEach { (segmentTop, segmentBottom, segmentColor) ->
                val visibleTop = maxOf(segmentTop, fillBoundary)
                if (visibleTop < segmentBottom) {
                    fillPaint.color = segmentColor
                    canvas.drawRoundRect(RectF(left, visibleTop, right, segmentBottom), radius, radius, fillPaint)
                }
            }

            val bell = Path().apply {
                moveTo(left - 3f * density, bottom - 1f * density)
                lineTo(right + 3f * density, bottom - 1f * density)
                lineTo(right + 5f * density, height - 1f * density)
                lineTo(left - 5f * density, height - 1f * density)
                close()
            }
            fillPaint.color = if (level > 0.02f) Color.rgb(255, 101, 101) else trackPaint.color
            canvas.drawPath(bell, fillPaint)

            val holeRadius = 1.35f * density
            listOf(0.31f, 0.48f, 0.65f).forEach { fraction ->
                val y = top + (bottom - top) * fraction
                canvas.drawCircle(cx, y, holeRadius, holePaint)
            }

            fillPaint.color = statusColor
            canvas.drawCircle(right + 3.5f * density, top + 2f * density, 1.6f * density, fillPaint)
        }
    }

    private data class MetricRow(
        val row: LinearLayout,
        val leftCell: LinearLayout,
        val leftValue: TextView,
        val rightCell: LinearLayout,
        val rightValue: TextView
    )
}
