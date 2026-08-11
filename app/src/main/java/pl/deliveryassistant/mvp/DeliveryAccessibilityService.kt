package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DeliveryAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private lateinit var prefs: AppPrefs

    private val handler = Handler(Looper.getMainLooper())
    private var pendingScan: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        prefs = AppPrefs(this)
        overlay = OverlayController(this)

        /*
         * Uber używa pływającego okna.
         * Chcemy widzieć WSZYSTKIE interaktywne okna,
         * a nie tylko główną aktywną aplikację.
         */
        val info = serviceInfo

        info.flags =
            info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        /*
         * Nie analizujemy natychmiast.
         * Dajemy Uberowi/Pyszne chwilę na narysowanie
         * całej karty z kwotą, km i czasem.
         */
        pendingScan?.let(handler::removeCallbacks)

        pendingScan = Runnable {
            scanAllWindows()
        }.also {
            handler.postDelayed(it, 180)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingScan?.let(handler::removeCallbacks)

        if (::overlay.isInitialized) {
            overlay.destroy()
        }

        super.onDestroy()
    }

    private fun scanAllWindows() {

        val configuredPackage =
            prefs.targetPackage.trim()

        val candidates =
            mutableListOf<WindowOfferCandidate>()

        /*
         * NAJWAŻNIEJSZA ZMIANA:
         *
         * Nie rootInActiveWindow,
         * tylko wszystkie okna widoczne na ekranie.
         *
         * Dzięki temu:
         *
         * - Uber może być pływającą kartą,
         * - mapa może być pod spodem,
         * - launcher może być aktywnym ekranem,
         * - a my nadal znajdziemy kartę Ubera.
         */
        for ((index, window) in windows.withIndex()) {

            val root = runCatching {
                window.root
            }.getOrNull() ?: continue

            val packageName =
                root.packageName
                    ?.toString()
                    .orEmpty()

            /*
             * Bardzo ważne:
             * NIE czytamy własnego overlayu,
             * bo wtedy asystent zacząłby analizować
             * własne liczby.
             */
            if (packageName == this.packageName) {
                continue
            }

            /*
             * Jeżeli użytkownik wpisał konkretny pakiet,
             * filtrujemy tylko do niego.
             *
             * Jeśli pole pakietu jest puste:
             * Uber + Pyszne + inne aplikacje działają razem.
             */
            if (
                configuredPackage.isNotBlank() &&
                packageName != configuredPackage
            ) {
                continue
            }

            val text = buildString {
                collectText(
                    node = root,
                    out = this,
                    depth = 0
                )
            }

            if (text.isBlank()) {
                continue
            }

            val offer =
                OfferParser.parse(text)
                    ?: continue

            candidates += WindowOfferCandidate(
                packageName = packageName,
                offer = offer,
                windowIndex = index,
                text = text
            )
        }

        /*
         * Awaryjnie sprawdzamy też klasyczne
         * rootInActiveWindow.
         *
         * Przyda się np. dla zwykłych ekranów Pyszne.
         */
        val activeRoot =
            rootInActiveWindow

        if (activeRoot != null) {

            val packageName =
                activeRoot.packageName
                    ?.toString()
                    .orEmpty()

            if (
                packageName != this.packageName &&
                (
                    configuredPackage.isBlank() ||
                    packageName == configuredPackage
                )
            ) {

                val text = buildString {
                    collectText(
                        node = activeRoot,
                        out = this,
                        depth = 0
                    )
                }

                OfferParser.parse(text)?.let { offer ->

                    candidates += WindowOfferCandidate(
                        packageName = packageName,
                        offer = offer,
                        windowIndex = -1,
                        text = text
                    )
                }
            }
        }

        /*
         * Nic nie znaleziono.
         */
        if (candidates.isEmpty()) {
            overlay.hide()
            return
        }

        /*
         * Jeśli jest kilka okien:
         *
         * preferujemy aplikacje kurierskie.
         *
         * To zapobiega sytuacji, gdzie np.
         * pod Uberem jest strona z jakimiś liczbami
         * i parser wybierze nie ten ekran.
         */
        val selected =
            candidates.maxByOrNull {
                scoreCandidate(it)
            } ?: run {
                overlay.hide()
                return
            }

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
                selected.offer,
                rules
            )

        /*
         * Wersja overlayu ze stylem,
         * który teraz masz.
         */
        overlay.show(
            result = result,
            applicationName =
                getApplicationName(
                    selected.packageName
                ),
            applicationIcon =
                getApplicationIcon(
                    selected.packageName
                )
        )
    }

    private fun scoreCandidate(
        candidate: WindowOfferCandidate
    ): Int {

        val packageName =
            candidate.packageName.lowercase()

        var score = 0

        /*
         * Najwyższy priorytet dla aplikacji dostawczych.
         */
        if ("ubercab" in packageName) {
            score += 1000
        }

        if ("uber" in packageName) {
            score += 900
        }

        if ("takeaway" in packageName) {
            score += 900
        }

        if ("pyszne" in packageName) {
            score += 900
        }

        if ("justeat" in packageName) {
            score += 900
        }

        if ("wolt" in packageName) {
            score += 900
        }

        if ("glovo" in packageName) {
            score += 900
        }

        if ("bolt" in packageName) {
            score += 900
        }

        /*
         * Oferta z prawdziwym czasem jest bardziej
         * wiarygodna niż taka, gdzie używamy fallbacku.
         */
        if (
            candidate.offer.durationMinutes != null
        ) {
            score += 100
        }

        /*
         * PLN jest mocną wskazówką przy Uberze.
         */
        if (
            candidate.text.contains(
                "PLN",
                ignoreCase = true
            )
        ) {
            score += 30
        }

        /*
         * Typowe słowa z ekranu ofert.
         */
        if (
            candidate.text.contains(
                "Confirm",
                ignoreCase = true
            )
        ) {
            score += 20
        }

        if (
            candidate.text.contains(
                "Delivery",
                ignoreCase = true
            )
        ) {
            score += 20
        }

        return score
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int
    ) {

        if (depth > 60) {
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

        /*
         * Hint też czasem zawiera dane
         * w niestandardowych widokach.
         */
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
            i in 0 until node.childCount
        ) {

            val child =
                node.getChild(i)
                    ?: continue

            collectText(
                node = child,
                out = out,
                depth = depth + 1
            )
        }
    }

    private fun getApplicationName(
        packageName: String
    ): String {

        val lower =
            packageName.lowercase()

        /*
         * Rozpoznajemy znane aplikacje po package name.
         * To działa nawet jeśli Android ograniczy
         * dostęp PackageManagera do innej aplikacji.
         */
        when {

            "ubercab" in lower ||
            "uber" in lower ->
                return "Uber"

            "takeaway" in lower ||
            "pyszne" in lower ||
            "justeat" in lower ->
                return "Pyszne.pl"

            "wolt" in lower ->
                return "Wolt"

            "glovo" in lower ->
                return "Glovo"

            "bolt" in lower ->
                return "Bolt Food"
        }

        if (packageName.isBlank()) {
            return "Delivery"
        }

        return runCatching {

            val info =
                packageManager.getApplicationInfo(
                    packageName,
                    0
                )

            packageManager
                .getApplicationLabel(info)
                .toString()

        }.getOrDefault(
            "Delivery"
        )
    }

    private fun getApplicationIcon(
        packageName: String
    ): Drawable? {

        if (packageName.isBlank()) {
            return null
        }

        return runCatching {

            packageManager.getApplicationIcon(
                packageName
            )

        }.getOrNull()
    }

    private data class WindowOfferCandidate(
        val packageName: String,
        val offer: Offer,
        val windowIndex: Int,
        val text: String
    )
}