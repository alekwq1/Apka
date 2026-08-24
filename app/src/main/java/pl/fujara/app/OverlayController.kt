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
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
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
    private var zusText: TextView? = null
    private var blacklistText: TextView? = null
    private var collapseRoot: TextView? = null
    private var restoreRoot: TextView? = null
    private var saveHistoryRoot: TextView? = null
    private var currentHistoryEntry: PyszneDeliveryLog? = null
    private var currentHistoryAlreadySaved: Boolean = false
    private var onSaveHistory: ((PyszneDeliveryLog) -> PyszneSaveResult)? = null
    private var userCollapsed = false
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
        applicationIcon: Drawable?,
        blacklistHits: BlacklistHits = BlacklistHits(),
        historyEntry: PyszneDeliveryLog? = null,
        historyAlreadySaved: Boolean = false,
        onSaveHistory: ((PyszneDeliveryLog) -> PyszneSaveResult)? = null
    ) {
        val language = prefs.languageCode
        if (root == null || overlayLanguage != language) {
            removeOverlayView()
            createOverlay(language)
            overlayLanguage = language
        }

        val profitabilityAccent = when (result.status) {
            ProfitabilityStatus.PROFITABLE -> green
            ProfitabilityStatus.ALMOST_PROFITABLE -> yellow
            ProfitabilityStatus.UNPROFITABLE -> red
            ProfitabilityStatus.NO_TIME -> amber
        }
        val accent = if (blacklistHits.hasAny) red else profitabilityAccent

        appName?.text = applicationName

        if (applicationIcon != null) {
            appIcon?.setImageDrawable(applicationIcon)
            appIcon?.visibility = View.VISIBLE
        } else {
            appIcon?.visibility = View.GONE
        }

        status?.apply {
            text = if (blacklistHits.hasAny) {
                tr(language, "CZARNA LISTA", "BLOCKLIST", "ЧОРНИЙ СПИСОК", "ЧЕРНЫЙ СПИСОК")
            } else {
                when (result.status) {
                    ProfitabilityStatus.PROFITABLE -> tr(language, "DOBRA", "GOOD", "ДОБРЕ", "ХОРОШО")
                    ProfitabilityStatus.ALMOST_PROFITABLE -> tr(language, "NA STYK", "BORDERLINE", "НА МЕЖІ", "НА ГРАНИ")
                    ProfitabilityStatus.UNPROFITABLE -> tr(language, "SŁABA", "POOR", "СЛАБА", "СЛАБАЯ")
                    ProfitabilityStatus.NO_TIME -> tr(language, "BRAK CZASU", "NO TIME", "НЕМАЄ ЧАСУ", "НЕТ ВРЕМЕНИ")
                }
            }
            setTextColor(accent)
            background = pillBackground(accent)
        }

        amountRow?.leftValue?.text = earningMoney(result.grossPln, language)
        amountRow?.rightValue?.text = earningMoney(result.netPln, language)

        blacklistText?.apply {
            val warnings = buildList {
                blacklistHits.restaurant?.let {
                    add(tr(language, "Restauracja: $it", "Restaurant: $it", "Ресторан: $it", "Ресторан: $it"))
                }
                blacklistHits.customer?.let {
                    add(tr(language, "Odbiorca: $it", "Customer: $it", "Клієнт: $it", "Получатель: $it"))
                }
            }
            text = warnings.joinToString("  •  ")
            visibility = if (warnings.isNotEmpty()) View.VISIBLE else View.GONE
        }

        zusText?.apply {
            text = if (result.zusPercent > 0.0) {
                tr(
                    language,
                    "Po ZUS ${earningMoney(result.afterZusPln, language)} (${num(result.zusPercent, language)}%)",
                    "After ZUS ${earningMoney(result.afterZusPln, language)} (${num(result.zusPercent, language)}%)",
                    "Після ZUS ${earningMoney(result.afterZusPln, language)} (${num(result.zusPercent, language)}%)",
                    "После ZUS ${earningMoney(result.afterZusPln, language)} (${num(result.zusPercent, language)}%)"
                )
            } else ""
            visibility = if (result.zusPercent > 0.0) View.VISIBLE else View.GONE
        }
        distanceRow?.leftValue?.text = "${num(result.distanceKm, language)} km"
        distanceRow?.rightValue?.text = result.durationMinutes?.let { "$it min" } ?: "--"
        rateRow?.leftValue?.text = result.netPerHour?.let { compactRateText(hourlyMoney(it, language), "zł/h") } ?: "--"
        rateRow?.rightValue?.text = result.netPerKm?.let { compactRateText(perKmMoney(it, language), "zł/km") } ?: "--"

        applyFontScale()

        amountRow?.rightValue?.setTextColor(profitabilityAccent)
        rateRow?.leftValue?.setTextColor(if (result.netPerHour != null) profitabilityAccent else gray)
        rateRow?.rightValue?.setTextColor(if (result.netPerKm != null) profitabilityAccent else gray)
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
            val sourceLabel = when (result.durationSource) {
                DurationSource.DIRECT_TOTAL -> tr(language, "czas z oferty", "time from offer", "час з пропозиції", "время из предложения")
                DurationSource.PLANNED_DELIVERY -> tr(language, "czas: teraz → planowana dostawa", "time: now → planned delivery", "час: зараз → планова доставка", "время: сейчас → плановая доставка")
                DurationSource.UNKNOWN -> tr(language, "brak wiarygodnego czasu dostawy", "no reliable delivery time", "немає надійного часу доставки", "нет надежного времени доставки")
            }
            val bufferLabel = if (result.extraTimeMinutes > 0 && result.durationMinutes != null) {
                tr(
                    language,
                    " + ${result.extraTimeMinutes} min zapasu",
                    " + ${result.extraTimeMinutes} min buffer",
                    " + ${result.extraTimeMinutes} хв запасу",
                    " + ${result.extraTimeMinutes} мин запаса"
                )
            } else ""
            text = sourceLabel + bufferLabel
            setTextColor(if (result.durationSource == DurationSource.UNKNOWN) amber else gray)
            visibility = if (prefs.showTime) View.VISIBLE else View.GONE
        }

        fujaraGauge?.setStatus(result.status)
        root?.background = panelBackground(accent, prefs.overlayOpacityPercent)

        currentHistoryEntry = historyEntry
        currentHistoryAlreadySaved = historyAlreadySaved
        this.onSaveHistory = onSaveHistory
        updateHistorySaveButton(language)

        if (userCollapsed) {
            root?.visibility = View.GONE
            collapseRoot?.visibility = View.GONE
            restoreRoot?.visibility = View.VISIBLE
            saveHistoryRoot?.visibility = View.GONE
        } else {
            root?.visibility = View.VISIBLE
            collapseRoot?.visibility = View.VISIBLE
            restoreRoot?.visibility = View.GONE
            if (historyEntry != null) {
                saveHistoryRoot?.visibility = View.VISIBLE
                positionHistorySaveButton()
            }
        }
    }

    fun hide() {
        userCollapsed = false
        root?.visibility = View.GONE
        collapseRoot?.visibility = View.GONE
        restoreRoot?.visibility = View.GONE
        saveHistoryRoot?.visibility = View.GONE
        currentHistoryEntry = null
        onSaveHistory = null
    }

    fun isVisible(): Boolean =
        root?.visibility == View.VISIBLE || restoreRoot?.visibility == View.VISIBLE

    fun destroy() {
        removeOverlayView()
    }

    fun screenBounds(): Rect? {
        val visibleViews = listOfNotNull(
            root?.takeIf { it.visibility == View.VISIBLE },
            restoreRoot?.takeIf { it.visibility == View.VISIBLE },
            saveHistoryRoot?.takeIf { it.visibility == View.VISIBLE }
        ).filter { it.width > 0 && it.height > 0 }

        if (visibleViews.isEmpty()) return null

        var union: Rect? = null
        visibleViews.forEach { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val bounds = Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height
            )
            union = union?.apply { this.union(bounds) } ?: bounds
        }
        return union
    }

    private fun removeOverlayView() {
        root?.let { runCatching { windowManager.removeView(it) } }
        collapseRoot?.let { runCatching { windowManager.removeView(it) } }
        restoreRoot?.let { runCatching { windowManager.removeView(it) } }
        saveHistoryRoot?.let { runCatching { windowManager.removeView(it) } }
        root = null
        collapseRoot = null
        restoreRoot = null
        saveHistoryRoot = null
        currentHistoryEntry = null
        currentHistoryAlreadySaved = false
        onSaveHistory = null
        amountRow = null
        distanceRow = null
        rateRow = null
        zusText = null
        blacklistText = null
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
            ellipsize = null
            includeFontPadding = false
            setHorizontallyScrolling(false)
        }

        val assistantName = TextView(service).apply {
            text = tr(language, "FUJARA · policz zanim przyjmiesz", "FUJARA · check before accepting", "FUJARA · перевір до прийняття", "FUJARA · проверь до принятия")
            textSize = 7.5f
            setTextColor(gray)
            maxLines = 1
            ellipsize = null
            includeFontPadding = false
            setHorizontallyScrolling(false)
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

        // Rezerwujemy miejsce na przycisk. Sam panel pozostaje NOT_TOUCHABLE,
        // a maly przycisk jest osobnym oknem - dzieki temu panel nie blokuje
        // klikniec mapy/akceptacji pod soba.
        header.addView(
            View(service),
            LinearLayout.LayoutParams(dp(30), dp(30))
        )
        panel.addView(header)

        val divider = View(service).apply { setBackgroundColor(Color.rgb(48, 53, 60)) }
        panel.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(7)
                bottomMargin = dp(7)
            }
        )

        blacklistText = TextView(service).apply {
            textSize = 8.5f
            setTextColor(red)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(5), dp(4), dp(5), dp(7))
            background = pillBackground(red)
        }
        panel.addView(blacklistText)

        amountRow = metricRow(
            tr(language, "KWOTA", "AMOUNT", "СУМА", "СУММА"),
            tr(language, "PO KOSZTACH", "AFTER COSTS", "ПІСЛЯ ВИТРАТ", "ПОСЛЕ РАСХОДОВ")
        ).also { panel.addView(it.row) }

        zusText = TextView(service).apply {
            textSize = 8f
            setTextColor(gray)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(3), dp(1), dp(3), dp(5))
        }
        panel.addView(zusText)

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

        val availableWidth = (service.resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(220))
        val panelWidth = minOf(dp(268), availableWidth)
        val params = WindowManager.LayoutParams(
            panelWidth,
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

        val collapse = TextView(service).apply {
            text = "−"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            background = pillBackground(gray)
            isClickable = true
            isFocusable = false
            contentDescription = tr(language, "Ukryj panel", "Hide panel", "Сховати панель", "Скрыть панель")
            setOnClickListener {
                userCollapsed = true
                panel.visibility = View.GONE
                visibility = View.GONE
                restoreRoot?.visibility = View.VISIBLE
                saveHistoryRoot?.visibility = View.GONE
            }
        }
        val collapseParams = WindowManager.LayoutParams(
            dp(30),
            dp(30),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(11)
            y = dp(66)
        }
        windowManager.addView(collapse, collapseParams)
        collapseRoot = collapse

        val restore = TextView(service).apply {
            text = "FUJARA  👁"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = panelBackground(green, prefs.overlayOpacityPercent)
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            contentDescription = tr(language, "Pokaż panel FUJARA", "Show FUJARA panel", "Показати панель FUJARA", "Показать панель FUJARA")
            setOnClickListener {
                userCollapsed = false
                visibility = View.GONE
                panel.visibility = View.VISIBLE
                collapseRoot?.visibility = View.VISIBLE
                if (currentHistoryEntry != null) {
                    saveHistoryRoot?.visibility = View.VISIBLE
                    positionHistorySaveButton()
                }
            }
        }
        val restoreParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(58)
        }
        windowManager.addView(restore, restoreParams)
        restoreRoot = restore

        val saveHistory = TextView(service).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panelBackground(green, prefs.overlayOpacityPercent)
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            contentDescription = tr(language, "Zapisz dane zlecenia", "Save order data", "Зберегти дані замовлення", "Сохранить данные заказа")
            setOnClickListener {
                val entry = currentHistoryEntry ?: return@setOnClickListener
                if (currentHistoryAlreadySaved) {
                    updateHistorySaveButton(language)
                    return@setOnClickListener
                }
                when (onSaveHistory?.invoke(entry)) {
                    PyszneSaveResult.SAVED, PyszneSaveResult.DUPLICATE -> {
                        currentHistoryAlreadySaved = true
                        updateHistorySaveButton(language)
                    }
                    null -> Unit
                }
            }
        }
        val saveHistoryParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(58)
        }
        windowManager.addView(saveHistory, saveHistoryParams)
        saveHistoryRoot = saveHistory
    }

    private fun updateHistorySaveButton(language: String) {
        val button = saveHistoryRoot ?: return
        val entry = currentHistoryEntry
        if (entry == null) {
            button.visibility = View.GONE
            return
        }

        if (currentHistoryAlreadySaved) {
            button.text = tr(language, "✓ ZAPISANE", "✓ SAVED", "✓ ЗБЕРЕЖЕНО", "✓ СОХРАНЕНО")
            button.setTextColor(green)
            button.background = panelBackground(green, prefs.overlayOpacityPercent)
        } else {
            button.text = tr(language, "＋ ZAPISZ DANE", "＋ SAVE DATA", "＋ ЗБЕРЕГТИ ДАНІ", "＋ СОХРАНИТЬ ДАННЫЕ")
            button.setTextColor(white)
            button.background = panelBackground(amber, prefs.overlayOpacityPercent)
        }
    }

    private fun positionHistorySaveButton() {
        val panel = root ?: return
        val button = saveHistoryRoot ?: return
        panel.post panelPost@ {
            if (button.visibility != View.VISIBLE || panel.visibility != View.VISIBLE) return@panelPost
            button.post buttonPost@ {
                if (button.visibility != View.VISIBLE || panel.visibility != View.VISIBLE) return@buttonPost
                val location = IntArray(2)
                panel.getLocationOnScreen(location)
                val params = button.layoutParams as? WindowManager.LayoutParams ?: return@buttonPost
                // Przycisk lezy W obrebie panelu FUJARA, zamiast pod nim. Dzieki temu
                // nie zaslania dodatkowego fragmentu Pyszne i nie pogarsza kolejnych OCR.
                params.y = location[1] + (panel.height - button.height - dp(6)).coerceAtLeast(0)
                params.x = dp(8)
                runCatching { windowManager.updateViewLayout(button, params) }
            }
        }
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
            ellipsize = null
            includeFontPadding = false
            setHorizontallyScrolling(false)
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

    private fun earningMoney(value: Double, language: String): String =
        if (prefs.roundEarnings) {
            String.format(localeFor(language), "%.0f zł", value)
        } else {
            String.format(localeFor(language), "%.2f zł", value)
        }

    private fun hourlyMoney(value: Double, language: String): String =
        if (prefs.roundEarnings) {
            String.format(localeFor(language), "%.0f zł/h", value)
        } else {
            String.format(localeFor(language), "%.2f zł/h", value)
        }

    private fun perKmMoney(value: Double, language: String): String =
        String.format(localeFor(language), "%.2f zł/km", value)

    private fun num(value: Double, language: String): String =
        String.format(localeFor(language), "%.2f", value)

    private fun applyFontScale() {
        val scale = prefs.overlayFontScalePercent.coerceIn(80, 170) / 100f
        val onlyHourly = prefs.showHourly && !prefs.showPerKm &&
            !prefs.showAmount && !prefs.showAfterCosts && !prefs.showTime && !prefs.showDistance
        val onlyRates = (prefs.showHourly || prefs.showPerKm) &&
            !prefs.showAmount && !prefs.showAfterCosts && !prefs.showTime && !prefs.showDistance

        // Direct textSize scaling is intentional. The previous AutoSize implementation
        // kept shrinking the text back to fit the cell, so the user's size slider
        // appeared to do nothing. Units are rendered smaller instead (compactRateText).
        amountRow?.leftValue?.textSize = 11.8f * scale
        amountRow?.rightValue?.textSize = 11.8f * scale
        distanceRow?.leftValue?.textSize = 11.8f * scale
        distanceRow?.rightValue?.textSize = 11.8f * scale
        rateRow?.leftValue?.textSize = when {
            onlyHourly -> 20f * scale
            onlyRates -> 15.5f * scale
            else -> 12.5f * scale
        }
        rateRow?.rightValue?.textSize = if (onlyRates) 15.5f * scale else 12.5f * scale
    }

    private fun compactRateText(text: String, unit: String): SpannableString {
        val result = SpannableString(text)
        val start = text.lastIndexOf(unit)
        if (start >= 0) {
            result.setSpan(
                RelativeSizeSpan(0.62f),
                start,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return result
    }

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
