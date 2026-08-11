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
        if (amounts.isEmpty()) return null

        val candidates = mutableListOf<Candidate>()

        for ((index, amountMatch) in amounts.withIndex()) {
            val amountText = amountMatch.groupValues
                .drop(1)
                .firstOrNull { it.isNotBlank() }
                ?: continue

            val amount = amountText.toNumber() ?: continue

            val nextAmountStart = amounts
                .getOrNull(index + 1)
                ?.range
                ?.first
                ?: normalized.length

            // W Pyszne godziny odbioru/dostawy są zwykle sporo niżej niż kwota.
            val searchEnd = minOf(
                nextAmountStart,
                amountMatch.range.last + 1 + 1200,
                normalized.length
            )

            if (searchEnd <= amountMatch.range.last + 1) continue

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

            if (amount <= 0.0 || distance <= 0.0) continue

            val pickupTime = pickupTimeRegex
                .find(block)
                ?.toMinuteOfDay()

            val deliveryTime = deliveryTimeRegex
                .find(block)
                ?.toMinuteOfDay()

            // Jeśli mamy planowaną godzinę dostawy (Pyszne), nie używamy
            // przypadkowej liczby "xx min" z innego fragmentu ekranu.
            val directDuration = if (deliveryTime == null) {
                durationRegex
                    .find(block)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..360 }
            } else {
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

        // Jeżeli ekran zawiera kilka ofert, najczęściej aktualna jest ostatnia.
        return candidates.maxByOrNull { it.startPosition }?.offer
    }

    private fun MatchResult.toMinuteOfDay(): Int? {
        val hour = groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = groupValues.getOrNull(2)?.toIntOrNull() ?: return null

        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private data class Candidate(
        val startPosition: Int,
        val offer: Offer
    )

    private fun String.toNumber(): Double? =
        replace(',', '.').toDoubleOrNull()
}
