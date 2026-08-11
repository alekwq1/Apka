package pl.deliveryassistant.mvp

object OfferParser {

    private val amountRegex = Regex(
        """(?:(?:PLN)\s*(\d{1,4}(?:[.,]\d{1,2})?)|(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:zł|zl|PLN))""",
        RegexOption.IGNORE_CASE
    )

    private val distanceRegex = Regex(
        """(?<!\d)(\d{1,4}(?:[.,]\d{1,2})?)\s*km\b""",
        RegexOption.IGNORE_CASE
    )

    private val durationRegex = Regex(
        """(?<!\d)(\d{1,3})\s*min\b""",
        RegexOption.IGNORE_CASE
    )

    private val totalRegex = Regex(
        """\btotal\b""",
        RegexOption.IGNORE_CASE
    )

    // Pyszne / Just Eat Courier: "Odbierz na 20:54".
    private val pickupTimeRegex = Regex(
        """(?:odbierz|pick\s*up|pickup)\s*(?:na|do|by)?\s*(\d{1,2})\s*[:.]\s*(\d{2})""",
        RegexOption.IGNORE_CASE
    )

    // Pyszne / Just Eat Courier: "Dostarcz na 20:57".
    private val deliveryTimeRegex = Regex(
        """(?:dostarcz|deliver|delivery|drop\s*off)\s*(?:na|do|by)?\s*(\d{1,2})\s*[:.]\s*(\d{2})""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Offer? {
        val normalized = text
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val amounts = amountRegex.findAll(normalized).toList()

        if (amounts.isEmpty()) {
            return null
        }

        val candidates = mutableListOf<Candidate>()

        for ((index, amountMatch) in amounts.withIndex()) {

            val amountText = amountMatch.groupValues
                .drop(1)
                .firstOrNull { it.isNotBlank() }
                ?: continue

            val amount = amountText.toNumber()
                ?: continue

            val nextAmountStart = amounts
                .getOrNull(index + 1)
                ?.range
                ?.first
                ?: normalized.length

            val searchEnd = minOf(
                nextAmountStart,
                amountMatch.range.last + 1 + 1200,
                normalized.length
            )

            if (searchEnd <= amountMatch.range.last + 1) {
                continue
            }

            val block = normalized.substring(
                amountMatch.range.last + 1,
                searchEnd
            )

            val distance = distanceRegex
                .find(block)
                ?.groupValues
                ?.get(1)
                ?.toNumber()
                ?: continue

            if (amount <= 0.0 || distance <= 0.0) {
                continue
            }

            val pickupTime = pickupTimeRegex
                .find(block)
                ?.toMinuteOfDay()

            val deliveryTime = deliveryTimeRegex
                .find(block)
                ?.toMinuteOfDay()

            /*
             * Uber może pokazać np.:
             *
             * Pickup in 3 min
             * 26 min (5.4 km) total
             *
             * Nie możemy wtedy brać pierwszego "3 min".
             * Najpierw szukamy czasu znajdującego się najbliżej
             * słowa "total".
             */
            val directDuration = if (deliveryTime == null) {

                findTotalDuration(block)
                    ?: durationRegex
                        .find(block)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?.takeIf { it in 1..360 }

            } else {
                /*
                 * Jeśli Pyszne podaje planowaną godzinę dostawy,
                 * nie bierzemy przypadkowego "xx min"
                 * z innej części ekranu.
                 */
                null
            }

            candidates += Candidate(
                startPosition = amountMatch.range.first,
                offer = Offer(
                    amountPln = amount,
                    distanceKm = distance,
                    durationMinutes = directDuration,
                    pickupTimeMinutesOfDay = pickupTime,
                    deliveryTimeMinutesOfDay = deliveryTime
                )
            )
        }

        /*
         * Jeśli na ekranie zostało kilka ofert,
         * zwykle aktualna oferta znajduje się jako ostatnia.
         */
        return candidates
            .maxByOrNull { it.startPosition }
            ?.offer
    }

    /**
     * Szuka całkowitego czasu oferty.
     *
     * Obsługuje między innymi:
     *
     * 26 min (5.4 km) total
     *
     * oraz OCR rozbijający tekst:
     *
     * 26 min (5.4 km)
     * total
     *
     * a także:
     *
     * total 26 min
     */
    private fun findTotalDuration(block: String): Int? {

        val durations = durationRegex
            .findAll(block)
            .toList()

        if (durations.isEmpty()) {
            return null
        }

        for (totalMatch in totalRegex.findAll(block)) {

            /*
             * Najpierw szukamy najbliższego czasu
             * znajdującego się PRZED słowem "total".
             */
            val before = durations
                .asSequence()
                .filter {
                    it.range.last < totalMatch.range.first
                }
                .filter {
                    totalMatch.range.first - it.range.last <= 160
                }
                .maxByOrNull {
                    it.range.last
                }

            val beforeMinutes = before
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf {
                    it in 1..360
                }

            if (beforeMinutes != null) {
                return beforeMinutes
            }

            /*
             * Fallback na wypadek, gdy OCR zwróci np.:
             *
             * total 26 min
             */
            val after = durations
                .asSequence()
                .filter {
                    it.range.first > totalMatch.range.last
                }
                .filter {
                    it.range.first - totalMatch.range.last <= 160
                }
                .minByOrNull {
                    it.range.first
                }

            val afterMinutes = after
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf {
                    it in 1..360
                }

            if (afterMinutes != null) {
                return afterMinutes
            }
        }

        return null
    }

    private fun MatchResult.toMinuteOfDay(): Int? {

        val hour = groupValues
            .getOrNull(1)
            ?.toIntOrNull()
            ?: return null

        val minute = groupValues
            .getOrNull(2)
            ?.toIntOrNull()
            ?: return null

        if (hour !in 0..23 || minute !in 0..59) {
            return null
        }

        return hour * 60 + minute
    }

    private data class Candidate(
        val startPosition: Int,
        val offer: Offer
    )

    private fun String.toNumber(): Double? =
        replace(',', '.').toDoubleOrNull()
}