package pl.fujara.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

enum class VoiceState { OFF, LISTENING, CONFIRMING, EXECUTING, UNAVAILABLE }

/**
 * Glosowe odrzucanie: nasluch tylko wtedy, gdy karta oferty jest widoczna.
 *
 * 1. Kurier mowi haslo (domyslnie "odrzuć").
 * 2. FUJARA pyta "Odrzucić? Powiedz: odrzucam" (TTS + pasek w nakladce).
 * 3. Po potwierdzeniu klika "Odrzuć" w aplikacji kurierskiej przez Accessibility.
 *
 * Uwaga: systemowy rozpoznawacz mowy na wielu telefonach wydaje krotki dzwiek przy
 * starcie nasluchu i moze wymagac internetu, jesli nie ma pakietu offline.
 */
class VoiceRejectController(
    private val service: AccessibilityService,
    private val prefs: AppPrefs,
    private val onStateChanged: (VoiceState, String?) -> Unit,
    private val onConfirmedReject: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var state: VoiceState = VoiceState.OFF
    private var offerVisible = false
    private var consecutiveErrors = 0
    private var confirmDeadline: Runnable? = null
    private var restartRunnable: Runnable? = null

    val currentState: VoiceState
        get() = state

    fun hasPermission(): Boolean =
        service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun offerAppeared() {
        offerVisible = true
        if (!prefs.voiceRejectEnabled) return
        if (state == VoiceState.LISTENING || state == VoiceState.CONFIRMING || state == VoiceState.EXECUTING) return
        if (!hasPermission() || !SpeechRecognizer.isRecognitionAvailable(service)) {
            setState(VoiceState.UNAVAILABLE)
            return
        }
        consecutiveErrors = 0
        startListening(confirmMode = false)
    }

    fun offerGone() {
        offerVisible = false
        stopAll()
        setState(VoiceState.OFF)
    }

    fun destroy() {
        stopAll()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun setState(newState: VoiceState, hint: String? = null) {
        state = newState
        onStateChanged(newState, hint)
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(service).also { it.setRecognitionListener(listener) }
        }.getOrNull()?.also { recognizer = it }
    }

    private fun startListening(confirmMode: Boolean) {
        if (!offerVisible || !prefs.voiceRejectEnabled) return
        val speech = ensureRecognizer() ?: run { setState(VoiceState.UNAVAILABLE); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, service.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        runCatching { speech.cancel() }
        runCatching { speech.startListening(intent) }
            .onFailure { setState(VoiceState.UNAVAILABLE) }
            .onSuccess { setState(if (confirmMode) VoiceState.CONFIRMING else VoiceState.LISTENING) }
    }

    private fun scheduleRestart(delayMs: Long, confirmMode: Boolean) {
        restartRunnable?.let(handler::removeCallbacks)
        restartRunnable = Runnable {
            restartRunnable = null
            if (offerVisible && prefs.voiceRejectEnabled) startListening(confirmMode)
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun stopAll() {
        restartRunnable?.let(handler::removeCallbacks)
        restartRunnable = null
        confirmDeadline?.let(handler::removeCallbacks)
        confirmDeadline = null
        runCatching { recognizer?.cancel() }
    }

    private fun handleHypotheses(hypotheses: List<String>, final: Boolean) {
        if (hypotheses.isEmpty()) return
        when (state) {
            VoiceState.LISTENING -> {
                if (VoiceKeywordMatcher.matches(hypotheses, prefs.voiceRejectKeyword)) {
                    // 1.2: domyslnie haslo = odrzucenie od razu, bez pytania o potwierdzenie.
                    if (prefs.voiceRejectRequireConfirmation) enterConfirming() else executeReject()
                } else if (final) {
                    scheduleRestart(250L, confirmMode = false)
                }
            }
            VoiceState.CONFIRMING -> {
                when {
                    VoiceKeywordMatcher.isNegation(hypotheses) -> {
                        confirmDeadline?.let(handler::removeCallbacks)
                        speak(tr("Anulowano", "Cancelled", "Скасовано", "Отменено"))
                        scheduleRestart(400L, confirmMode = false)
                    }
                    VoiceKeywordMatcher.isConfirmation(hypotheses, prefs.voiceRejectConfirmWord) -> executeReject()
                    final -> scheduleRestart(200L, confirmMode = true)
                }
            }
            else -> Unit
        }
    }

    private fun enterConfirming() {
        runCatching { recognizer?.cancel() }
        setState(
            VoiceState.CONFIRMING,
            tr(
                "Na pewno odrzucić? Powiedz: ${prefs.voiceRejectConfirmWord}",
                "Really decline? Say: ${prefs.voiceRejectConfirmWord}",
                "Точно відхилити? Скажи: ${prefs.voiceRejectConfirmWord}",
                "Точно отклонить? Скажи: ${prefs.voiceRejectConfirmWord}"
            )
        )
        HapticLanguage.offer(service, ProfitabilityStatus.ALMOST_PROFITABLE)
        val delay = if (prefs.voiceRejectSpeakPrompt) {
            speak(tr("Odrzucić? Powiedz ${prefs.voiceRejectConfirmWord}", "Decline? Say ${prefs.voiceRejectConfirmWord}", "Відхилити? Скажи ${prefs.voiceRejectConfirmWord}", "Отклонить? Скажи ${prefs.voiceRejectConfirmWord}"))
            1_400L
        } else {
            150L
        }
        scheduleRestart(delay, confirmMode = true)
        confirmDeadline?.let(handler::removeCallbacks)
        confirmDeadline = Runnable {
            confirmDeadline = null
            if (state == VoiceState.CONFIRMING) {
                runCatching { recognizer?.cancel() }
                scheduleRestart(200L, confirmMode = false)
            }
        }.also { handler.postDelayed(it, CONFIRM_WINDOW_MS) }
    }

    private fun executeReject() {
        confirmDeadline?.let(handler::removeCallbacks)
        confirmDeadline = null
        runCatching { recognizer?.cancel() }
        setState(VoiceState.EXECUTING, tr("Odrzucam…", "Declining…", "Відхиляю…", "Отклоняю…"))
        HapticLanguage.offer(service, ProfitabilityStatus.UNPROFITABLE)
        onConfirmedReject()
    }

    private fun speak(text: String) {
        if (!prefs.voiceRejectSpeakPrompt) return
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(service) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    runCatching { tts?.language = Locale.forLanguageTag(languageTag()) }
                    runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fujara-voice") }
                }
            }
            return
        }
        if (ttsReady) runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fujara-voice") }
    }

    private fun languageTag(): String = when (prefs.languageCode) {
        "en" -> "en-GB"
        "uk" -> "uk-UA"
        "ru" -> "ru-RU"
        else -> "pl-PL"
    }

    private fun tr(pl: String, en: String, uk: String, ru: String): String = when (prefs.languageCode) {
        "en" -> en
        "uk" -> uk
        "ru" -> ru
        else -> pl
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!offerVisible) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_CLIENT -> {
                    consecutiveErrors++
                    if (consecutiveErrors > 3) { setState(VoiceState.UNAVAILABLE); return }
                    scheduleRestart(900L, state == VoiceState.CONFIRMING)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                    scheduleRestart(900L, state == VoiceState.CONFIRMING)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(150L, state == VoiceState.CONFIRMING)
                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors > 6) { setState(VoiceState.UNAVAILABLE); return }
                    scheduleRestart(700L, state == VoiceState.CONFIRMING)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            handleHypotheses(list, final = false)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            handleHypotheses(list, final = true)
        }
    }

    private companion object {
        const val CONFIRM_WINDOW_MS = 7_000L
    }
}

/** Klika "Odrzuć" w aplikacji kurierskiej (Wolt / Pyszne / Stuart) przez Accessibility. */
object RejectActionPerformer {
    private val confirmDialogLabel = Regex("""(?i)^(?:odrzu[cć]|tak(?:,\s*odrzu[cć])?|potwierd[zź]|decline|yes|confirm|так|да)\b""")

    fun perform(service: AccessibilityService, isDeliveryPackage: (String) -> Boolean): Boolean {
        val node = findRejectNode(service, isDeliveryPackage) ?: return false
        return click(service, node)
    }

    /**
     * Po kliknieciu "Odrzuć" niektore aplikacje pokazuja dodatkowe potwierdzenie.
     *
     * BEZPIECZNIK: Wolt pokazuje "Na pewno chcesz odrzucić tę dostawę? Stracisz zarobki…"
     * gdy "Odrzuć" dotyczylo JUZ PRZYJETEJ dostawy, a nie nowej oferty. Takiego dialogu
     * nigdy nie potwierdzamy – cofamy go, zeby nie zrzucic kursu.
     */
    fun confirmIfDialog(service: AccessibilityService, isDeliveryPackage: (String) -> Boolean): ConfirmOutcome {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val texts = StringBuilder()
        for (window in service.windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (!isDeliveryPackage(pkg)) continue
            collect(root, candidates, predicate = { text -> confirmDialogLabel.containsMatchIn(text.trim()) && text.trim().length <= 24 })
            collectAllText(root, texts, 0)
        }
        val all = texts.toString()
        if (dropDeliveryDialog.containsMatchIn(all)) {
            val cancel = mutableListOf<AccessibilityNodeInfo>()
            for (window in service.windows) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                if (!isDeliveryPackage(root.packageName?.toString().orEmpty())) continue
                collect(root, cancel, predicate = { text -> cancelLabel.containsMatchIn(text.trim()) && text.trim().length <= 30 })
            }
            val cancelNode = cancel.firstOrNull { it.isClickable } ?: cancel.firstOrNull()
            if (cancelNode != null) click(service, cancelNode) else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return ConfirmOutcome.BLOCKED_DROP_DELIVERY
        }
        val best = candidates.firstOrNull { it.isClickable } ?: candidates.firstOrNull() ?: return ConfirmOutcome.NO_DIALOG
        return if (click(service, best)) ConfirmOutcome.CONFIRMED else ConfirmOutcome.NO_DIALOG
    }

    enum class ConfirmOutcome { NO_DIALOG, CONFIRMED, BLOCKED_DROP_DELIVERY }

    private val dropDeliveryDialog = Regex("""(?i)stracisz\s+zarobki|odrzuci[cć]\s+t[eę]\s+dostaw[eę]|lose\s+(?:your\s+)?earnings|decline\s+this\s+delivery""")
    private val cancelLabel = Regex("""(?i)^(?:rozpocznij\s+odbi[oó]r|anuluj|wr[oó][cć]|cancel|back|zamknij|nie)$""")

    private fun collectAllText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 60) return
        node.text?.let { out.append(it).append('\n') }
        node.contentDescription?.let { out.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, out, depth + 1)
        }
    }

    private fun findRejectNode(service: AccessibilityService, isDeliveryPackage: (String) -> Boolean): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (window in service.windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (!isDeliveryPackage(pkg)) continue
            collect(root, candidates, predicate = { text -> DecisionButtons.isRejectLabel(text) })
        }
        if (candidates.isEmpty()) {
            service.rootInActiveWindow?.let { root ->
                if (isDeliveryPackage(root.packageName?.toString().orEmpty())) {
                    collect(root, candidates, predicate = { text -> DecisionButtons.isRejectLabel(text) })
                }
            }
        }
        return candidates.firstOrNull { it.isClickable } ?: candidates.firstOrNull()
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, predicate: (String) -> Boolean, depth: Int = 0) {
        if (depth > 60) return
        val texts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        if (texts.any { it.isNotBlank() && predicate(it) }) {
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(true)
            if (visible) out += clickableSelfOrAncestor(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, predicate, depth + 1)
        }
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return node
    }

    private fun click(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return tap(service, bounds.exactCenterX(), bounds.exactCenterY())
    }

    fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }
}

/** Pomocnik do sprawdzania zgody na mikrofon z poziomu Activity/Settings. */
object VoicePermissions {
    fun recordAudioGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun locationGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
