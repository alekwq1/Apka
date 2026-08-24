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
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        updateStatusNotification(prefs.analysisEnabled)
        handler.postDelayed(pollRunnable, 600L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (!prefs.analysisEnabled) {
            updateStatusNotification(false)
            if (::overlay.isInitialized) overlay.hide()
            return
        }
        updateStatusNotification(true)

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

        if (!prefs.analysisEnabled) {
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
                val pyszneAccessibilityText = if (
                    packagePlatform == CourierPlatform.PYSZNE && !isGalleryPackage(sourcePackageName)
                ) {
                    accessibilityTextForPackage(sourcePackageName)
                } else {
                    ""
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
            val dates = PyszneHistoryParser.parseDatesFromText(clean).distinct()
            if (dates.size != 1) return null
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
        offer: Offer,
        applicationName: String,
        packageName: String = "",
        sourceText: String = ""
    ) {
        val rules = prefs.rulesForCourier(applicationName)

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
        } else if (platform == CourierPlatform.PYSZNE) {
            lastPyszneOverlayWasHistoryDetails = false
        }

        overlay.show(
            result = result,
            applicationName = applicationName,
            applicationIcon = resolveIcon(applicationName, packageName),
            blacklistHits = prefs.findBlacklistHits(sourceText),
            historyEntry = historyEntry,
            historyAlreadySaved = historyEntry?.let { pyszneLogStore.contains(it) } ?: false,
            onSaveHistory = if (historyEntry != null) {
                { entry -> pyszneLogStore.save(entry) }
            } else {
                null
            }
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
        if (lastNotificationActive == active && lastNotificationLanguage == language) return

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

        val notification = Notification.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fujara_app)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // Android 13+ wymaga zgody na powiadomienia. Activity prosi o nia po
        // konfiguracji; jesli user odmowi, analiza nadal dziala normalnie.
        runCatching { manager.notify(STATUS_NOTIFICATION_ID, notification) }
            .onSuccess {
                lastNotificationActive = active
                lastNotificationLanguage = language
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
