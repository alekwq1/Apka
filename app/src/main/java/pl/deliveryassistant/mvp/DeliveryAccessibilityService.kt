package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalTime

class DeliveryAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private lateinit var prefs: AppPrefs

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: TextRecognizer? = null
    private var ocrBusy = false
    private var misses = 0
    private var pendingImmediateScan: Runnable? = null
    private var pendingThrottledScan: Runnable? = null
    private var lastScreenshotRequestAt = 0L

    private val scanIntervalMs = 1600L
    private val minimumScreenshotGapMs = 900L

    private val pollRunnable = object : Runnable {
        override fun run() {
            scanVisibleScreen()
            handler.postDelayed(this, scanIntervalMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPrefs(this)
        overlay = OverlayController(this)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        handler.postDelayed(pollRunnable, 600L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (!prefs.analysisEnabled) {
            if (::overlay.isInitialized) overlay.hide()
            return
        }

        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage == packageName) return

        val configuredPackage = prefs.targetPackage.trim()
        if (
            configuredPackage.isNotBlank() &&
            eventPackage != configuredPackage &&
            !isGalleryPackage(eventPackage)
        ) {
            return
        }

        pendingImmediateScan?.let { handler.removeCallbacks(it) }
        pendingImmediateScan = Runnable {
            pendingImmediateScan = null
            scanVisibleScreen()
        }.also {
            handler.postDelayed(it, 250L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        pendingImmediateScan?.let { handler.removeCallbacks(it) }
        pendingThrottledScan?.let { handler.removeCallbacks(it) }
        recognizer?.close()
        recognizer = null
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    private fun scanVisibleScreen() {
        if (ocrBusy) return

        if (!prefs.analysisEnabled) {
            if (::overlay.isInitialized) overlay.hide()
            return
        }

        // First try accessibility text. Gallery screenshots are handled by OCR below.
        if (scanAccessibilityOnly(hideOnFailure = false)) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            registerMiss()
            return
        }

        val screenshotWindow = findBestWindowForScreenshot()
        val configuredPackage = prefs.targetPackage.trim()

        // Bez ręcznego filtra robimy OCR tylko dla znanej aplikacji kurierskiej.
        if (screenshotWindow == null) {
            registerMiss()
            return
        }

        val windowPackage = screenshotWindow.root
            ?.packageName
            ?.toString()
            .orEmpty()

        if (
            configuredPackage.isNotBlank() &&
            windowPackage.isNotBlank() &&
            windowPackage != configuredPackage &&
            !isGalleryPackage(windowPackage)
        ) {
            registerMiss()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val waitMs = (minimumScreenshotGapMs - (now - lastScreenshotRequestAt))
            .coerceAtLeast(0L)

        if (waitMs > 0L) {
            scheduleThrottledScan(waitMs)
            return
        }

        ocrBusy = true
        lastScreenshotRequestAt = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: screenshot konkretnego okna. Overlay nie trafia do obrazu.
            takeScreenshotOfWindow(
                screenshotWindow.id,
                mainExecutor,
                screenshotCallback(
                    maskOverlay = false,
                    sourcePackageName = windowPackage,
                    cropBounds = null
                )
            )
        } else {
            // Android 11-13: screenshot całego ekranu. Przycinamy go do okna aplikacji
            // kurierskiej i maskujemy tylko obszar naszej nakładki.
            val windowBounds = Rect().also { screenshotWindow.getBoundsInScreen(it) }

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                screenshotCallback(
                    maskOverlay = true,
                    sourcePackageName = windowPackage,
                    cropBounds = windowBounds
                )
            )
        }
    }

    private fun screenshotCallback(
        maskOverlay: Boolean,
        sourcePackageName: String,
        cropBounds: Rect?
    ) = object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            val buffer = screenshot.hardwareBuffer
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            buffer.close()

            if (bitmap == null) {
                ocrBusy = false
                if (!scanAccessibilityOnly(hideOnFailure = false)) registerMiss()
                return
            }

            val maskBounds = if (maskOverlay) overlay.screenBounds() else null
            val prepared = prepareScreenshot(
                source = bitmap,
                maskBounds = maskBounds,
                cropBounds = cropBounds
            )
            bitmap.recycle()

            runOcr(prepared, sourcePackageName)
        }

        override fun onFailure(errorCode: Int) {
            ocrBusy = false

            if (scanAccessibilityOnly(hideOnFailure = false)) return

            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                scheduleThrottledScan(minimumScreenshotGapMs)
            } else {
                registerMiss()
            }
        }
    }

    private fun scheduleThrottledScan(delayMs: Long) {
        if (pendingThrottledScan != null) return

        pendingThrottledScan = Runnable {
            pendingThrottledScan = null
            scanVisibleScreen()
        }.also {
            handler.postDelayed(it, delayMs.coerceAtLeast(100L))
        }
    }

    private fun runOcr(
        bitmap: Bitmap,
        sourcePackageName: String
    ) {
        val scanner = recognizer
        if (scanner == null) {
            bitmap.recycle()
            ocrBusy = false
            return
        }

        scanner
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                // Zamiast polegać wyłącznie na result.text, składamy tekst z linii
                // posortowanych wg położenia na ekranie. To pomaga przy kartach ofert,
                // gdzie OCR czasem miesza kolejność elementów.
                val text = buildOcrText(result)
                val configuredPackage = prefs.targetPackage.trim()

                val recognizedName = recognizeCourier(sourcePackageName, text)
                    ?: if (
                        configuredPackage.isNotBlank() &&
                        sourcePackageName == configuredPackage
                    ) {
                        appLabel(sourcePackageName)
                    } else {
                        null
                    }

                if (recognizedName != null) {
                    val offer = OfferParser.parse(text)
                    if (offer != null) {
                        misses = 0
                        showOffer(
                            offer = offer,
                            applicationName = recognizedName,
                            packageName = if (isGalleryPackage(sourcePackageName)) {
                                ""
                            } else {
                                sourcePackageName
                            }
                        )
                        return@addOnSuccessListener
                    }
                }

                if (!scanAccessibilityOnly(hideOnFailure = false)) {
                    registerMiss()
                }
            }
            .addOnFailureListener {
                if (!scanAccessibilityOnly(hideOnFailure = false)) {
                    registerMiss()
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                ocrBusy = false
            }
    }

    private fun buildOcrText(result: Text): String {
        val lines = result.textBlocks
            .flatMap { it.lines }
            .map { line ->
                val bounds = line.boundingBox
                OcrLine(
                    text = line.text.trim(),
                    top = bounds?.top ?: Int.MAX_VALUE,
                    left = bounds?.left ?: Int.MAX_VALUE
                )
            }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })

        if (lines.isEmpty()) return result.text

        return lines.joinToString("\n") { it.text }
    }

    private fun prepareScreenshot(
        source: Bitmap,
        maskBounds: Rect?,
        cropBounds: Rect?
    ): Bitmap {
        val crop = validatedCrop(source, cropBounds)

        // Tworzymy niezależny bitmap, żeby bezpiecznie móc zwolnić screenshot źródłowy.
        val working = Bitmap.createBitmap(
            crop.width(),
            crop.height(),
            Bitmap.Config.ARGB_8888
        )

        Canvas(working).drawBitmap(
            source,
            -crop.left.toFloat(),
            -crop.top.toFloat(),
            null
        )

        maskBounds?.let { bounds ->
            val padding = dp(5)
            val left = (bounds.left - crop.left - padding).coerceIn(0, working.width)
            val top = (bounds.top - crop.top - padding).coerceIn(0, working.height)
            val right = (bounds.right - crop.left + padding).coerceIn(0, working.width)
            val bottom = (bounds.bottom - crop.top + padding).coerceIn(0, working.height)

            if (right > left && bottom > top) {
                Canvas(working).drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    right.toFloat(),
                    bottom.toFloat(),
                    Paint().apply { color = Color.BLACK }
                )
            }
        }

        // Nie ścinamy już każdego screenshotu do 1080 px. Na ekranach 1440p
        // zachowujemy więcej pikseli małych cyfr, co poprawia OCR.
        val maxWidth = 1440
        if (working.width <= maxWidth) return working

        val ratio = maxWidth.toFloat() / working.width.toFloat()
        val newHeight = (working.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(working, maxWidth, newHeight, true)
        working.recycle()
        return scaled
    }

    private fun validatedCrop(source: Bitmap, requested: Rect?): Rect {
        if (requested == null) {
            return Rect(0, 0, source.width, source.height)
        }

        val left = requested.left.coerceIn(0, source.width)
        val top = requested.top.coerceIn(0, source.height)
        val right = requested.right.coerceIn(left, source.width)
        val bottom = requested.bottom.coerceIn(top, source.height)

        if (right - left < 100 || bottom - top < 100) {
            return Rect(0, 0, source.width, source.height)
        }

        return Rect(left, top, right, bottom)
    }

    private fun findBestWindowForScreenshot(): AccessibilityWindowInfo? {
        val configuredPackage = prefs.targetPackage.trim()

        return windows
            .mapNotNull { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
                val pkg = root.packageName?.toString().orEmpty()

                if (pkg.isBlank() || pkg == packageName) return@mapNotNull null

                val gallery = isGalleryPackage(pkg)
                val allowed = if (configuredPackage.isNotBlank()) {
                    pkg == configuredPackage || gallery
                } else {
                    isDeliveryPackage(pkg) || gallery
                }

                if (!allowed) return@mapNotNull null

                var score = 0
                if (window.isActive) score += 5000
                if (window.isFocused) score += 3000
                if (pkg == configuredPackage && configuredPackage.isNotBlank()) score += 2000
                if (isDeliveryPackage(pkg)) score += 1000
                if (gallery) score += 800

                WindowCandidate(window, score)
            }
            .maxByOrNull { it.score }
            ?.window
    }

    private fun scanAccessibilityOnly(hideOnFailure: Boolean = true): Boolean {
        val configuredPackage = prefs.targetPackage.trim()
        val candidates = mutableListOf<AccessibilityCandidate>()

        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()

            if (pkg.isBlank() || pkg == packageName) continue
            if (configuredPackage.isNotBlank() && pkg != configuredPackage) continue
            if (configuredPackage.isBlank() && !isDeliveryPackage(pkg)) continue

            val text = buildAccessibilityText(root)
            val applicationName = recognizeCourier(pkg, text)

            if (configuredPackage.isBlank() && applicationName == null) continue

            val offer = OfferParser.parse(text) ?: continue

            var score = 0
            if (pkg == configuredPackage && configuredPackage.isNotBlank()) score += 2000
            if (isDeliveryPackage(pkg)) score += 1000
            if (window.isActive) score += 200
            if (window.isFocused) score += 200

            candidates += AccessibilityCandidate(
                packageName = pkg,
                applicationName = applicationName ?: appLabel(pkg),
                offer = offer,
                score = score
            )
        }

        val selected = candidates.maxByOrNull { it.score }
        if (selected != null) {
            misses = 0
            showOffer(
                offer = selected.offer,
                applicationName = selected.applicationName,
                packageName = selected.packageName
            )
            return true
        }

        val root = rootInActiveWindow
        if (root != null) {
            val pkg = root.packageName?.toString().orEmpty()
            val allowed = pkg.isNotBlank() &&
                pkg != packageName &&
                (configuredPackage.isNotBlank() && pkg == configuredPackage ||
                    configuredPackage.isBlank() && isDeliveryPackage(pkg))

            if (allowed) {
                val text = buildAccessibilityText(root)
                val applicationName = recognizeCourier(pkg, text)

                if (configuredPackage.isNotBlank() || applicationName != null) {
                    val offer = OfferParser.parse(text)
                    if (offer != null) {
                        misses = 0
                        showOffer(
                            offer = offer,
                            applicationName = applicationName ?: appLabel(pkg),
                            packageName = pkg
                        )
                        return true
                    }
                }
            }
        }

        if (hideOnFailure) registerMiss()
        return false
    }

    private fun buildAccessibilityText(root: AccessibilityNodeInfo): String {
        val items = mutableListOf<AccessibilityTextItem>()
        collectVisibleText(root, items, depth = 0)

        return items
            .distinctBy { item ->
                "${item.left}:${item.top}:${item.right}:${item.bottom}:${item.text}"
            }
            .sortedWith(
                compareBy<AccessibilityTextItem> { it.top }
                    .thenBy { it.left }
            )
            .joinToString("\n") { it.text }
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityTextItem>,
        depth: Int
    ) {
        if (depth > 60) return

        val visible = runCatching { node.isVisibleToUser }.getOrDefault(true)
        if (visible) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            addAccessibilityText(node.text?.toString(), bounds, out)
            addAccessibilityText(node.contentDescription?.toString(), bounds, out)
            addAccessibilityText(node.hintText?.toString(), bounds, out)
        }

        // Nawet jeśli rodzic nie jest oznaczony jako widoczny, jego dziecko może mieć
        // użyteczny tekst, dlatego nadal przechodzimy po potomkach.
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectVisibleText(child, out, depth + 1)
        }
    }

    private fun addAccessibilityText(
        raw: String?,
        bounds: Rect,
        out: MutableList<AccessibilityTextItem>
    ) {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return

        out += AccessibilityTextItem(
            text = value,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )
    }

    private fun showOffer(
        offer: Offer,
        applicationName: String,
        packageName: String = ""
    ) {
        val rules = ProfitabilityCalculator.Rules(
            vehicleCostPerKm = prefs.vehicleCostPerKm,
            minimumNetPerKm = prefs.minimumNetPerKm,
            minimumNetPerHour = prefs.minimumNetPerHour
        )

        val now = LocalTime.now()
        val currentMinuteOfDay = now.hour * 60 + now.minute
        val result = ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = currentMinuteOfDay
        )

        overlay.show(
            result = result,
            applicationName = applicationName,
            applicationIcon = resolveIcon(applicationName, packageName)
        )
    }

    private fun recognizeCourier(
        packageName: String,
        text: String
    ): String? {
        val lowerPackage = packageName.lowercase()
        val lowerText = text.lowercase()

        if (
            "uber" in lowerPackage ||
            "ubercab" in lowerPackage ||
            ("pln" in lowerText &&
                ("confirm" in lowerText || "delivery" in lowerText) &&
                "min" in lowerText)
        ) {
            return "Uber"
        }

        if (
            "pyszne" in lowerPackage ||
            "takeaway" in lowerPackage ||
            "justeat" in lowerPackage ||
            "zaakceptuj zlecenie" in lowerText ||
            ("odbierz na" in lowerText && "dostarcz na" in lowerText)
        ) {
            return "Pyszne.pl"
        }

        if ("wolt" in lowerPackage) return "Wolt"
        if ("glovo" in lowerPackage) return "Glovo"
        if ("bolt" in lowerPackage) return "Bolt Food"

        return null
    }

    private fun isDeliveryPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return "uber" in lower ||
            "ubercab" in lower ||
            "pyszne" in lower ||
            "takeaway" in lower ||
            "justeat" in lower ||
            "wolt" in lower ||
            "glovo" in lower ||
            "bolt" in lower
    }

    private fun isGalleryPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()

        if (
            "gallery" in lower ||
            "gallery3d" in lower ||
            "google.android.apps.photos" in lower ||
            "huawei.photos" in lower ||
            "miui.gallery" in lower ||
            "oplus.gallery" in lower ||
            "coloros.gallery" in lower ||
            "oneplus.gallery" in lower
        ) {
            return true
        }

        val label = appLabel(packageName).lowercase()
        return "galeria" in label ||
            "gallery" in label ||
            "zdjecia" in label ||
            "photos" in label
    }

    private fun resolveIcon(
        applicationName: String,
        packageName: String
    ): Drawable? {
        if (packageName.isNotBlank()) {
            runCatching { packageManager.getApplicationIcon(packageName) }
                .getOrNull()
                ?.let { return it }
        }

        if (applicationName == "Uber") {
            return runCatching { packageManager.getApplicationIcon("com.ubercab.driver") }
                .getOrNull()
        }

        return null
    }

    private fun appLabel(packageName: String): String =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("Dostawa")

    private fun registerMiss() {
        misses++
        if (misses >= 2 && ::overlay.isInitialized) overlay.hide()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class WindowCandidate(
        val window: AccessibilityWindowInfo,
        val score: Int
    )

    private data class AccessibilityCandidate(
        val packageName: String,
        val applicationName: String,
        val offer: Offer,
        val score: Int
    )

    private data class AccessibilityTextItem(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class OcrLine(
        val text: String,
        val top: Int,
        val left: Int
    )
}
