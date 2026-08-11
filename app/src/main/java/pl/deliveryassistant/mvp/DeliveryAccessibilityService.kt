package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class DeliveryAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private lateinit var prefs: AppPrefs

    private val handler =
        Handler(Looper.getMainLooper())

    private var recognizer: TextRecognizer? = null

    private var ocrBusy = false

    private var misses = 0

    private var pendingImmediateScan: Runnable? = null

    /*
     * Co ile sprawdzamy ekran.
     *
     * 1400 ms = wystarczająco szybko dla ofert,
     * ale bez robienia kilkunastu screenshotów na sekundę.
     */
    private val scanIntervalMs = 1400L

    private val pollRunnable =
        object : Runnable {

            override fun run() {

                scanVisibleScreen()

                handler.postDelayed(
                    this,
                    scanIntervalMs
                )
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        prefs =
            AppPrefs(this)

        overlay =
            OverlayController(this)

        recognizer =
            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

        /*
         * Pierwszy skan chwilę po włączeniu usługi.
         */
        handler.postDelayed(
            pollRunnable,
            600L
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        event ?: return

        /*
         * Ignorujemy własną aplikację/overlay.
         */
        if (
            event.packageName
                ?.toString() ==
            packageName
        ) {
            return
        }

        /*
         * Gdy coś zmieni się na ekranie,
         * nie czekamy całych 1,4 s.
         */
        pendingImmediateScan?.let {
            handler.removeCallbacks(it)
        }

        pendingImmediateScan =
            Runnable {

                scanVisibleScreen()

            }.also {

                handler.postDelayed(
                    it,
                    180L
                )
            }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {

        handler.removeCallbacks(
            pollRunnable
        )

        pendingImmediateScan?.let {
            handler.removeCallbacks(it)
        }

        recognizer?.close()
        recognizer = null

        if (::overlay.isInitialized) {
            overlay.destroy()
        }

        super.onDestroy()
    }

    /*
     * ============================================================
     * GŁÓWNY SKAN
     * ============================================================
     */

    private fun scanVisibleScreen() {

        if (ocrBusy) {
            return
        }

        /*
         * Screenshot Accessibility działa od Androida 11 / API 30.
         *
         * Na starszym telefonie zostajemy przy klasycznym
         * Accessibility.
         */
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {

            scanAccessibilityOnly()
            return
        }

        ocrBusy = true

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,

            object :
                AccessibilityService.TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot:
                        AccessibilityService.ScreenshotResult
                ) {

                    val buffer =
                        screenshot.hardwareBuffer

                    val hardwareBitmap =
                        Bitmap.wrapHardwareBuffer(
                            buffer,
                            screenshot.colorSpace
                        )

                    /*
                     * Robimy zwykłą bitmapę ARGB,
                     * żeby ML Kit mógł ją spokojnie analizować.
                     */
                    val bitmap =
                        hardwareBitmap?.copy(
                            Bitmap.Config.ARGB_8888,
                            true
                        )

                    buffer.close()

                    if (bitmap == null) {

                        ocrBusy = false

                        scanAccessibilityOnly()

                        return
                    }

                    val prepared =
                        prepareScreenshot(bitmap)

                    bitmap.recycle()

                    runOcr(prepared)
                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    ocrBusy = false

                    /*
                     * Jeśli screenshot się nie uda,
                     * nadal próbujemy zwykłego Accessibility.
                     */
                    scanAccessibilityOnly()
                }
            }
        )
    }

    /*
     * ============================================================
     * OCR
     * ============================================================
     */

    private fun runOcr(
        bitmap: Bitmap
    ) {

        val scanner =
            recognizer

        if (scanner == null) {

            bitmap.recycle()

            ocrBusy = false

            return
        }

        val image =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        scanner
            .process(image)

            .addOnSuccessListener { result ->

                val text =
                    result.text

                val source =
                    recognizeCourierFromScreen(
                        text
                    )

                /*
                 * OCR stosujemy przede wszystkim do ekranów,
                 * które naprawdę wyglądają jak oferta
                 * Uber/Pyszne.
                 *
                 * Dzięki temu losowe PLN/km z innych aplikacji
                 * nie powinny uruchamiać overlayu.
                 */
                if (source != null) {

                    val offer =
                        OfferParser.parse(
                            text
                        )

                    if (offer != null) {

                        misses = 0

                        showOffer(
                            offer = offer,
                            applicationName = source
                        )

                        return@addOnSuccessListener
                    }
                }

                /*
                 * OCR nic sensownego nie znalazł.
                 * Próbujemy zwykłego Accessibility.
                 */
                val foundAccessibility =
                    scanAccessibilityOnly(
                        hideOnFailure = false
                    )

                if (!foundAccessibility) {

                    misses++

                    /*
                     * Nie chowamy po jednym nieudanym frame,
                     * żeby overlay nie migał.
                     */
                    if (misses >= 2) {
                        overlay.hide()
                    }
                }
            }

            .addOnFailureListener {

                scanAccessibilityOnly()
            }

            .addOnCompleteListener {

                bitmap.recycle()

                ocrBusy = false
            }
    }

    /*
     * ============================================================
     * PRZYGOTOWANIE SCREENSHOTA
     * ============================================================
     */

    private fun prepareScreenshot(
        source: Bitmap
    ): Bitmap {

        /*
         * Robimy kopię, na której możemy rysować.
         */
        val mutable =
            source.copy(
                Bitmap.Config.ARGB_8888,
                true
            )

        /*
         * Nasz Delivery Assistant siedzi w prawym
         * górnym rogu.
         *
         * Zamazujemy ten fragment przed OCR.
         *
         * Inaczej OCR mógłby przeczytać nasze własne:
         *
         * 25,42 zł
         * 5,40 km
         * 26 min
         *
         * i uznać je za nową ofertę.
         */
        val canvas =
            Canvas(mutable)

        val paint =
            Paint().apply {
                color = Color.BLACK
            }

        val maskWidth =
            dp(240)

        val maskHeight =
            dp(300)

        val left =
            (
                mutable.width -
                    maskWidth
                ).coerceAtLeast(0)

        val bottom =
            maskHeight.coerceAtMost(
                mutable.height
            )

        canvas.drawRect(
            left.toFloat(),
            0f,
            mutable.width.toFloat(),
            bottom.toFloat(),
            paint
        )

        /*
         * Na bardzo dużych ekranach zmniejszamy obraz.
         * Dla tak dużych napisów jak:
         *
         * PLN25.42
         * 26 min (5.4 km)
         *
         * nadal zostaje dużo szczegółów,
         * a OCR ma mniej pracy.
         */
        val maxWidth =
            900

        if (
            mutable.width <=
            maxWidth
        ) {
            return mutable
        }

        val ratio =
            maxWidth.toFloat() /
                mutable.width.toFloat()

        val newHeight =
            (
                mutable.height *
                    ratio
                ).toInt()
                .coerceAtLeast(1)

        val scaled =
            Bitmap.createScaledBitmap(
                mutable,
                maxWidth,
                newHeight,
                true
            )

        mutable.recycle()

        return scaled
    }

    /*
     * ============================================================
     * ROZPOZNAWANIE TYPU APLIKACJI Z OCR
     * ============================================================
     */

    private fun recognizeCourierFromScreen(
        text: String
    ): String? {

        val lower =
            text.lowercase()

        /*
         * Typowa karta Uber Delivery:
         *
         * PLN25.42
         * 26 min (5.4 km) total
         * Delivery
         * Confirm
         */
        val looksUber =
            "pln" in lower &&
                (
                    "confirm" in lower ||
                        "delivery" in lower
                    ) &&
                (
                    "total" in lower ||
                        "min" in lower
                    )

        if (looksUber) {
            return "Uber"
        }

        /*
         * Typowa oferta Pyszne.
         */
        val looksPyszne =
            (
                "zaakceptuj zlecenie" in lower ||
                    "odbiór:" in lower ||
                    "odbior:" in lower
                ) &&
                (
                    "zł" in lower ||
                        "zl" in lower ||
                        "pln" in lower
                    )

        if (looksPyszne) {
            return "Pyszne.pl"
        }

        return null
    }

    /*
     * ============================================================
     * ACCESSIBILITY - FALLBACK
     * ============================================================
     */

    private fun scanAccessibilityOnly(
        hideOnFailure: Boolean = true
    ): Boolean {

        val candidates =
            mutableListOf<AccessibilityCandidate>()

        var deliveryWindowExists =
            false

        /*
         * Najpierw wszystkie widoczne/interaktywne okna.
         */
        for (window in windows) {

            val root =
                runCatching {
                    window.root
                }.getOrNull()
                    ?: continue

            val pkg =
                root.packageName
                    ?.toString()
                    .orEmpty()

            if (
                pkg ==
                packageName
            ) {
                continue
            }

            if (
                isDeliveryPackage(pkg)
            ) {
                deliveryWindowExists =
                    true
            }

            val text =
                buildString {

                    collectText(
                        root,
                        this,
                        0
                    )
                }

            val offer =
                OfferParser.parse(
                    text
                ) ?: continue

            var score =
                0

            if (
                isDeliveryPackage(
                    pkg
                )
            ) {
                score += 1000
            }

            if (
                window.isActive
            ) {
                score += 200
            }

            if (
                window.isFocused
            ) {
                score += 200
            }

            candidates +=
                AccessibilityCandidate(
                    packageName = pkg,
                    text = text,
                    offer = offer,
                    score = score
                )
        }

        /*
         * Jeśli na ekranie jest znane okno kurierskie,
         * nie bierzemy oferty np. z Telegrama pod spodem.
         */
        val usableCandidates =
            if (deliveryWindowExists) {

                candidates.filter {
                    isDeliveryPackage(
                        it.packageName
                    )
                }

            } else {

                candidates
            }

        val selected =
            usableCandidates.maxByOrNull {
                it.score
            }

        if (selected != null) {

            misses = 0

            val appName =
                detectApplicationName(
                    selected.packageName,
                    selected.text
                )

            showOffer(
                offer = selected.offer,
                applicationName = appName,
                packageName =
                    selected.packageName
            )

            return true
        }

        /*
         * Klasyczny root jako ostatnia próba.
         */
        val root =
            rootInActiveWindow

        if (root != null) {

            val pkg =
                root.packageName
                    ?.toString()
                    .orEmpty()

            if (
                pkg != packageName &&
                (
                    !deliveryWindowExists ||
                        isDeliveryPackage(pkg)
                    )
            ) {

                val text =
                    buildString {

                        collectText(
                            root,
                            this,
                            0
                        )
                    }

                val offer =
                    OfferParser.parse(
                        text
                    )

                if (offer != null) {

                    misses = 0

                    showOffer(
                        offer = offer,

                        applicationName =
                            detectApplicationName(
                                pkg,
                                text
                            ),

                        packageName = pkg
                    )

                    return true
                }
            }
        }

        if (hideOnFailure) {

            misses++

            if (misses >= 2) {
                overlay.hide()
            }
        }

        return false
    }

    /*
     * ============================================================
     * WYLICZENIA + OVERLAY
     * ============================================================
     */

    private fun showOffer(
        offer: Offer,
        applicationName: String,
        packageName: String = ""
    ) {

        val rules =
            ProfitabilityCalculator.Rules(

                vehicleCostPerKm =
                    prefs.vehicleCostPerKm,

                minimumNetPerKm =
                    prefs.minimumNetPerKm,

                minimumNetPerHour =
                    prefs.minimumNetPerHour,

                fallbackMinutes =
                    prefs.fallbackMinutes
            )

        val result =
            ProfitabilityCalculator.calculate(
                offer,
                rules
            )

        val icon =
            resolveIcon(
                applicationName,
                packageName
            )

        /*
         * To pasuje do Twojego nowego,
         * zielono-czerwonego OverlayController.
         */
        overlay.show(
            result = result,
            applicationName = applicationName,
            applicationIcon = icon
        )
    }

    /*
     * ============================================================
     * NAZWA APLIKACJI
     * ============================================================
     */

    private fun detectApplicationName(
        packageName: String,
        text: String
    ): String {

        val lowerPackage =
            packageName.lowercase()

        val lowerText =
            text.lowercase()

        if (
            "uber" in lowerPackage ||
            "ubercab" in lowerPackage ||
            (
                "pln" in lowerText &&
                    "confirm" in lowerText
                )
        ) {
            return "Uber"
        }

        if (
            "pyszne" in lowerPackage ||
            "takeaway" in lowerPackage ||
            "justeat" in lowerPackage ||
            "zaakceptuj zlecenie" in lowerText
        ) {
            return "Pyszne.pl"
        }

        if (
            "wolt" in lowerPackage
        ) {
            return "Wolt"
        }

        if (
            "glovo" in lowerPackage
        ) {
            return "Glovo"
        }

        if (
            "bolt" in lowerPackage
        ) {
            return "Bolt Food"
        }

        if (
            packageName.isBlank()
        ) {
            return "Delivery"
        }

        return runCatching {

            val info =
                packageManager
                    .getApplicationInfo(
                        packageName,
                        0
                    )

            packageManager
                .getApplicationLabel(
                    info
                )
                .toString()

        }.getOrDefault(
            "Delivery"
        )
    }

    private fun isDeliveryPackage(
        packageName: String
    ): Boolean {

        val lower =
            packageName.lowercase()

        return (
            "uber" in lower ||
                "ubercab" in lower ||
                "pyszne" in lower ||
                "takeaway" in lower ||
                "justeat" in lower ||
                "wolt" in lower ||
                "glovo" in lower ||
                "bolt" in lower
            )
    }

    /*
     * ============================================================
     * IKONA
     * ============================================================
     */

    private fun resolveIcon(
        applicationName: String,
        packageName: String
    ): Drawable? {

        /*
         * Jeśli znamy prawdziwy package - używamy go.
         */
        if (
            packageName.isNotBlank()
        ) {

            runCatching {

                packageManager
                    .getApplicationIcon(
                        packageName
                    )

            }.getOrNull()
                ?.let {
                    return it
                }
        }

        /*
         * Uber ma znany package.
         */
        if (
            applicationName ==
            "Uber"
        ) {

            return runCatching {

                packageManager
                    .getApplicationIcon(
                        "com.ubercab.driver"
                    )

            }.getOrNull()
        }

        return null
    }

    /*
     * ============================================================
     * ODCZYT ACCESSIBILITY TREE
     * ============================================================
     */

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int
    ) {

        if (
            depth > 60
        ) {
            return
        }

        node.text
            ?.toString()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {

                out.append(it)
                    .append('\n')
            }

        node.contentDescription
            ?.toString()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {

                out.append(it)
                    .append('\n')
            }

        node.hintText
            ?.toString()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {

                out.append(it)
                    .append('\n')
            }

        for (
            index in
            0 until node.childCount
        ) {

            val child =
                node.getChild(index)
                    ?: continue

            collectText(
                child,
                out,
                depth + 1
            )
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()
    }

    private data class AccessibilityCandidate(
        val packageName: String,
        val text: String,
        val offer: Offer,
        val score: Int
    )
}