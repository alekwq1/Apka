package pl.fujara.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
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
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

class DeliveryAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private lateinit var prefs: AppPrefs
    private lateinit var pyszneLogStore: PyszneLogStore

    // 1.1: asystent decyzji
    private lateinit var offerEventStore: OfferEventStore
    private lateinit var woltEarningsStore: WoltEarningsStore
    private lateinit var woltOrderStore: WoltOrderStore
    private lateinit var routeAdvisor: RouteAdvisor
    private var voice: VoiceRejectController? = null
    private val decisionTracker = DecisionTracker()
    private val activeJobTracker = ActiveJobTracker()
    private var voiceState: VoiceState = VoiceState.OFF
    private var voiceHint: String? = null
    private var lastOfferSignature: String? = null
    private var lastRouteAdvice: RouteAdvice? = null
    private var lastRouteSignature: String? = null
    private var decisionCounterCache: Pair<Long, String?> = 0L to null
    private var lastAutoSavedKey: String? = null
    private val decisionTickRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            decisionTracker.tick(now)
            persistDecisions()
            if (::prefs.isInitialized && prefs.activeJobsResetRequestedAt > lastHandledResetAt) {
                lastHandledResetAt = prefs.activeJobsResetRequestedAt
                activeJobTracker.clear()
                persistActiveJobs()
                lastRouteAdvice = null
                lastRouteSignature = null
            }
            if (::prefs.isInitialized) runCatching { updateStatusNotification(prefs.privacyAccepted && prefs.analysisEnabled) }
            handler.postDelayed(this, 5_000L)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: TextRecognizer? = null
    private var ocrBusy = false
    private var misses = 0
    private var pendingImmediateScan: Runnable? = null
    private var pendingThrottledScan: Runnable? = null
    private var lastScreenshotRequestAt = 0L
    private var lastDeliverySignalAt = 0L
    private var lastDeliveryPackage = ""
    private var lastNotificationActive: Boolean? = null
    private var lastNotificationLanguage: String? = null
    private var lastNotificationWork: String? = null
    private lateinit var workSessionStore: WorkSessionStore
    private lateinit var activeJobStore: ActiveJobStore
    private val woltExpandStartedAt = java.util.LinkedHashMap<String, Long>()
    private val woltCollapsedSignatures = java.util.LinkedHashSet<String>()
    private val woltDetailsBySignature = object : java.util.LinkedHashMap<String, OfferDetails>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OfferDetails>?): Boolean = size > 30
    }
    private var lastOfferDecisionAt = 0L
    private var lastHandledResetAt = 0L
    private var activePyszneDayDate: LocalDate? = null
    private var activePyszneDaySeenAt = 0L
    // OCR numerow z listy jest fallbackiem. Numer musi zostac zobaczony co najmniej
    // dwa razy, zanim trafi do listy brakow; Accessibility jest traktowane jako zrodlo zaufane.
    private var pendingOcrDayIdsDate: LocalDate? = null
    private val pendingOcrDayIdHits = mutableMapOf<String, Int>()
    private val pyszneDisplayGate = PyszneDisplayGate()
    private var lastPyszneOverlayWasHistoryDetails = false

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
        pyszneLogStore = PyszneLogStore(this)
        overlay = OverlayController(this)
        offerEventStore = OfferEventStore(this)
        woltEarningsStore = WoltEarningsStore(this)
        woltOrderStore = WoltOrderStore(this)
        workSessionStore = WorkSessionStore(this)
        activeJobStore = ActiveJobStore(this)
        activeJobTracker.restore(activeJobStore.load(), System.currentTimeMillis())
        lastHandledResetAt = prefs.activeJobsResetRequestedAt
        routeAdvisor = RouteAdvisor(this, prefs)
        voice = VoiceRejectController(
            service = this,
            prefs = prefs,
            onStateChanged = { state, hint ->
                voiceState = state
                voiceHint = hint
                if (::overlay.isInitialized && overlay.isVisible()) {
                    overlay.updateExtras(overlay.currentExtras().copy(voiceState = state, voiceHint = hint, voiceKeyword = prefs.voiceRejectKeyword))
                }
            },
            onConfirmedReject = { performVoiceReject() }
        )
        // Klikniecia Akceptuj/Odrzuc sa potrzebne do statystyk decyzji.
        runCatching {
            serviceInfo = serviceInfo?.apply {
                eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_CLICKED
            }
        }
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        updateStatusNotification(prefs.privacyAccepted && prefs.analysisEnabled)
        handler.postDelayed(pollRunnable, 600L)
        handler.postDelayed(decisionTickRunnable, 5_000L)
        if (routeAdvisor.enabled) routeAdvisor.startLocationUpdates()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (!prefs.privacyAccepted || !prefs.analysisEnabled) {
            updateStatusNotification(false)
            if (::overlay.isInitialized) overlay.hide()
            return
        }
        updateStatusNotification(true)

        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage == packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isDeliveryPackage(eventPackage)) {
            handleDeliveryClick(event, platformForPackage(eventPackage))
        }

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

        // Pyszne potrafi przez ulamek sekundy wyswietlac dane poprzedniego
        // zlecenia po kliknieciu w kolejna pozycje. Chowamy nakladke od razu po
        // przejsciu i blokujemy ostatnie ID do czasu stabilnego odczytu nowego.
        if (
            platformForPackage(eventPackage) == CourierPlatform.PYSZNE &&
            lastPyszneOverlayWasHistoryDetails &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ) {
            pyszneDisplayGate.beginDirectTransition(SystemClock.elapsedRealtime())
            lastPyszneOverlayWasHistoryDetails = false
            if (::overlay.isInitialized) overlay.hide()
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
        handler.removeCallbacks(decisionTickRunnable)
        runCatching { decisionTracker.offerNotVisible(System.currentTimeMillis()); persistDecisions(); persistActiveJobs() }
        voice?.destroy()
        voice = null
        if (::routeAdvisor.isInitialized) routeAdvisor.shutdown()
        pendingImmediateScan?.let { handler.removeCallbacks(it) }
        pendingThrottledScan?.let { handler.removeCallbacks(it) }
        recognizer?.close()
        recognizer = null
        if (::overlay.isInitialized) overlay.destroy()
        runCatching { getSystemService(NotificationManager::class.java).cancel(STATUS_NOTIFICATION_ID) }
        super.onDestroy()
    }

    private fun scanVisibleScreen() {
        if (ocrBusy) return

        if (!prefs.privacyAccepted || !prefs.analysisEnabled) {
            updateStatusNotification(false)
            if (::overlay.isInitialized) overlay.hide()
            return
        }
        updateStatusNotification(true)

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
            if (overlayVisible) {
                overlay.hide()
                onOfferGone()
            }
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
                val pyszneAccessibilityText = if (
                    packagePlatform == CourierPlatform.PYSZNE && !isGalleryPackage(sourcePackageName)
                ) {
                    accessibilityTextForPackage(sourcePackageName)
                } else {
                    ""
                }
                if (packagePlatform == CourierPlatform.WOLT && !isGalleryPackage(sourcePackageName)) {
                    captureWoltData(accessibilityTextForPackage(sourcePackageName), recognitionText)
                }

                // Gdy user wejdzie w Pyszne -> Podsumowanie dnia, preferujemy tekst
                // z drzewa Accessibility, a OCR jest fallbackiem. Parser wymaga teraz
                // jawnej daty oraz count+kwoty z tej samej gornej karty dnia.
                if (packagePlatform == CourierPlatform.PYSZNE && !isGalleryPackage(sourcePackageName)) {
                    capturePyszneDayData(pyszneAccessibilityText, recognitionText)
                }

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

                if (platform == CourierPlatform.PYSZNE) {
                    val independentTexts = listOf(
                        pyszneAccessibilityText,
                        resolved?.text.orEmpty(),
                        recognitionText
                    )
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()

                    val joinedForState = independentTexts.joinToString("\n")
                    if (isPyszneNavigationScreen(joinedForState)) {
                        pyszneDisplayGate.noteNavigationScreen(SystemClock.elapsedRealtime())
                        lastPyszneOverlayWasHistoryDetails = false
                        if (::overlay.isInitialized) overlay.hide()
                    }

                    // Nie laczymy bezwarunkowo Accessibility i OCR na ekranie
                    // szczegolow. Podczas przejscia jedno zrodlo moze juz widziec
                    // nowe zlecenie, a drugie jeszcze stare. Taki miks byl glowna
                    // przyczyna sekundowego migniecia starymi danymi.
                    val historyResolution = resolvePyszneHistory(
                        accessibilityText = pyszneAccessibilityText,
                        fallbackTexts = independentTexts.filterNot { it == pyszneAccessibilityText.trim() }
                    )
                    if (historyResolution.detailsDetected) {
                        val candidate = historyResolution.candidate
                        if (historyResolution.conflict || candidate == null || recognizedName == null) {
                            pyszneDisplayGate.noteConflict()
                            lastPyszneOverlayWasHistoryDetails = false
                            misses = 0
                            if (::overlay.isInitialized) overlay.hide()
                            return@addOnSuccessListener
                        }

                        misses = 0
                        lastDeliverySignalAt = SystemClock.elapsedRealtime()
                        if (sourcePackageName.isNotBlank() && packagePlatform == CourierPlatform.PYSZNE) {
                            lastDeliveryPackage = sourcePackageName
                        }

                        showOffer(
                            offer = candidate.offer,
                            applicationName = recognizedName,
                            sourceText = candidate.sourceText,
                            packageName = if (
                                isGalleryPackage(sourcePackageName) ||
                                packagePlatform != CourierPlatform.PYSZNE
                            ) "" else sourcePackageName
                        )
                        return@addOnSuccessListener
                    }
                }

                // Dla zwyklej, biezacej oferty Pyszne nadal mozemy laczyc zrodla,
                // bo nie ma tam ryzyka zapisania danych poprzedniego zlecenia.
                val pyszneSourceText = if (platform == CourierPlatform.PYSZNE) {
                    listOf(pyszneAccessibilityText, resolved?.text ?: recognitionText)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("\n")
                } else {
                    recognitionText
                }
                val pyszneFallbackOffer = if (platform == CourierPlatform.PYSZNE) {
                    OfferParser.parse(pyszneSourceText, CourierPlatform.PYSZNE)
                } else {
                    null
                }
                val finalOffer = pyszneFallbackOffer ?: resolved?.offer

                if (recognizedName != null && finalOffer != null) {
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
                        offer = finalOffer,
                        platform = platform,
                        sourcePackageName = sourcePackageName
                    )

                    showOffer(
                        offer = offer,
                        applicationName = recognizedName,
                        sourceText = if (platform == CourierPlatform.PYSZNE) pyszneSourceText else (resolved?.text ?: recognitionText),
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

    private fun accessibilityTextForPackage(packageName: String): String {
        if (packageName.isBlank()) return ""

        val windowText = windows
            .asSequence()
            .mapNotNull { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
                if (root.packageName?.toString() != packageName) return@mapNotNull null
                val score = (if (window.isActive) 2 else 0) + (if (window.isFocused) 1 else 0)
                score to buildAccessibilityText(root)
            }
            .filter { it.second.isNotBlank() }
            .maxByOrNull { it.first }
            ?.second

        if (!windowText.isNullOrBlank()) return windowText

        val root = rootInActiveWindow ?: return ""
        if (root.packageName?.toString() != packageName) return ""
        return buildAccessibilityText(root)
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
            if (platform == CourierPlatform.PYSZNE) {
                capturePyszneDayData(text)
                if (isPyszneNavigationScreen(text)) {
                    pyszneDisplayGate.noteNavigationScreen(SystemClock.elapsedRealtime())
                    lastPyszneOverlayWasHistoryDetails = false
                    if (::overlay.isInitialized) overlay.hide()
                }
            }
            if (platform == CourierPlatform.WOLT) captureWoltData(text, "")
            if (platform != null) noteActiveJobScreen(platform, text)
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
                sourceText = text,
                score = score
            )
        }

        val selected = candidates.maxByOrNull { it.score }
        if (selected != null) {
            misses = 0
            showOffer(
                offer = selected.offer,
                applicationName = selected.applicationName,
                packageName = selected.packageName,
                sourceText = selected.sourceText
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
                if (platform == CourierPlatform.PYSZNE) {
                    capturePyszneDayData(text)
                    if (isPyszneNavigationScreen(text)) {
                        pyszneDisplayGate.noteNavigationScreen(SystemClock.elapsedRealtime())
                        lastPyszneOverlayWasHistoryDetails = false
                        if (::overlay.isInitialized) overlay.hide()
                    }
                }
                val applicationName = platform?.displayName
                    ?: if (configuredPackage.isNotBlank() && pkg == configuredPackage) appLabel(pkg) else null

                if (configuredPackage.isNotBlank() || applicationName != null) {
                    val offer = OfferParser.parse(text, platform)
                    if (offer != null) {
                        misses = 0
                        showOffer(
                            offer = offer,
                            applicationName = applicationName ?: appLabel(pkg),
                            packageName = pkg,
                            sourceText = text
                        )
                        return true
                    }
                }
            }
        }

        if (hideOnFailure) registerMiss()
        return false
    }

    /**
     * Zapamietuje naglowek dnia (data + liczba + kwota), a podczas przewijania
     * tej samej listy dopisuje widoczne numery #XXXXXX. Dzieki temu ekran FUJARA
     * moze powiedziec dokladnie, ktorych zlecen jeszcze nie zapisano.
     */
    private fun capturePyszneDayData(accessibilityText: String, ocrText: String = "") {
        val accessibility = accessibilityText.trim()
        val ocr = ocrText.trim()
        if (accessibility.isBlank() && ocr.isBlank()) return

        // Returning to the week must end the previous day's list session.
        if (PyszneDayReferenceParser.isWeeklySummary(accessibility) || PyszneDayReferenceParser.isWeeklySummary(ocr)) {
            activePyszneDayDate = null
            activePyszneDaySeenAt = 0L
            pendingOcrDayIdsDate = null
            pendingOcrDayIdHits.clear()
            return
        }

        // Accessibility ma pierwszenstwo. OCR jest fallbackiem, ale nigdy nie
        // nadpisuje poprawnego odczytu Accessibility kwota z pierwszej dostawy.
        val accessibilityReference = accessibility.takeIf { it.isNotBlank() }
            ?.let(PyszneDayReferenceParser::parse)
        val ocrReference = if (accessibilityReference == null) {
            ocr.takeIf { it.isNotBlank() }?.let(PyszneDayReferenceParser::parse)
        } else {
            null
        }
        // Numery ID z OCR nie trafiaja do magazynu razem z naglowkiem dnia.
        // Musza przejsc osobna stabilizacje ponizej. Accessibility moze zapisac
        // swoje numery od razu, bo sa tekstem UI, a nie rozpoznaniem obrazu.
        val reference = accessibilityReference ?: ocrReference?.copy(orderIds = emptyList())

        if (reference != null) {
            pyszneLogStore.saveDayReference(reference)
            if (activePyszneDayDate != reference.date) {
                pendingOcrDayIdsDate = reference.date
                pendingOcrDayIdHits.clear()
            }
            activePyszneDayDate = reference.date
            activePyszneDaySeenAt = SystemClock.elapsedRealtime()
        }

        // Nie zgadujemy aktywnego dnia na podstawie ostatniego zapisu z dysku.
        // Zanim zaczniemy zbierac numery z przewijanej listy, naglowek tego dnia
        // musi zostac faktycznie zobaczony w tej sesji.
        val activeDate = activePyszneDayDate ?: return
        if (SystemClock.elapsedRealtime() - activePyszneDaySeenAt > 10 * 60_000L) return

        val combined = listOf(accessibility, ocr).filter { it.isNotBlank() }.joinToString("\n")
        val isHistoryList = Regex("""(?i)(?:historia\s+przychod[oó]w|earnings\s+history)""").containsMatchIn(combined)
        val isOrderDetails = Regex("""(?i)(?:szczeg[oó][lł]y\s+zlecenia|order\s+details)""").containsMatchIn(combined)
        if (!isHistoryList || isOrderDetails) return

        // Jesli podczas przewijania widac date, musi to byc ten sam dzien.
        val explicitDates = PyszneHistoryParser.parseDatesFromText(combined).distinct()
        if (explicitDates.size > 1 || (explicitDates.size == 1 && explicitDates.single() != activeDate)) return

        val trustedIds = if (accessibility.isNotBlank()) {
            PyszneDayReferenceParser.parseOrderIds(accessibility)
        } else {
            emptyList()
        }

        val idsToPersist = if (trustedIds.isNotEmpty()) {
            trustedIds
        } else {
            stableOcrDayIds(activeDate, PyszneDayReferenceParser.parseOrderIds(ocr))
        }

        if (idsToPersist.isNotEmpty()) {
            pyszneLogStore.mergeDayOrderIds(activeDate, idsToPersist)
            activePyszneDaySeenAt = SystemClock.elapsedRealtime()
        }
    }

    private fun stableOcrDayIds(date: LocalDate, ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        if (pendingOcrDayIdsDate != date) {
            pendingOcrDayIdsDate = date
            pendingOcrDayIdHits.clear()
        }
        ids.distinct().forEach { id ->
            pendingOcrDayIdHits[id] = (pendingOcrDayIdHits[id] ?: 0) + 1
        }
        return ids.distinct().filter { (pendingOcrDayIdHits[it] ?: 0) >= 2 }
    }

    /**
     * Rozpoznaje ekran historii/listy Pyszne. Na takim ekranie chowamy panel i
     * uzbrajamy ponownie stabilizator przed otwarciem kolejnego zlecenia.
     */
    private fun isPyszneNavigationScreen(text: String): Boolean {
        val lower = text.lowercase()
        val navigation =
            "historia przychodów" in lower ||
                "historia przychodow" in lower ||
                "earnings history" in lower ||
                "podsumowanie dnia" in lower ||
                "daily summary" in lower ||
                "day summary" in lower
        return navigation && !isPyszneHistoryDetailsScreen(text)
    }

    private fun isPyszneHistoryDetailsScreen(text: String): Boolean {
        val lower = text.lowercase()
        val detailsMarker =
            "szczegóły zlecenia" in lower ||
                "szczegoly zlecenia" in lower ||
                "order details" in lower
        val completedMarker =
            "dostarczone" in lower || "delivered" in lower ||
                "anulowane" in lower || "cancelled" in lower || "canceled" in lower
        val metricsMarker =
            (("czas aktywności" in lower || "czas aktywnosci" in lower || "active time" in lower) &&
                ("szacowana odległość" in lower || "szacowana odleglosc" in lower || "estimated distance" in lower))
        return detailsMarker || (completedMarker && metricsMarker)
    }

    /**
     * Na szczegolach Pyszne OCR i Accessibility sa analizowane OSOBNO.
     * Jesli widza dwa rozne numery zlecenia, uznajemy ekran za przejsciowy i
     * niczego nie pokazujemy. Kandydat musi miec jawna date i ID zlecenia.
     */
    private fun resolvePyszneHistory(
        accessibilityText: String,
        fallbackTexts: List<String>
    ): PyszneHistoryResolution {
        fun candidateFrom(text: String): PyszneHistoryCandidate? {
            val clean = text.trim()
            if (clean.isBlank() || !isPyszneHistoryDetailsScreen(clean)) return null
            // Zlecenie moze zaczac sie przed polnoca i skonczyc po polnocy.
            // Taki ekran ma dwie sasiednie daty i jest poprawny. Wczesniej
            // wymaganie dokladnie jednej daty chowalo panel na zawsze.
            if (!PyszneHistoryParser.hasCoherentOrderDetailDates(clean)) return null
            val offer = OfferParser.parse(clean, CourierPlatform.PYSZNE) ?: return null
            val entry = PyszneHistoryParser.parse(sourceText = clean, offer = offer) ?: return null
            val orderId = entry.orderId?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            return PyszneHistoryCandidate(
                identity = orderId,
                offer = offer,
                entry = entry,
                sourceText = clean,
                score = historyCandidateScore(entry, clean)
            )
        }

        val accessibilityClean = accessibilityText.trim()
        val fallbackClean = fallbackTexts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val detailsDetected = (accessibilityClean.isNotBlank() && isPyszneHistoryDetailsScreen(accessibilityClean)) ||
            fallbackClean.any(::isPyszneHistoryDetailsScreen)
        if (!detailsDetected) return PyszneHistoryResolution(detailsDetected = false)

        // Numer z drzewa Accessibility jest tekstem samego UI Pyszne i ma
        // pierwszenstwo przed OCR. To eliminuje sytuacje, gdy OCR myli jedna
        // litere w #FGFQM7 i lista brakow/stan ZAPISANE odnosza sie do innego ID.
        // Miganie starym wpisem nadal blokuje PyszneDisplayGate (2 odczyty + hold).
        candidateFrom(accessibilityClean)?.let { trusted ->
            return PyszneHistoryResolution(detailsDetected = true, conflict = false, candidate = trusted)
        }

        val candidates = fallbackClean.mapNotNull(::candidateFrom)
        val identities = candidates.map { it.identity }.distinct()
        if (identities.size > 1) {
            return PyszneHistoryResolution(detailsDetected = true, conflict = true)
        }
        return PyszneHistoryResolution(
            detailsDetected = true,
            conflict = false,
            candidate = candidates.maxByOrNull { it.score }
        )
    }

    private fun historyCandidateScore(entry: PyszneDeliveryLog, text: String): Int {
        var score = 100
        if (!entry.restaurant.equals("Nieznana restauracja", ignoreCase = true)) score += 20
        if (entry.acceptedMinuteOfDay != null) score += 10
        if (text.contains("Suma przychod", ignoreCase = true) || text.contains("total earnings", ignoreCase = true)) score += 10
        return score
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
     * Drzewo Accessibility traktujemy tu tylko jako zrodlo harmonogramu.
     * Kwota i dystans nadal pochodza z aktualnego OCR widocznego ekranu.
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

            // Nie wymagamy juz, aby drzewo Accessibility zawieralo rowniez kwote
            // i dystans. Na prawdziwym Pyszne te dane sa czesto rysowane, a
            // godziny Odbierz/Dostarcz sa dostepne jako osobne wezly tekstowe.
            val schedule = OfferParser.findSchedule(buildAccessibilityText(root))
            if (schedule.deliveryTimeMinutesOfDay == null) return@mapNotNull null

            val score =
                (if (pkg == sourcePackageName && sourcePackageName.isNotBlank()) 100 else 0) +
                    (if (window.isActive) 20 else 0) +
                    (if (window.isFocused) 20 else 0)

            schedule to score
        }

        val schedule = candidates.maxByOrNull { it.second }?.first ?: return offer
        val deliveryTime = schedule.deliveryTimeMinutesOfDay ?: return offer

        return offer.copy(
            // Pyszne zawsze liczymy od aktualnego czasu telefonu do "Dostarcz na".
            durationMinutes = null,
            durationSeconds = null,
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
        rawOffer: Offer,
        applicationName: String,
        packageName: String = "",
        sourceText: String = ""
    ) {
        val rules = prefs.rulesForCourier(applicationName)
        // Po rozwinieciu arkusza Wolt "Szacowany 23-26 min" bywa zasloniety – bierzemy czas
        // z wczesniejszego odczytu TEJ SAMEJ oferty, zamiast pokazywac "BRAK CZASU".
        val offer = decisionTracker.current
            ?.takeIf { it.signature == OfferSignature.of(CourierPlatform.fromDisplayName(applicationName), rawOffer) }
            ?.offer
            ?.let { prev ->
                rawOffer.copy(
                    durationMinutes = rawOffer.durationMinutes ?: prev.durationMinutes,
                    pickupTimeMinutesOfDay = rawOffer.pickupTimeMinutesOfDay ?: prev.pickupTimeMinutesOfDay,
                    deliveryTimeMinutesOfDay = rawOffer.deliveryTimeMinutesOfDay ?: prev.deliveryTimeMinutesOfDay
                )
            } ?: rawOffer

        val now = LocalTime.now()
        val currentMinuteOfDay = now.hour * 60 + now.minute
        val result = ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = currentMinuteOfDay,
            decisionBasis = prefs.decisionBasis,
            zusPercent = if (prefs.zusEnabled) prefs.zusPercent else 0.0
        )

        val platform = CourierPlatform.fromDisplayName(applicationName)
        val historyEntry = if (platform == CourierPlatform.PYSZNE) {
            PyszneHistoryParser.parse(sourceText = sourceText, offer = offer)
        } else {
            null
        }
        val blacklistHits = prefs.findBlacklistHits(sourceText)

        if (platform == CourierPlatform.PYSZNE && isPyszneHistoryDetailsScreen(sourceText)) {
            val identity = historyEntry?.orderId?.trim()?.uppercase()
            if (identity.isNullOrBlank()) {
                pyszneDisplayGate.noteConflict()
                lastPyszneOverlayWasHistoryDetails = false
                if (::overlay.isInitialized) overlay.hide()
                scheduleThrottledScan(450L)
                return
            }

            if (!pyszneDisplayGate.shouldShow(identity, SystemClock.elapsedRealtime())) {
                // Pierwszy poprawny odczyt jest tylko kandydatem. Panel pozostaje
                // schowany, a szybszy kolejny skan potwierdza, ze Pyszne zdazylo
                // juz podmienic wszystkie dane na nowe zlecenie.
                lastPyszneOverlayWasHistoryDetails = false
                if (::overlay.isInitialized) overlay.hide()
                scheduleThrottledScan(450L)
                return
            }
            lastPyszneOverlayWasHistoryDetails = true
            onOfferGone()

            // 1.1: automatyczny zapis szczegolow zakonczonego zlecenia. Zapis idzie przez
            // ten sam magazyn co przycisk ZAPISZ (ta sama deduplikacja po numerze zlecenia).
            var alreadySaved = historyEntry?.let { pyszneLogStore.contains(it) } ?: false
            if (prefs.pyszneAutoSaveEnabled && historyEntry != null && !alreadySaved && lastAutoSavedKey != historyEntry.key) {
                val saveResult = runCatching { pyszneLogStore.save(historyEntry) }.getOrNull()
                if (saveResult != null) {
                    alreadySaved = true
                    lastAutoSavedKey = historyEntry.key
                    if (saveResult == PyszneSaveResult.SAVED) HapticLanguage.record(this)
                }
            }

            overlay.show(
                result = result,
                applicationName = applicationName,
                applicationIcon = resolveIcon(applicationName, packageName),
                blacklistHits = blacklistHits,
                historyEntry = historyEntry,
                historyAlreadySaved = alreadySaved,
                onSaveHistory = if (historyEntry != null) {
                    { entry -> pyszneLogStore.save(entry) }
                } else {
                    null
                },
                platform = platform,
                extras = OverlayExtras(decisionCounter = decisionCounterLabel())
            )
            return
        } else if (platform == CourierPlatform.PYSZNE) {
            lastPyszneOverlayWasHistoryDetails = false
        }

        var kind = when (val classified = ScreenClassifier.classify(sourceText)) {
            ScreenKind.OTHER -> if (Regex("(?i)nawiguj|navigate").containsMatchIn(sourceText)) ScreenKind.ACTIVE_JOB else ScreenKind.OFFER_CARD
            else -> classified
        }
        // Ta sama karta co widoczna oferta (np. OCR bez przycisku) nie jest aktywnym zleceniem.
        decisionTracker.current?.let { pendingNow ->
            if (kind == ScreenKind.ACTIVE_JOB && pendingNow.visible && pendingNow.signature == OfferSignature.of(platform, offer)) kind = ScreenKind.OFFER_CARD
        }
        val nowMillis = System.currentTimeMillis()
        val details = offerDetails(platform, sourceText)
        val language = prefs.languageCode

        if (kind == ScreenKind.ACTIVE_JOB) {
            // Ekran aktywnego zlecenia: nie jest nowa oferta. Potwierdza akceptacje
            // poprzedniej i aktualizuje "w trakcie".
            val previousJob = activeJobTracker.activeAt(nowMillis, platform)
            val seenRestaurant = updateActiveJobFromScreen(platform, sourceText, offer, details, nowMillis)
            decisionTracker.activeDeliveryScreenSeen(platform, nowMillis, seenRestaurant, previousJob?.restaurant, previousJob != null)
            persistDecisions()
            onOfferGone()
            overlay.show(
                result = result,
                applicationName = applicationName,
                applicationIcon = resolveIcon(applicationName, packageName),
                blacklistHits = blacklistHits,
                platform = platform,
                extras = OverlayExtras(
                    activeJobLabel = tr(language, "W TRAKCIE", "IN PROGRESS", "В ПРОЦЕСІ", "В ПРОЦЕССЕ"),
                    feasibilityNote = activeJobsLabel(activeJobTracker.displayJobs(nowMillis), currentMinuteOfDay, language),
                    feasibilityVerdict = OfferFeasibility.Verdict.UNKNOWN,
                    decisionCounter = decisionCounterLabel()
                )
            )
            return
        }

        // ---- karta nowej oferty ----
        val activeJobs = activeJobTracker.displayJobs(nowMillis)
        val activeJob = activeJobs.maxByOrNull { it.expectedFinishMinuteOfDay(currentMinuteOfDay) }
        val pending = if (prefs.decisionTrackingEnabled) {
            decisionTracker.offerVisible(
                platform = platform,
                offer = offer,
                result = result,
                nowMillis = nowMillis,
                restaurant = details.restaurant,
                pickupAddress = details.pickupAddress,
                dropoffAddress = details.dropoffAddress,
                stops = details.stops,
                duringActiveJob = activeJobs.isNotEmpty(),
                blacklisted = blacklistHits.hasAny
            ).also { p ->
                if (details.pickups.size > 1) p.extraPickups = details.pickups.drop(1)
                if (details.drops.size > 1) p.extraDropoffs = details.drops.drop(1)
            }
        } else {
            null
        }
        persistDecisions()
        val signature = OfferSignature.of(platform, offer)
        if (signature != lastOfferSignature) {
            lastOfferSignature = signature
            lastRouteAdvice = null
            lastRouteSignature = null
        }

        // Wolt: adresy sa dopiero po rozwinieciu arkusza. Zapamietujemy je dla tej oferty,
        // bo po zwinieciu arkusza znikaja z ekranu.
        val routeDetails = if (platform == CourierPlatform.WOLT) {
            if (details.fromTimeline) woltDetailsBySignature[signature] = details
            maybeExpandWoltOffer(signature, details)
            maybeCollapseWoltOffer(signature, sourceText, details)
            woltDetailsBySignature[signature] ?: details
        } else {
            details
        }
        val routeKey = signature + "|" + routeDetails.pickups.joinToString("/") + "|" + routeDetails.drops.joinToString("/")
        if (lastRouteSignature != null && lastRouteSignature != routeKey && lastRouteSignature!!.startsWith(signature)) {
            lastRouteAdvice = null
            lastRouteSignature = null
        }
        // Weryfikacja przed mapa: dla Wolt czekamy na adresy z rozwinietej karty (max ~4 s),
        // zeby nie geokodowac samej nazwy lokalu (dawalo zle punkty, np. KFC pod Pruszczem).
        val woltAwaitingAddresses = platform == CourierPlatform.WOLT && prefs.woltAutoExpandOffer &&
            !routeDetails.fromTimeline && nowMillis - (woltExpandStartedAt[signature] ?: nowMillis) < 4_000L

        var advice = lastRouteAdvice?.takeIf { lastRouteSignature == routeKey }
        if (advice == null && routeAdvisor.enabled && !woltAwaitingAddresses) {
            routeAdvisor.startLocationUpdates()
            advice = routeAdvisor.advise(
                signature = routeKey + "|" + activeJobs.joinToString(";") { it.platform.key + it.stage.name + it.remainingAddresses().size },
                restaurant = routeDetails.restaurant,
                pickups = routeDetails.pickups,
                drops = routeDetails.drops,
                activeJobs = activeJobs,
                language = language
            ) { fresh ->
                if (lastOfferSignature == signature && ::overlay.isInitialized && overlay.isVisible()) {
                    lastRouteAdvice = fresh
                    lastRouteSignature = routeKey
                    val minute = LocalTime.now().let { it.hour * 60 + it.minute }
                    val job = activeJobTracker.displayJobs(System.currentTimeMillis()).maxByOrNull { it.expectedFinishMinuteOfDay(minute) }
                    val feasibility = job?.let { OfferFeasibilityCalculator.evaluate(it, offer, minute, fresh.toPickup?.minutes, prefs.languageCode) }
                    overlay.updateExtras(
                        overlay.currentExtras().copy(
                            routeText = RouteAdvisor.describe(fresh, prefs.languageCode).ifBlank { null },
                            routePending = false,
                            spatialNote = fresh.spatial?.note,
                            spatialVerdict = fresh.spatial?.verdict,
                            mapPoints = if (prefs.showMiniMap) fresh.points else emptyList(),
                            mapTiles = prefs.mapTilesEnabled,
                            feasibilityNote = feasibility?.note ?: overlay.currentExtras().feasibilityNote,
                            feasibilityVerdict = feasibility?.verdict ?: overlay.currentExtras().feasibilityVerdict
                        )
                    )
                }
            }
            if (advice != null) {
                lastRouteAdvice = advice
                lastRouteSignature = routeKey
            }
        }

        val feasibility = activeJob?.let {
            OfferFeasibilityCalculator.evaluate(it, offer, currentMinuteOfDay, advice?.toPickup?.minutes, language)
        }

        voice?.offerAppeared()
        val extras = OverlayExtras(
            activeJobLabel = activeJobs.takeIf { it.isNotEmpty() }?.let { jobs ->
                tr(language, "W TRAKCIE: ", "IN PROGRESS: ", "В ПРОЦЕСІ: ", "В ПРОЦЕССЕ: ") + jobs.joinToString(" · ") { job -> shortJobLabel(job, currentMinuteOfDay) }
            },
            feasibilityNote = feasibility?.note,
            feasibilityVerdict = feasibility?.verdict,
            routeText = advice?.let { RouteAdvisor.describe(it, language).ifBlank { null } }
                ?: when {
                    woltAwaitingAddresses -> tr(language, "🧭 czytam adresy Wolt…", "🧭 reading Wolt addresses…", "🧭 читаю адреси…", "🧭 читаю адреса…")
                    routeAdvisor.enabled -> tr(language, "🧭 liczę trasę…", "🧭 routing…", "🧭 рахую маршрут…", "🧭 считаю маршрут…")
                    else -> null
                },
            routePending = advice == null,
            spatialNote = advice?.spatial?.note,
            spatialVerdict = advice?.spatial?.verdict,
            mapPoints = if (prefs.showMiniMap) advice?.points.orEmpty() else emptyList(),
            mapTiles = prefs.mapTilesEnabled,
            voiceState = if (prefs.voiceRejectEnabled) voiceState else null,
            voiceHint = voiceHint,
            voiceKeyword = prefs.voiceRejectKeyword,
            decisionCounter = decisionCounterLabel()
        )

        overlay.show(
            result = result,
            applicationName = applicationName,
            applicationIcon = resolveIcon(applicationName, packageName),
            blacklistHits = blacklistHits,
            historyEntry = null,
            historyAlreadySaved = false,
            onSaveHistory = null,
            platform = platform,
            extras = extras
        )
    }

    // ---- 1.1: asystent decyzji -------------------------------------------------------

    private data class OfferDetails(
        val restaurant: String?,
        val pickupAddress: String?,
        val dropoffAddress: String?,
        val stops: Int,
        val pickups: List<String> = listOfNotNull(pickupAddress),
        val drops: List<String> = listOfNotNull(dropoffAddress),
        /** Czy adresy pochodza z rozwinietej karty ("Ramy czasowe"). */
        val fromTimeline: Boolean = false
    )

    private fun offerDetails(platform: CourierPlatform, sourceText: String): OfferDetails = when (platform) {
        CourierPlatform.PYSZNE -> PyszneOfferDetailsParser.parse(sourceText).let {
            OfferDetails(it.restaurant, it.pickupAddress, it.dropoffAddress, it.stops)
        }
        CourierPlatform.WOLT -> {
            val timeline = WoltOfferDetailsParser.stopsFromTimeline(sourceText)
            if (timeline.isNotEmpty()) {
                val pickups = timeline.filter { it.isPickup }.map { it.address }.distinct()
                val drops = timeline.filter { !it.isPickup }.map { it.address }.distinct()
                OfferDetails(
                    restaurant = WoltOfferDetailsParser.restaurant(sourceText) ?: timeline.firstOrNull { it.isPickup }?.name,
                    pickupAddress = pickups.firstOrNull(),
                    dropoffAddress = drops.firstOrNull(),
                    stops = maxOf(WoltOfferDetailsParser.stops(sourceText), drops.size),
                    pickups = pickups,
                    drops = drops,
                    fromTimeline = true
                )
            } else {
                val (pickup, drop) = WoltOfferDetailsParser.addresses(sourceText)
                OfferDetails(
                    restaurant = WoltOfferDetailsParser.restaurant(sourceText),
                    pickupAddress = pickup,
                    dropoffAddress = drop,
                    stops = WoltOfferDetailsParser.stops(sourceText)
                )
            }
        }
        else -> {
            // Stuart / Uber: adresy sa czesto w tekscie jako "Nazwa - ulica 1, 80-000 Miasto".
            val lines = sourceText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val addressLike = lines.filter { Regex("""\d{2}-\d{3}""").containsMatchIn(it) }
            OfferDetails(
                restaurant = addressLike.firstOrNull()?.substringBefore(" - ")?.take(60),
                pickupAddress = addressLike.getOrNull(0)?.replace(Regex("""\s+\d+[,.]\d+\s*km$"""), ""),
                dropoffAddress = addressLike.getOrNull(1)?.replace(Regex("""\s+\d+[,.]\d+\s*km$"""), ""),
                stops = Regex("""(?i)(\d)\s*(?:stops?|przystank|zam[oó]wieni)""").find(sourceText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            )
        }
    }

    /** Aktualizuje aktywne zlecenie z ekranu "w trakcie" (Pyszne / Wolt / inne). */
    /** Zwraca nazwe lokalu widoczna na ekranie (do rozstrzygania decyzji). */
    private fun updateActiveJobFromScreen(platform: CourierPlatform, text: String, offer: Offer, details: OfferDetails, nowMillis: Long): String? {
        // Weryfikacja: gdy przed chwila byla widoczna oferta tej platformy i nie mamy jeszcze
        // zlecenia, nie tworzymy "aktywnego" z ekranu – to najczesciej resztka karty oferty.
        val pendingNow = decisionTracker.current
        if (!activeJobTracker.hasJob(platform, nowMillis) && pendingNow != null && pendingNow.platform == platform &&
            nowMillis - pendingNow.lastSeenAtMillis < 2_500L
        ) {
            return null
        }
        if (ScreenClassifier.classify(text) != ScreenKind.ACTIVE_JOB) return null
        return when (platform) {
            CourierPlatform.PYSZNE -> {
                val screen = PyszneActiveScreenParser.parse(text)
                activeJobTracker.onActiveScreen(
                    platform = platform,
                    nowMillis = nowMillis,
                    pickupMinute = screen?.pickupMinuteOfDay ?: offer.pickupTimeMinutesOfDay,
                    deliveryMinute = screen?.deliveryMinuteOfDay ?: offer.deliveryTimeMinutesOfDay,
                    restaurant = screen?.restaurant ?: details.restaurant,
                    pickedUp = screen?.pickedUp ?: false,
                    pickupAddress = screen?.pickupAddress ?: details.pickupAddress,
                    dropoffAddress = screen?.dropoffAddress ?: details.dropoffAddress
                )
                screen?.restaurant ?: details.restaurant
            }
            CourierPlatform.WOLT -> {
                val active = WoltActiveDeliveryParser.parse(text)
                activeJobTracker.onActiveScreen(
                    platform = platform,
                    nowMillis = nowMillis,
                    pickupMinute = active?.pickupMinuteOfDay ?: offer.pickupTimeMinutesOfDay,
                    deliveryMinute = active?.deliveryMinuteOfDay ?: offer.deliveryTimeMinutesOfDay,
                    restaurant = active?.venue ?: details.restaurant,
                    pickedUp = active?.stage == WoltActiveDelivery.Stage.TO_CUSTOMER,
                    pickupAddress = active?.pickupAddress,
                    dropoffAddress = active?.dropoffAddress,
                    etaMinutes = active?.etaMinutes
                )
                active?.venue ?: details.restaurant
            }
            else -> {
                activeJobTracker.onActiveScreen(
                platform = platform,
                nowMillis = nowMillis,
                pickupMinute = offer.pickupTimeMinutesOfDay,
                deliveryMinute = offer.deliveryTimeMinutesOfDay,
                restaurant = details.restaurant,
                pickedUp = Regex("(?i)dostarcz\\s+do|w\\s+drodze\\s+do\\s+klienta|odebrano|picked\\s+up").containsMatchIn(text),
                pickupAddress = details.pickupAddress,
                dropoffAddress = details.dropoffAddress
            )
                details.restaurant
            }
        }
    }

    private fun persistActiveJobs() {
        if (::activeJobStore.isInitialized) runCatching { activeJobStore.save(activeJobTracker.snapshot()) }
    }

    /** Zlecenie oznaczone jako dostarczone: Wolt (i inne bez historii) zapisujemy z pomiarem czasu. */
    private fun onJobDelivered(job: ActiveJob, nowMillis: Long) {
        if (job.platform == CourierPlatform.PYSZNE) return // Pyszne ma wlasna historie (auto-zapis szczegolow)
        if (job.amountPln <= 0.0 && job.restaurant == null) return
        val accepted = DecisionAnalytics.localDateTime(job.acceptedAtMillis)
        val duration = ((nowMillis - job.acceptedAtMillis) / 60_000L).toInt().coerceIn(1, 240)
        val order = WoltOrder(
            date = accepted.toLocalDate(),
            minuteOfDay = accepted.hour * 60 + accepted.minute,
            venue = job.restaurant ?: platformLabel(job.platform),
            grossPln = job.amountPln,
            distanceKm = job.distanceKm.takeIf { it > 0.0 },
            stops = job.stops,
            source = WoltOrder.SOURCE_LIVE,
            durationMinutes = duration,
            acceptedAtMillis = job.acceptedAtMillis,
            deliveredAtMillis = nowMillis
        )
        if (job.platform == CourierPlatform.WOLT && ::woltOrderStore.isInitialized && woltOrderStore.saveLive(order)) {
            HapticLanguage.record(this)
            val language = prefs.languageCode
            val message = tr(
                language,
                "✓ Zapisano Wolt: ${order.venue} · ${String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.2f", order.grossPln)} zł · ${duration} min",
                "✓ Saved Wolt: ${order.venue} · ${String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.2f", order.grossPln)} zł · ${duration} min",
                "✓ Збережено Wolt: ${order.venue} · ${duration} хв",
                "✓ Сохранено Wolt: ${order.venue} · ${duration} мин"
            )
            runCatching { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** Gesty startuja po przeciwnej stronie niz panel FUJARY, zeby nie trafic w nakladke. */
    private fun gestureX(platform: CourierPlatform): Float = if (prefs.overlaySide(platform) == "start") 0.82f else 0.18f

    /** Wolt: karta oferty nie pokazuje adresow, dopoki arkusz nie zostanie przesuniety w gore. */
    private fun maybeExpandWoltOffer(signature: String, details: OfferDetails) {
        if (!prefs.woltAutoExpandOffer || woltExpandStartedAt.containsKey(signature)) return
        if (details.fromTimeline) {
            // Kurier sam rozwinal karte – nic nie robimy, ale zapamietujemy oferte.
            woltExpandStartedAt[signature] = System.currentTimeMillis()
            woltCollapsedSignatures += signature
            return
        }
        woltExpandStartedAt[signature] = System.currentTimeMillis()
        if (woltExpandStartedAt.size > 40) woltExpandStartedAt.keys.take(20).forEach { woltExpandStartedAt.remove(it) }
        handler.postDelayed({
            if (lastOfferSignature != signature) return@postDelayed
            if (!RejectActionPerformer.offerCardVisible(this, ::isDeliveryPackage)) return@postDelayed
            // Start gestu na tekscie "Spodziewany zarobek" (nie na przycisku Akceptuj), w gore.
            RejectActionPerformer.swipe(this, fromYFraction = 0.70f, toYFraction = 0.25f, xFraction = gestureX(CourierPlatform.WOLT))
            scheduleThrottledScan(700L)
        }, 500L)
    }

    /** Po odczytaniu adresow zwijamy arkusz z powrotem, zeby kurier widzial mape Wolta. */
    private fun maybeCollapseWoltOffer(signature: String, sourceText: String, details: OfferDetails) {
        if (!prefs.woltAutoExpandOffer || signature in woltCollapsedSignatures) return
        val startedAt = woltExpandStartedAt[signature] ?: return
        val expanded = Regex("(?i)ramy\\s+czasowe").containsMatchIn(sourceText)
        val elapsed = System.currentTimeMillis() - startedAt
        if (!(details.fromTimeline || (expanded && elapsed > 2_500L))) return
        woltCollapsedSignatures += signature
        if (woltCollapsedSignatures.size > 40) woltCollapsedSignatures.remove(woltCollapsedSignatures.first())
        handler.postDelayed({
            if (lastOfferSignature != signature) return@postDelayed
            if (!RejectActionPerformer.offerCardVisible(this, ::isDeliveryPackage)) return@postDelayed
            // Arkusz rozwiniety: gora arkusza ~10% ekranu. Ciagniemy w dol na wysokosc mapy.
            RejectActionPerformer.swipe(this, fromYFraction = 0.13f, toYFraction = 0.66f, xFraction = gestureX(CourierPlatform.WOLT))
        }, 350L)
    }

    /**
     * Po odrzuceniu: jesli Wolt zostawil arkusz oferty na dole (bez przycisku Akceptuj),
     * zsuwamy go W DOL. Nigdy przy widocznym przycisku akceptacji ani przy dialogu "Na pewno…".
     */
    private fun dismissResidualSheetIfAny() {
        if (!prefs.autoDismissAfterReject) return
        if (RejectActionPerformer.offerCardVisible(this, ::isDeliveryPackage)) return
        if (!RejectActionPerformer.residualOfferSheetVisible(this, ::isDeliveryPackage)) return
        RejectActionPerformer.swipe(this, fromYFraction = 0.66f, toYFraction = 0.98f, xFraction = gestureX(CourierPlatform.WOLT))
    }

    private fun shortJobLabel(job: ActiveJob, currentMinuteOfDay: Int): String {
        val name = platformLabel(job.platform) + " " + job.label.take(28)
        val stage = if (job.stage == ActiveJob.Stage.TO_CUSTOMER) "→🏠" else "→🍴"
        val time = job.deliveryMinuteOfDay?.let { clockLabel(it) }
            ?: job.etaMinutes?.let { "${it} min" }
            ?: "~${clockLabel(job.expectedFinishMinuteOfDay(currentMinuteOfDay))}"
        return "$name $stage $time"
    }

    private fun activeJobsLabel(jobs: List<ActiveJob>, currentMinuteOfDay: Int, language: String): String? {
        if (jobs.isEmpty()) return null
        return jobs.joinToString("\n") { job ->
            val remaining = job.expectedFinishMinuteOfDay(currentMinuteOfDay) - currentMinuteOfDay
            shortJobLabel(job, currentMinuteOfDay) + " (" + tr(language, "jeszcze ~$remaining min", "~$remaining min left", "ще ~$remaining хв", "ещё ~$remaining мин") + ")"
        }
    }

    private fun handleDeliveryClick(event: AccessibilityEvent, platform: CourierPlatform?) {
        val collected = mutableListOf<String>()
        event.text?.forEach { item -> item?.toString()?.let { collected.add(it) } }
        event.contentDescription?.toString()?.let { collected.add(it) }
        // Czesto klikniety jest kontener; etykieta jest w dziecku.
        runCatching<AccessibilityNodeInfo?> { event.source }.getOrNull()?.let { source ->
            collectShallowTexts(source, collected, depth = 0)
        }
        val texts = collected.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (texts.isEmpty()) return

        val now = System.currentTimeMillis()
        // 1.2: etapy aktywnego zlecenia (Zakończ odbiór / Dostarcz zamówienie / Odrzuć dostawę).
        // "Odrzuć dostawę" / "Rozpocznij odbiór" w dialogu Wolt dotycza NOWEJ oferty – gdy przed
        // chwila byla oferta lub decyzja, nie ruszamy aktywnych zlecen.
        val offerContext = decisionTracker.current?.let { now - it.lastSeenAtMillis < 30_000L } == true ||
            now - lastOfferDecisionAt < 20_000L
        if (platform != null && !offerContext) {
            when {
                texts.any { JobStageButtons.isCancelled(it) } -> {
                    activeJobTracker.onFinished(platform)
                    lastRouteSignature = null
                    persistActiveJobs()
                }
                texts.any { JobStageButtons.isDelivered(it) } -> {
                    activeJobTracker.onFinished(platform)?.let { onJobDelivered(it, now) }
                    lastRouteSignature = null
                    persistActiveJobs()
                }
                texts.any { JobStageButtons.isPickedUp(it) } -> {
                    activeJobTracker.onPickedUp(platform, now)
                    lastRouteSignature = null
                    persistActiveJobs()
                }
            }
        }
        val pending = decisionTracker.current
        val decision = texts.firstNotNullOfOrNull { text ->
            decisionTracker.buttonClicked(text, now)?.let { it to text }
        }?.first ?: return
        lastOfferDecisionAt = now
        if (decision == OfferDecision.REJECTED && platform == CourierPlatform.WOLT) {
            // Reczne odrzucenie w Wolt: jesli zostanie arkusz na dole, zsuwamy go w dol.
            handler.postDelayed({ dismissResidualSheetIfAny() }, 1_500L)
            handler.postDelayed({ dismissResidualSheetIfAny() }, 3_000L)
        }

        if (decision == OfferDecision.ACCEPTED && pending != null) {
            activeJobTracker.onAccepted(
                platform = pending.platform,
                offer = pending.offer,
                nowMillis = now,
                restaurant = pending.restaurant,
                pickupAddress = pending.pickupAddress,
                dropoffAddress = pending.dropoffAddress,
                stops = pending.stops,
                extraPickups = pending.extraPickups,
                extraDropoffs = pending.extraDropoffs
            )
            persistActiveJobs()
        }
        persistDecisions()
        decisionCounterCache = 0L to null
        voice?.offerGone()
    }

    private fun collectShallowTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 3) return
        node.text?.toString()?.let(out::add)
        node.contentDescription?.toString()?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectShallowTexts(child, out, depth + 1)
        }
    }

    private fun onOfferGone() {
        decisionTracker.offerNotVisible(System.currentTimeMillis())
        persistDecisions()
        voice?.offerGone()
    }

    private fun persistDecisions() {
        if (!::offerEventStore.isInitialized) return
        val finished = decisionTracker.drainFinished()
        if (finished.isEmpty()) return
        offerEventStore.appendAll(finished)
        finished.filter { it.decision == OfferDecision.ACCEPTED && it.decisionSource == DecisionSource.NEXT_SCREEN }
            .forEach { activeJobTracker.enrichFromOffer(it.platform, it) }
        persistActiveJobs()
        decisionCounterCache = 0L to null
    }

    private fun decisionCounterLabel(): String? {
        if (!prefs.showDecisionCounter || !::offerEventStore.isInitialized) return null
        val now = System.currentTimeMillis()
        val (at, cached) = decisionCounterCache
        if (now - at < 30_000L) return cached
        val today = offerEventStore.forDate(LocalDate.now())
        val accepted = today.count { it.decision == OfferDecision.ACCEPTED }
        val declined = today.count { it.decision == OfferDecision.REJECTED || it.decision == OfferDecision.EXPIRED }
        val label = if (accepted + declined == 0) null else "✓$accepted ✗$declined"
        decisionCounterCache = now to label
        return label
    }

    private fun noteActiveJobScreen(platform: CourierPlatform, text: String) {
        if (ScreenClassifier.classify(text) != ScreenKind.ACTIVE_JOB) return
        val now = System.currentTimeMillis()
        val details = offerDetails(platform, text)
        val previousJob = activeJobTracker.activeAt(now, platform)
        val seenRestaurant = updateActiveJobFromScreen(platform, text, Offer(0.0, 0.0), details, now)
        decisionTracker.activeDeliveryScreenSeen(platform, now, seenRestaurant, previousJob?.restaurant, previousJob != null)
        persistDecisions()
    }

    private fun captureWoltData(accessibilityText: String, ocrText: String) {
        if (!prefs.woltTrackingEnabled || !::woltEarningsStore.isInitialized) return
        val texts = listOf(accessibilityText, ocrText).filter { it.isNotBlank() }
        texts.firstNotNullOfOrNull { WoltFinanceParser.parse(it) }?.let { snapshot ->
            if (woltEarningsStore.record(snapshot)) HapticLanguage.offer(this, ProfitabilityStatus.NO_TIME)
        }
        // 1.2: oficjalne sumy dni i pojedyncze zlecenia z "Zarobki brutto".
        texts.forEach { text ->
            val totals = WoltHistoryParser.parseDailySummary(text)
            if (totals.isNotEmpty() && woltOrderStore.saveDayTotals(totals) > 0) HapticLanguage.record(this)
            WoltHistoryParser.parseDayDetails(text)?.let { day ->
                var changed = 0
                day.totalPln?.let { changed += woltOrderStore.saveDayTotals(listOf(WoltDayTotal(day.date, it))) }
                changed += woltOrderStore.saveOrders(day.orders)
                if (changed > 0) HapticLanguage.record(this)
            }
        }
        texts.firstOrNull()?.let { noteActiveJobScreen(CourierPlatform.WOLT, it) }
    }

    private fun performVoiceReject() {
        val clicked = RejectActionPerformer.perform(this, ::isDeliveryPackage)
        if (!clicked) {
            voiceHint = tr(prefs.languageCode, "Nie znalazłem przycisku Odrzuć", "Decline button not found", "Не знайшов кнопку", "Не нашёл кнопку")
            if (::overlay.isInitialized) overlay.updateExtras(overlay.currentExtras().copy(voiceState = VoiceState.UNAVAILABLE, voiceHint = voiceHint))
            return
        }
        decisionTracker.rejectedByVoice(System.currentTimeMillis())
        lastOfferDecisionAt = System.currentTimeMillis()
        persistDecisions()
        // Niektore aplikacje pytaja jeszcze raz "Odrzucić?" – potwierdzamy, bo kurier juz potwierdzil glosem.
        handler.postDelayed({ RejectActionPerformer.confirmIfDialog(this, ::isDeliveryPackage) }, 700L)
        handler.postDelayed({ RejectActionPerformer.confirmIfDialog(this, ::isDeliveryPackage) }, 1_500L)
        handler.postDelayed({
            voice?.offerGone()
            if (::overlay.isInitialized) overlay.hide()
        }, 1_200L)
        handler.postDelayed({ dismissResidualSheetIfAny() }, 1_900L)
        handler.postDelayed({ dismissResidualSheetIfAny() }, 3_500L)
    }

    private fun platformLabel(platform: CourierPlatform): String = platform.displayName

    private fun clockLabel(minute: Int): String {
        val normalized = ((minute % 1440) + 1440) % 1440
        return String.format(java.util.Locale.ROOT, "%02d:%02d", normalized / 60, normalized % 60)
    }

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String =
        when (language) {
            "en" -> en
            "uk" -> uk
            "ru" -> ru
            else -> pl
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
            (("estimated earnings" in lower && (" mi total" in lower || "stuart" in lower)) ||
                ("szacowane zarob" in lower && "km total" in lower) ||
                ("szacowane zarob" in lower && "stuart" in lower)) -> CourierPlatform.STUART
            "expected earnings for the full delivery" in lower -> CourierPlatform.WOLT
            "spodziewany zarobek" in lower ||
                "spodziewany zarobek za pełną dostawę" in lower ||
                ("szacowane zarobki" in lower && "km total" !in lower) ||
                ("całkowita kwota reszty" in lower && "akceptuj" in lower) -> CourierPlatform.WOLT
            "route distance" in lower && "estimated" in lower && "accept" in lower && "pln" in lower -> CourierPlatform.WOLT
            "zaakceptuj zlecenie" in lower ||
                "zaakceptuj" in lower ||
                "dostawa do klienta" in lower ||
                ("odbiór:" in lower || "odbior:" in lower) ||
                ("odbierz na" in lower && "dostarcz na" in lower) ||
                ("accept" in lower && ("pickup" in lower || "pick up" in lower) && "deliver" in lower) ||
                (("czas aktywności" in lower || "czas aktywnosci" in lower || "active time" in lower) &&
                    ("szacowana odległość" in lower || "szacowana odleglosc" in lower || "estimated distance" in lower)) -> CourierPlatform.PYSZNE
            "bolt food" in lower || (Regex("""\d+[.,]?\d*\s*km\s*[,·|]\s*\d+\s*min\s*[,·|]\s*\d+[.,]\d{1,2}\s*z[łl]""", RegexOption.IGNORE_CASE).containsMatchIn(text)) -> CourierPlatform.BOLT
            // Polski Uber bywa OCR-owany bez slowa "Dostawa" albo z blokami
            // w innej kolejnosci. Uklad: kwota + Lacznie/total + min + km oraz
            // przycisk Akceptuj/Confirm jest wystarczajaco charakterystyczny.
            (Regex("""(?is)(?:łącznie|lacznie|total)[\s\S]{0,180}?\d{1,3}\s*(?:m|rn)\s*i\s*n[\s\S]{0,160}?\d{1,4}(?:[.,]\d{1,2})?\s*k\s*(?:m|rn)""").containsMatchIn(text) &&
                ("akceptuj" in lower || "confirm" in lower || "delivery" in lower || "dostawa" in lower)) -> CourierPlatform.UBER
            (("delivery" in lower || "dostawa" in lower) &&
                ("confirm" in lower || "akceptuj" in lower || "łącznie" in lower || "lacznie" in lower || "stops" in lower || "przystank" in lower) &&
                ("pln" in lower || "zł" in lower || "zl" in lower)) -> CourierPlatform.UBER
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

    private fun updateStatusNotification(active: Boolean) {
        val language = prefs.languageCode
        val notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        // Nie zapamiętujemy stanu, dopóki Android 13+ nie da zgody. Dzięki temu
        // po zaakceptowaniu dialogu następny skan od razu opublikuje ikonę/status.
        if (!notificationPermissionGranted) {
            lastNotificationActive = null
            lastNotificationLanguage = null
            return
        }
        val workStore = if (::workSessionStore.isInitialized) workSessionStore else null
        val workState = workStore?.state() ?: WorkState.STOPPED
        val workMinutes = workStore?.todayMinutes() ?: 0
        val workSignature = "$workState/$workMinutes"
        if (lastNotificationActive == active && lastNotificationLanguage == language && lastNotificationWork == workSignature) return

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL_ID,
                    "FUJARA - status odczytu",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Stała informacja, czy FUJARA odczytuje oferty."
                    setShowBadge(false)
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (active) {
            trStatus(language, "FUJARA działa", "FUJARA is running", "FUJARA працює", "FUJARA работает")
        } else {
            trStatus(language, "FUJARA wstrzymana", "FUJARA paused", "FUJARA призупинена", "FUJARA приостановлена")
        }
        val body = if (active) {
            trStatus(language, "Odczyt ofert jest aktywny", "Offer reading is active", "Читання пропозицій активне", "Чтение предложений активно")
        } else {
            trStatus(language, "Włącz analizę w aplikacji, aby odczytywać oferty", "Enable analysis in the app to read offers", "Увімкніть аналіз у застосунку", "Включите анализ в приложении")
        }

        val workLine = when (workState) {
            WorkState.RUNNING -> trStatus(language, "Praca: ${WorkAnalytics.formatHours(workMinutes)} · PRACUJĘ", "Work: ${WorkAnalytics.formatHours(workMinutes)} · WORKING", "Робота: ${WorkAnalytics.formatHours(workMinutes)}", "Работа: ${WorkAnalytics.formatHours(workMinutes)}")
            WorkState.PAUSED -> trStatus(language, "Praca: ${WorkAnalytics.formatHours(workMinutes)} · PAUZA", "Work: ${WorkAnalytics.formatHours(workMinutes)} · PAUSED", "Робота: ${WorkAnalytics.formatHours(workMinutes)} · ПАУЗА", "Работа: ${WorkAnalytics.formatHours(workMinutes)} · ПАУЗА")
            WorkState.STOPPED -> if (workMinutes > 0) trStatus(language, "Praca dziś: ${WorkAnalytics.formatHours(workMinutes)}", "Worked today: ${WorkAnalytics.formatHours(workMinutes)}", "Сьогодні: ${WorkAnalytics.formatHours(workMinutes)}", "Сегодня: ${WorkAnalytics.formatHours(workMinutes)}") else ""
        }
        fun action(code: Int, actionName: String, label: String): Notification.Action {
            val intent = Intent(this, WorkShiftReceiver::class.java).setAction(actionName).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_fujara_app), label, pending).build()
        }

        val builder = Notification.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fujara_app)
            .setContentTitle(title)
            .setContentText(if (workLine.isBlank()) body else "$body · $workLine")
            .setStyle(Notification.BigTextStyle().bigText(if (workLine.isBlank()) body else "$body\n$workLine"))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        when (workState) {
            WorkState.RUNNING -> {
                builder.addAction(action(1, WorkShiftReceiver.ACTION_PAUSE, trStatus(language, "Pauza", "Pause", "Пауза", "Пауза")))
                builder.addAction(action(2, WorkShiftReceiver.ACTION_STOP, trStatus(language, "Stop", "Stop", "Стоп", "Стоп")))
            }
            WorkState.PAUSED -> {
                builder.addAction(action(3, WorkShiftReceiver.ACTION_START, trStatus(language, "Wznów", "Resume", "Продовжити", "Продолжить")))
                builder.addAction(action(2, WorkShiftReceiver.ACTION_STOP, trStatus(language, "Stop", "Stop", "Стоп", "Стоп")))
            }
            WorkState.STOPPED -> builder.addAction(action(3, WorkShiftReceiver.ACTION_START, trStatus(language, "Start pracy", "Start work", "Старт", "Старт")))
        }
        val notification = builder.build()

        // Android 13+ wymaga zgody na powiadomienia. Activity prosi o nia po
        // konfiguracji; jesli user odmowi, analiza nadal dziala normalnie.
        runCatching { manager.notify(STATUS_NOTIFICATION_ID, notification) }
            .onSuccess {
                lastNotificationActive = active
                lastNotificationLanguage = language
                lastNotificationWork = workSignature
            }
    }

    private fun trStatus(language: String, pl: String, en: String, uk: String, ru: String): String =
        when (language) {
            "en" -> en
            "uk" -> uk
            "ru" -> ru
            else -> pl
        }

    private fun registerMiss() {
        misses++

        // Nakladka nie ma juz sztucznego timera. Jest widoczna tak dlugo, jak
        // wykrywamy karte oferty. Dwa kolejne pudla daja niewielka tolerancje na
        // pojedyncza klatke OCR, ale po zniknieciu oferty panel sam znika.
        if (misses >= 2 && ::overlay.isInitialized) {
            overlay.hide()
            onOfferGone()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class PyszneHistoryCandidate(
        val identity: String,
        val offer: Offer,
        val entry: PyszneDeliveryLog,
        val sourceText: String,
        val score: Int
    )

    private data class PyszneHistoryResolution(
        val detailsDetected: Boolean,
        val conflict: Boolean = false,
        val candidate: PyszneHistoryCandidate? = null
    )

    private data class WindowCandidate(
        val window: AccessibilityWindowInfo,
        val score: Int
    )

    private data class AccessibilityCandidate(
        val packageName: String,
        val applicationName: String,
        val offer: Offer,
        val sourceText: String,
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

    private companion object {
        const val STATUS_CHANNEL_ID = "fujara_status"
        const val STATUS_NOTIFICATION_ID = 7811
    }
}
