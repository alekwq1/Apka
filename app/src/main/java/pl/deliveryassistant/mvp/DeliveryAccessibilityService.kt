package pl.deliveryassistant.mvp

import android.accessibilityservice.AccessibilityService
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DeliveryAccessibilityService :
    AccessibilityService() {

    private lateinit var overlay:
        OverlayController

    private lateinit var prefs:
        AppPrefs

    private val handler =
        Handler(Looper.getMainLooper())

    private var pendingScan:
        Runnable? = null

    private var lastActivePackage:
        String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()

        prefs = AppPrefs(this)
        overlay = OverlayController(this)
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        event ?: return

        val activePackage =
            event.packageName
                ?.toString()
                .orEmpty()

        // Ignorujemy własną aplikację.
        if (
            activePackage ==
            packageName
        ) {
            return
        }

        if (
            activePackage.isNotBlank()
        ) {
            lastActivePackage =
                activePackage
        }

        val configuredPackage =
            prefs.targetPackage

        /*
         * Jeśli pole pakietu zostawisz PUSTE,
         * asystent działa z wieloma apkami:
         * Uber, Pyszne, itd.
         */
        if (
            configuredPackage.isNotBlank() &&
            activePackage != configuredPackage
        ) {
            overlay.hide()
            return
        }

        pendingScan?.let(
            handler::removeCallbacks
        )

        pendingScan =
            Runnable {
                scanActiveWindow()
            }.also {

                handler.postDelayed(
                    it,
                    220
                )
            }
    }

    override fun onInterrupt() =
        Unit

    override fun onDestroy() {

        pendingScan?.let(
            handler::removeCallbacks
        )

        overlay.destroy()

        super.onDestroy()
    }

    private fun scanActiveWindow() {

        val root =
            rootInActiveWindow
                ?: run {
                    overlay.hide()
                    return
                }

        val activePackage =
            root.packageName
                ?.toString()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: lastActivePackage

        val text =
            buildString {

                collectText(
                    root,
                    this,
                    0
                )
            }

        val offer =
            OfferParser.parse(text)
                ?: run {
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
                offer,
                rules
            )

        overlay.show(
            result = result,
            applicationName =
                getApplicationName(
                    activePackage
                ),
            applicationIcon =
                getApplicationIcon(
                    activePackage
                )
        )
    }

    private fun getApplicationName(
        packageName: String
    ): String {

        if (
            packageName.isBlank()
        ) {
            return "Delivery"
        }

        return runCatching {

            val applicationInfo =
                packageManager
                    .getApplicationInfo(
                        packageName,
                        0
                    )

            packageManager
                .getApplicationLabel(
                    applicationInfo
                )
                .toString()

        }.getOrElse {

            when {
                packageName.contains(
                    "uber",
                    ignoreCase = true
                ) -> "Uber"

                packageName.contains(
                    "pyszne",
                    ignoreCase = true
                ) -> "Pyszne.pl"

                packageName.contains(
                    "takeaway",
                    ignoreCase = true
                ) -> "Pyszne.pl"

                else -> "Delivery"
            }
        }
    }

    private fun getApplicationIcon(
        packageName: String
    ): Drawable? {

        if (
            packageName.isBlank()
        ) {
            return null
        }

        return runCatching {

            packageManager
                .getApplicationIcon(
                    packageName
                )

        }.getOrNull()
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int
    ) {

        if (
            depth > 40
        ) {
            return
        }

        node.text
            ?.toString()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {

                out.append(it)
                    .append('\n')
            }

        node.contentDescription
            ?.toString()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {

                out.append(it)
                    .append('\n')
            }

        for (
            i in 0 until
                node.childCount
        ) {

            node.getChild(i)
                ?.let { child ->

                    collectText(
                        child,
                        out,
                        depth + 1
                    )
                }
        }
    }
}