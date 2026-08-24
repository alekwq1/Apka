package pl.fujara.app

/**
 * Stabilizuje ekran szczegolow Pyszne przed pokazaniem nakladki.
 *
 * Pyszne podczas przechodzenia miedzy zleceniami potrafi przez chwile zostawic
 * tekst poprzedniego zlecenia. Nakladka nie powinna wtedy mignac starymi danymi.
 * Po opuszczeniu listy/szczegolow czekamy na powtorzenie tej samej tozsamosci
 * zlecenia w kolejnych odczytach. Przy bezposrednim przejsciu blokujemy ostatnio
 * pokazane ID do czasu zobaczenia nowego.
 */
class PyszneDisplayGate(
    private val requiredConfirmations: Int = 2,
    private val maxConfirmationGapMs: Long = 3_000L,
    private val blockedIdentityHoldMs: Long = 2_500L
) {
    private var lastShownIdentity: String? = null
    private var blockedIdentity: String? = null
    private var blockedSinceAt: Long = 0L
    private var candidateIdentity: String? = null
    private var candidateConfirmations: Int = 0
    private var lastCandidateAt: Long = 0L
    private var transitionActive: Boolean = true

    /** Wywolaj, gdy user klika/przechodzi dalej z aktualnych szczegolow. */
    fun beginDirectTransition(nowMs: Long = androidLikeNow()) {
        blockedIdentity = lastShownIdentity
        blockedSinceAt = nowMs
        candidateIdentity = null
        candidateConfirmations = 0
        lastCandidateAt = 0L
        transitionActive = true
    }

    /** Wywolaj, gdy na pewno widzimy liste/historie zamiast szczegolow zlecenia. */
    fun noteNavigationScreen() {
        // Po powrocie do listy wolno ponownie otworzyc nawet to samo zlecenie,
        // dlatego nie blokujemy starego ID na stale. Nadal wymagamy stabilizacji.
        blockedIdentity = null
        blockedSinceAt = 0L
        candidateIdentity = null
        candidateConfirmations = 0
        lastCandidateAt = 0L
        transitionActive = true
    }

    /** Konflikt zrodel (np. OCR widzi stare ID, Accessibility nowe) = czekamy. */
    fun noteConflict() {
        candidateIdentity = null
        candidateConfirmations = 0
        lastCandidateAt = 0L
        transitionActive = true
    }

    /**
     * true oznacza, ze te dane mozna juz pokazac. Po stabilnym pokazaniu tego
     * samego zlecenia kolejne odswiezenia sa natychmiastowe.
     */
    fun shouldShow(identity: String, nowMs: Long): Boolean {
        val normalized = identity.trim().uppercase()
        if (normalized.isBlank()) return false

        if (!transitionActive && normalized == lastShownIdentity) return true
        if (blockedIdentity != null && normalized == blockedIdentity) {
            val stillBlocked = blockedSinceAt > 0L && nowMs - blockedSinceAt in 0 until blockedIdentityHoldMs
            if (stillBlocked) return false
            blockedIdentity = null
            blockedSinceAt = 0L
            candidateIdentity = null
            candidateConfirmations = 0
        }

        val sameCandidate = normalized == candidateIdentity &&
            lastCandidateAt > 0L &&
            nowMs - lastCandidateAt in 0..maxConfirmationGapMs

        if (sameCandidate) {
            candidateConfirmations += 1
        } else {
            candidateIdentity = normalized
            candidateConfirmations = 1
        }
        lastCandidateAt = nowMs

        if (candidateConfirmations < requiredConfirmations) return false

        lastShownIdentity = normalized
        blockedIdentity = null
        blockedSinceAt = 0L
        transitionActive = false
        candidateIdentity = normalized
        return true
    }

    fun lastShown(): String? = lastShownIdentity

    private fun androidLikeNow(): Long = System.nanoTime() / 1_000_000L
}
