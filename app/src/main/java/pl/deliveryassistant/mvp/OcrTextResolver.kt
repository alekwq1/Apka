package pl.deliveryassistant.mvp

import com.google.mlkit.vision.text.Text

/**
 * ML Kit udostepnia jednoczesnie gotowy result.text oraz bloki/linie z pozycja.
 * Rozne ekrany potrafia dawac lepszy wynik w innej reprezentacji, dlatego parser
 * probuje kilku wariantow i wybiera najbardziej kompletna oferte.
 */
object OcrTextResolver {

    data class Resolution(
        val offer: Offer,
        val text: String
    )

    fun resolve(result: Text): Resolution? {
        return candidates(result)
            .mapIndexedNotNull { index, text ->
                val offer = OfferParser.parse(text) ?: return@mapIndexedNotNull null
                Candidate(
                    resolution = Resolution(offer = offer, text = text),
                    score = offerScore(offer),
                    order = index
                )
            }
            .maxWithOrNull(
                compareBy<Candidate> { it.score }
                    .thenBy { -it.order }
            )
            ?.resolution
    }

    /** Tekst najbardziej czytelny dla ekranu diagnostycznego. */
    fun displayText(result: Text): String =
        candidates(result).firstOrNull().orEmpty()

    /** Laczymy warianty tylko do rozpoznania nazwy aplikacji/kontekstu. */
    fun recognitionText(result: Text): String =
        candidates(result).joinToString("\n")

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

        val blocks = result.textBlocks
            .joinToString("\n") { block -> block.text.trim() }
            .trim()

        // result.text jest pierwszy, bo ML Kit zwykle najlepiej odtwarza w nim
        // naturalna kolejnosc czytania. Posortowane linie sa fallbackiem.
        return listOf(raw, sortedLines, blocks)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun offerScore(offer: Offer): Int {
        var score = 10 // kwota + dystans sa wymagane przez parser
        if (offer.durationMinutes != null) score += 5
        if (offer.deliveryTimeMinutesOfDay != null) score += 4
        if (offer.pickupTimeMinutesOfDay != null) score += 2
        return score
    }

    private data class Candidate(
        val resolution: Resolution,
        val score: Int,
        val order: Int
    )

    private data class OcrLine(
        val text: String,
        val top: Int,
        val left: Int
    )
}
