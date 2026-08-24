package pl.fujara.app

import com.google.mlkit.vision.text.Text

/**
 * ML Kit udostepnia result.text oraz bloki/linie z pozycja. Rozne ekrany
 * potrafia dawac lepszy wynik w innej reprezentacji, dlatego sprawdzamy kilka
 * wariantow. Gdy znamy platforme, parser dostaje ja jawnie i nie zgaduje.
 */
object OcrTextResolver {

    data class Resolution(
        val offer: Offer,
        val text: String
    )

    fun resolve(result: Text, platform: CourierPlatform? = null): Resolution? {
        return candidates(result)
            .mapIndexedNotNull { index, text ->
                val offer = OfferParser.parse(text, platform) ?: return@mapIndexedNotNull null
                Candidate(
                    resolution = Resolution(offer = offer, text = text),
                    score = offerScore(offer),
                    order = index
                )
            }
            .maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { -it.order })
            ?.resolution
    }

    fun displayText(result: Text): String = candidates(result).firstOrNull().orEmpty()

    fun recognitionText(result: Text): String = candidates(result).joinToString("\n")

    private fun candidates(result: Text): List<String> {
        val raw = result.text.trim()
        val sortedLines = result.textBlocks
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
            .joinToString("\n") { it.text }
            .trim()

        val blocks = result.textBlocks.joinToString("\n") { it.text.trim() }.trim()
        return listOf(raw, sortedLines, blocks).filter { it.isNotBlank() }.distinct()
    }

    private fun offerScore(offer: Offer): Int {
        var score = 10
        if (offer.durationMinutes != null || offer.durationSeconds != null) score += 5
        if (offer.deliveryTimeMinutesOfDay != null) score += 4
        if (offer.pickupTimeMinutesOfDay != null) score += 2
        return score
    }

    private data class Candidate(val resolution: Resolution, val score: Int, val order: Int)
    private data class OcrLine(val text: String, val top: Int, val left: Int)
}
