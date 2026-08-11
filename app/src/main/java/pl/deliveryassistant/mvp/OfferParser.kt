package pl.deliveryassistant.mvp

object OfferParser {

    /*
     * Obsługuje m.in.:
     *
     * 17,52 zł
     * 17.52 PLN
     * 200 zł
     * PLN28.61
     * PLN 28.61
     */
    private val amountRegex = Regex(
        """(?:(?:PLN)\s*(\d{1,4}(?:[.,]\d{1,2})?)|(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:zł|zl|PLN))""",
        RegexOption.IGNORE_CASE
    )

    /*
     * 3,8 km
     * 3.8 km
     * 13.0 km
     * 50 km
     */
    private val distanceRegex = Regex(
        """(?<!\d)(\d{1,4}(?:[.,]\d{1,2})?)\s*km\b""",
        RegexOption.IGNORE_CASE
    )

    /*
     * 15 min
     * 39 min
     */
    private val durationRegex = Regex(
        """(?<!\d)(\d{1,3})\s*min\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Offer? {

        val normalized = text
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val amounts = amountRegex
            .findAll(normalized)
            .toList()

        if (amounts.isEmpty()) {
            return null
        }

        val candidates = mutableListOf<Candidate>()

        for ((index, amountMatch) in amounts.withIndex()) {

            /*
             * Kwota może być w grupie 1:
             * PLN28.61
             *
             * albo grupie 2:
             * 28.61 PLN
             */
            val amountText =
                amountMatch.groupValues
                    .drop(1)
                    .firstOrNull { it.isNotBlank() }
                    ?: continue

            val amount =
                amountText.toNumber()
                    ?: continue

            /*
             * Koniec tej oferty = początek następnej kwoty.
             */
            val nextAmountStart =
                amounts
                    .getOrNull(index + 1)
                    ?.range
                    ?.first
                    ?: normalized.length

            /*
             * Maksymalnie analizujemy ok. 500 znaków
             * po znalezionej kwocie.
             */
            val searchEnd = minOf(
                nextAmountStart,
                amountMatch.range.last + 1 + 500,
                normalized.length
            )

            if (
                searchEnd <=
                amountMatch.range.last + 1
            ) {
                continue
            }

            val block =
                normalized.substring(
                    amountMatch.range.last + 1,
                    searchEnd
                )

            /*
             * WAŻNE:
             *
             * Szukamy dystansu i czasu niezależnie.
             *
             * Dzięki temu działają oba formaty:
             *
             * 3.8 km
             * 15 min
             *
             * ORAZ:
             *
             * 39 min (13.0 km) total
             */
            val distanceMatch =
                distanceRegex.find(block)
                    ?: continue

            val distance =
                distanceMatch
                    .groupValues[1]
                    .toNumber()
                    ?: continue

            val duration =
                durationRegex
                    .find(block)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()

            if (
                amount <= 0.0 ||
                distance <= 0.0
            ) {
                continue
            }

            candidates += Candidate(
                startPosition =
                    amountMatch.range.first,

                offer = Offer(
                    amountPln = amount,
                    distanceKm = distance,
                    durationMinutes = duration
                )
            )
        }

        /*
         * Jeśli na ekranie jest kilka ofert,
         * używamy ostatniej znalezionej.
         */
        return candidates
            .maxByOrNull {
                it.startPosition
            }
            ?.offer
    }

    private data class Candidate(
        val startPosition: Int,
        val offer: Offer
    )

    private fun String.toNumber(): Double? {
        return replace(',', '.')
            .toDoubleOrNull()
    }
}