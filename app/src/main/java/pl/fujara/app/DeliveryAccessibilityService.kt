package pl.fujara.app

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
import kotlin.math.abs

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
    private var lastDeliverySignalAt = 0L
    private var lastDeliveryPackage = ""

    private val scanIntervalMs = 1200L
    private val minimumScreenshotGapMs = 700L
    private val deliverySignalKeepAliveMs = 20_000L

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

        if (
            eventPackage == configuredPackage && configuredPackage.isNotBlank() ||
            isDeliveryPackage(eventPackage)
        ) {
            lastDeliverySignalAt = SystemClock.elapsedRealtime()
            lastDeliveryPackage = eventPackage
        } else if (
            configuredPackage.isBlank() &&
            !isGalleryPackage(eventPackage) &&
            !::overlay.isInitialized
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!scanAccessibilityOnly(hideOnFailure = false)) registerMiss()
            return
        }

        val deliveryWindow = findBestWindowForScreenshot()
        val now = SystemClock.elapsedRealtime()
        val recentDeliverySignal = now - lastDeliverySignalAt <= deliverySignalKeepAliveMs
        val overlayVisible = ::overlay.isInitialized && overlay.isVisible()
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()

        // Nie OCR-ujemy czatu, przegladarki itp. tylko dlatego, ze kilka sekund
        // temu byla otwarta aplikacja kurierska. Launcher/system UI jest wyjatkiem,
        // bo Uber potrafi pokazac plywajaca karte nad ekranem glownym.
        if (
            deliveryWindow == null &&
            activePackage.isNotBlank() &&
            !isDeliveryPackage(activePackage) &&
            !isGalleryPackage(activePackage) &&
            !isHomeOrSystemPackage(activePackage)
        ) {
            lastDeliverySignalAt = 0L
            lastDeliveryPackage = ""
            if (overlayVisible) overlay.hide()
            return
        }

        /*
         * Na Androidzie 11+ robimy OCR z CALEGO aktualnie widocznego ekranu,
         * ale tylko wtedy, gdy mamy sygnal z aplikacji kurierskiej, jej okno jest
         * widoczne albo nasza nakladka nadal pokazuje ostatnia oferte.
         *
         * To naprawia przypadek Ubera, w ktorym karta oferty jest wyswietlana nad
         * ekranem glownym telefonu. takeScreenshotOfWindow() potrafil wtedy zrobic
         * zrzut launchera bez samej karty. Zrzut calego displayu widzi to, co user.
         */
        if (deliveryWindow == null && !recentDeliverySignal && !overlayVisible) {
            if (!scanAccessibilityOnly(hideOnFailure = false)) registerMiss()
            return
        }

        val waitMs = (minimumScreenshotGapMs - (now - lastScreenshotRequestAt))
            .coerceAtLeast(0L)

        if (waitMs > 0L) {
            scheduleThrottledScan(waitMs)
            return
        }

        val windowPackage = deliveryWindow
            ?.root
            ?.packageName
            ?.toString()
            .orEmpty()
            .ifBlank { lastDeliveryPackage }

        ocrBusy = true
        lastScreenshotRequestAt = now

        // Celowo screenshot calego displayu na wszystkich wersjach Androida 11+.
        // Nasza nakladka jest maskowana przed OCR, wiec nie czytamy wlasnych liczb.
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            screenshotCallback(
                maskOverlay = true,
                sourcePackageName = windowPackage,
                cropBounds = null
            )
        )
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
                val configuredPackage = prefs.targetPackage.trim()
                val recognitionText = OcrTextResolver.recognitionText(result)
                val packagePlatform = platformForPackage(sourcePackageName)
                val inferredPlatform = inferPlatformFromText(recognitionText)

                // Tekst karty ma pierwszenstwo przed zapamietanym package name. To jest
                // wazne przy plywajacych kartach i launcherze: poprzednio po Uberze
                // mogl zostac stary sygnal i ekran Wolt byl opisany jako Uber.
                val platform = inferredPlatform ?: packagePlatform
                val resolved = OcrTextResolver.resolve(result, platform)

                val recognizedName = platform?.displayName
                    ?: if (
                        configuredPackage.isNotBlank() &&
                        sourcePackageName == configuredPackage
                    ) {
                        appLabel(sourcePackageName)
                    } else {
                        null
                    }

                if (recognizedName != null && resolved != null) {
                    misses = 0
                    lastDeliverySignalAt = SystemClock.elapsedRealtime()
                    if (
                        sourcePackageName.isNotBlank() &&
                        packagePlatform != null &&
                        packagePlatform == platform
                    ) {
                        lastDeliveryPackage = sourcePackageName
                    }

                    val offer = enrichPyszneScheduleFromAccessibility(
                        offer = resolved.offer,
                        platform = platform,
                        sourcePackageName = sourcePackageName
                    )

                    showOffer(
                        offer = offer,
                        applicationName = recognizedName,
                        packageName = if (
                            isGalleryPackage(sourcePackageName) ||
                            packagePlatform == null ||
                            packagePlatform != platform
                        ) {
                            ""
                        } else {
                            sourcePackageName
                        }
                    )
                    return@addOnSuccessListener
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
            val platform = platformForPackage(pkg)
            val applicationName = platform?.displayName
                ?: if (configuredPackage.isNotBlank() && pkg == configuredPackage) appLabel(pkg) else null

            if (configuredPackage.isBlank() && applicationName == null) continue

            val offer = OfferParser.parse(text, platform) ?: continue

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
                val platform = platformForPackage(pkg)
                val applicationName = platform?.displayName
                    ?: if (configuredPackage.isNotBlank() && pkg == configuredPackage) appLabel(pkg) else null

                if (configuredPackage.isNotBlank() || applicationName != null) {
                    val offer = OfferParser.parse(text, platform)
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

    /**
     * Na ekranie Pyszne duza kwota i dystans sa latwe dla OCR, ale mala linia
     * "Dostarcz na HH:mm" czasem wypada. Jesli OCR znalazl oferte bez godziny
     * dostawy, probujemy uzupelnic SAM harmonogram z drzewa Accessibility.
     *
     * Laczymy dane tylko wtedy, gdy kwota i dystans pasuja do tej samej oferty,
     * zeby nie podpiac godziny ze starej / innej karty.
     */
    private fun enrichPyszneScheduleFromAccessibility(
        offer: Offer,
        platform: CourierPlatform?,
        sourcePackageName: String
    ): Offer {
        if (platform != CourierPlatform.PYSZNE) return offer
        if (offer.deliveryTimeMinutesOfDay != null) return offer

        val candidates = windows.mapNotNull { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == packageName) return@mapNotNull null
            if (platformForPackage(pkg) != CourierPlatform.PYSZNE) return@mapNotNull null

            val parsed = OfferParser.parse(
                buildAccessibilityText(root),
                CourierPlatform.PYSZNE
            ) ?: return@mapNotNull null

            if (parsed.deliveryTimeMinutesOfDay == null) return@mapNotNull null
            if (abs(parsed.amountPln - offer.amountPln) > 0.25) return@mapNotNull null
            if (abs(parsed.distanceKm - offer.distanceKm) > 0.25) return@mapNotNull null

            val score =
                (if (pkg == sourcePackageName && sourcePackageName.isNotBlank()) 100 else 0) +
                    (if (window.isActive) 20 else 0) +
                    (if (window.isFocused) 20 else 0)

            parsed to score
        }

        val schedule = candidates.maxByOrNull { it.second }?.first ?: return offer
        val deliveryTime = schedule.deliveryTimeMinutesOfDay ?: return offer

        return offer.copy(
            // Pyszne zawsze liczymy od aktualnego czasu telefonu do "Dostarcz na".
            durationMinutes = null,
            pickupTimeMinutesOfDay =
                schedule.pickupTimeMinutesOfDay ?: offer.pickupTimeMinutesOfDay,
            deliveryTimeMinutesOfDay = deliveryTime
        )
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
        val rules = prefs.rulesForCourier(applicationName)

        val now = LocalTime.now()
        val currentMinuteOfDay = now.hour * 60 + now.minute
        val result = ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = currentMinuteOfDay,
            decisionBasis = prefs.decisionBasis
        )

        overlay.show(
            result = result,
            applicationName = applicationName,
            applicationIcon = resolveIcon(applicationName, packageName)
        )
    }

    private fun platformForPackage(packageName: String): CourierPlatform? {
        if (packageName.isBlank()) return null

        val lower = packageName.lowercase()
        val fromPackage = when {
            "uber" in lower || "ubercab" in lower -> CourierPlatform.UBER
            "wolt" in lower -> CourierPlatform.WOLT
            "glovo" in lower -> CourierPlatform.GLOVO
            "bolt" in lower -> CourierPlatform.BOLT
            "pyszne" in lower || "takeaway" in lower || "justeat" in lower -> CourierPlatform.PYSZNE
            "stuart" in lower -> CourierPlatform.STUART
            else -> null
        }
        if (fromPackage != null) return fromPackage

        // Część producentów / wariantów regionalnych używa package name bez nazwy
        // marki. Wtedy bezpiecznie sprawdzamy etykietę zainstalowanej aplikacji.
        val fromLabel = runCatching { CourierPlatform.fromDisplayName(appLabel(packageName)) }
            .getOrNull()
        return fromLabel?.takeUnless { it == CourierPlatform.GLOBAL }
    }

    private fun inferPlatformFromText(text: String): CourierPlatform? {
        val lower = text.lowercase()
        return when {
            "estimated earnings" in lower && (" mi total" in lower || "stuart" in lower) -> CourierPlatform.STUART
            "expected earnings for the full delivery" in lower -> CourierPlatform.WOLT
            "spodziewany zarobek" in lower || ("całkowita kwota reszty" in lower && "akceptuj" in lower) -> CourierPlatform.WOLT
            "route distance" in lower && "estimated" in lower && "accept" in lower && "pln" in lower -> CourierPlatform.WOLT
            "zaakceptuj zlecenie" in lower || ("odbierz na" in lower && "dostarcz na" in lower) -> CourierPlatform.PYSZNE
            "bolt food" in lower || (Regex("""\d+[.,]?\d*\s*km\s*[,·|]\s*\d+\s*min\s*[,·|]\s*\d+[.,]\d{1,2}\s*z[łl]""", RegexOption.IGNORE_CASE).containsMatchIn(text)) -> CourierPlatform.BOLT
            "delivery" in lower && "confirm" in lower && "pln" in lower -> CourierPlatform.UBER
            "wolt" in lower -> CourierPlatform.WOLT
            "glovo" in lower -> CourierPlatform.GLOVO
            else -> null
        }
    }

    private fun isDeliveryPackage(packageName: String): Boolean = platformForPackage(packageName) != null

    private val CourierPlatform.displayName: String
        get() = when (this) {
            CourierPlatform.GLOBAL -> "Dostawa"
            CourierPlatform.UBER -> "Uber"
            CourierPlatform.WOLT -> "Wolt"
            CourierPlatform.GLOVO -> "Glovo"
            CourierPlatform.BOLT -> "Bolt Food"
            CourierPlatform.PYSZNE -> "Pyszne.pl"
            CourierPlatform.STUART -> "Stuart"
        }

    private fun isHomeOrSystemPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if ("systemui" in lower || lower == "android") return true
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val homePackage = packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        return homePackage != null && packageName == homePackage
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

        // Nakladka nie ma juz sztucznego timera. Jest widoczna tak dlugo, jak
        // wykrywamy karte oferty. Dwa kolejne pudla daja niewielka tolerancje na
        // pojedyncza klatke OCR, ale po zniknieciu oferty panel sam znika.
        if (misses >= 2 && ::overlay.isInitialized) {
            overlay.hide()
        }
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
