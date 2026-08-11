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

        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage == packageName) return

        val configuredPackage = prefs.targetPackage.trim()
        if (configuredPackage.isNotBlank() && eventPackage != configuredPackage) return

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

        // Najpierw próbujemy Accessibility - jest szybsze i nie wymaga screenshotu.
        if (scanAccessibilityOnly(hideOnFailure = false)) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            registerMiss()
            return
        }

        val screenshotWindow = findBestWindowForScreenshot()
        val configuredPackage = prefs.targetPackage.trim()

        // Jeżeli użytkownik wskazał konkretny pakiet, nie analizujemy innych aplikacji.
        if (configuredPackage.isNotBlank() && screenshotWindow == null) {
            registerMiss()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val waitMs = (minimumScreenshotGapMs - (now - lastScreenshotRequestAt)).coerceAtLeast(0L)
        if (waitMs > 0L) {
            scheduleThrottledScan(waitMs)
            return
        }

        ocrBusy = true
        lastScreenshotRequestAt = now

        val windowPackage = screenshotWindow?.root
            ?.packageName
            ?.toString()
            .orEmpty()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && screenshotWindow != null) {
            // Android 14+: screenshot konkretnego okna nie zawiera naszego accessibility overlayu.
            takeScreenshotOfWindow(
                screenshotWindow.id,
                mainExecutor,
                screenshotCallback(
                    maskOverlay = false,
                    sourcePackageName = windowPackage
                )
            )
        } else {
            // Android 11-13: screenshot całego ekranu, ale maskujemy wyłącznie rzeczywisty obszar overlayu.
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                screenshotCallback(
                    maskOverlay = true,
                    sourcePackageName = windowPackage
                )
            )
        }
    }

    private fun screenshotCallback(
        maskOverlay: Boolean,
        sourcePackageName: String
    ) = object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            val buffer = screenshot.hardwareBuffer
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
            buffer.close()

            if (bitmap == null) {
                ocrBusy = false
                if (!scanAccessibilityOnly(hideOnFailure = false)) registerMiss()
                return
            }

            val maskBounds = if (maskOverlay) overlay.screenBounds() else null
            val prepared = prepareScreenshot(bitmap, maskBounds)
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
                val text = result.text
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
                            packageName = sourcePackageName
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

    private fun prepareScreenshot(
        source: Bitmap,
        maskBounds: Rect?
    ): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)

        maskBounds?.let { bounds ->
            val padding = dp(4)
            val left = (bounds.left - padding).coerceIn(0, mutable.width)
            val top = (bounds.top - padding).coerceIn(0, mutable.height)
            val right = (bounds.right + padding).coerceIn(0, mutable.width)
            val bottom = (bounds.bottom + padding).coerceIn(0, mutable.height)

            if (right > left && bottom > top) {
                Canvas(mutable).drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    right.toFloat(),
                    bottom.toFloat(),
                    Paint().apply { color = Color.BLACK }
                )
            }
        }

        val maxWidth = 1080
        if (mutable.width <= maxWidth) return mutable

        val ratio = maxWidth.toFloat() / mutable.width.toFloat()
        val newHeight = (mutable.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(mutable, maxWidth, newHeight, true)
        mutable.recycle()
        return scaled
    }

    private fun findBestWindowForScreenshot(): AccessibilityWindowInfo? {
        val configuredPackage = prefs.targetPackage.trim()

        return windows
            .mapNotNull { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
                val pkg = root.packageName?.toString().orEmpty()

                if (pkg.isBlank() || pkg == packageName) return@mapNotNull null
                if (configuredPackage.isNotBlank() && pkg != configuredPackage) return@mapNotNull null

                if (configuredPackage.isBlank() && !isDeliveryPackage(pkg)) return@mapNotNull null

                var score = 0
                if (pkg == configuredPackage && configuredPackage.isNotBlank()) score += 2000
                if (isDeliveryPackage(pkg)) score += 1000
                if (window.isActive) score += 200
                if (window.isFocused) score += 200

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

            val text = buildString { collectText(root, this, 0) }
            val applicationName = recognizeCourier(pkg, text)

            // Bez ręcznego filtra analizujemy tylko rozpoznane aplikacje kurierskie.
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
                (configuredPackage.isBlank() || pkg == configuredPackage)

            if (allowed) {
                val text = buildString { collectText(root, this, 0) }
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
            ("pln" in lowerText && ("confirm" in lowerText || "delivery" in lowerText) && "min" in lowerText)
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

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int
    ) {
        if (depth > 60) return

        node.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { out.append(it).append('\n') }

        node.contentDescription
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { out.append(it).append('\n') }

        node.hintText
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { out.append(it).append('\n') }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectText(child, out, depth + 1)
        }
    }

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
}
