package pl.deliveryassistant.mvp

object OfferParser {

    /*
     * OCR potrafi rozdzielać litery jednostek spacjami, np. "k m" albo "m i n".
     * Dlatego regexy są trochę bardziej tolerancyjne niż zwykłe "km" / "min".
     */
    private const val CURRENCY = "(?:P\\s*L\\s*N|z\\s*[łl])"
    private const val KM = "k\\s*(?:m|rn)"
    private const val MIN = "(?:m|rn)\\s*i\\s*n"

    private val amountRegex = Regex(
        """(?:(?:$CURRENCY)\s*(\d{1,4}(?:[.,]\d{1,2})?)|(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:$CURRENCY))""",
        RegexOption.IGNORE_CASE
    )

    // Fallback, gdy OCR zgubi PLN/zł. Wymagamy dokładnie dwóch miejsc po separatorze,
    // żeby nie brać np. zwykłego dystansu 5.4 jako ceny.
    private val bareAmountRegex = Regex(
        """(?<![\d:])(\d{1,3}[.,]\d{2})(?!\d)"""
    )

    private val distanceRegex = Regex(
        """(?<!\d)(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\b""",
        RegexOption.IGNORE_CASE
    )

    private val durationRegex = Regex(
        """(?<!\d)(\d{1,3})\s*$MIN\b""",
        RegexOption.IGNORE_CASE
    )

    private val totalRegex = Regex(
        """\b(?:total|łącznie|lacznie|razem|całkowit\w*|calkowit\w*)\b""",
        RegexOption.IGNORE_CASE
    )

    private val pickupEtaRegex = Regex(
        """\b(?:pickup|pick\s*up|odbierz|odbiór|odbior|do\s+odbioru|odebrać|odebrac)\b""",
        RegexOption.IGNORE_CASE
    )

    private val deliveryTimeRegex = Regex(
        """(?:dostarcz|deliver|delivery|drop\s*off)\s*(?:na|do|by)?\s*(\d{1,2})\s*[:.]\s*(\d{2})""",
        RegexOption.IGNORE_CASE
    )

    private val pickupTimeRegex = Regex(
        """(?:odbierz|pick\s*up|pickup)\s*(?:na|do|by)?\s*(\d{1,2})\s*[:.]\s*(\d{2})""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Offer? {
        val normalized = normalize(text)
        val amountMatches = findAmountMatches(normalized)

        if (amountMatches.isEmpty()) return null

        val candidates = mutableListOf<Candidate>()

        for ((index, amountMatch) in amountMatches.withIndex()) {
            val amount = amountMatch.value.toNumber() ?: continue
            if (amount !in 0.01..1000.0) continue

            val nextAmountStart = amountMatches
                .getOrNull(index + 1)
                ?.start
                ?: normalized.length

            val blockStart = amountMatch.endExclusive
            val searchEnd = minOf(
                nextAmountStart,
                blockStart + 1400,
                normalized.length
            )

            if (searchEnd <= blockStart) continue

            val block = normalized.substring(blockStart, searchEnd)

            val distance = distanceRegex
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.toNumber()
                ?: continue

            if (distance !in 0.01..500.0) continue

            val pickupTime = pickupTimeRegex
                .find(block)
                ?.toMinuteOfDay()

            val deliveryTime = deliveryTimeRegex
                .find(block)
                ?.toMinuteOfDay()

            val directDuration = if (deliveryTime == null) {
                findTotalDuration(block)
                    ?: findDurationOnDistanceLine(block)
                    ?: findBestNonPickupDuration(block)
            } else {
                // Pyszne ma planowaną godzinę dostawy. Nie podmieniamy jej przypadkowym
                // "3 min" z innej części ekranu.
                null
            }

            candidates += Candidate(
                startPosition = amountMatch.start,
                offer = Offer(
                    amountPln = amount,
                    distanceKm = distance,
                    durationMinutes = directDuration,
                    pickupTimeMinutesOfDay = pickupTime,
                    deliveryTimeMinutesOfDay = deliveryTime
                )
            )
        }

        // Przy kilku kartach ofert aktualna zwykle jest niżej / później w tekście.
        return candidates.maxByOrNull { it.startPosition }?.offer
    }

    private fun normalize(text: String): String =
        text
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .joinToString("\n") { line ->
                line.replace(Regex("[\\t ]+"), " ").trim()
            }

    private fun findAmountMatches(text: String): List<AmountMatch> {
        val withCurrency = amountRegex.findAll(text).mapNotNull { match ->
            val value = match.groupValues
                .drop(1)
                .firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null

            AmountMatch(
                value = value,
                start = match.range.first,
                endExclusive = match.range.last + 1
            )
        }.toList()

        if (withCurrency.isNotEmpty()) return withCurrency

        return bareAmountRegex.findAll(text).mapNotNull { match ->
            val line = lineContaining(text, match.range.first)

            // Nie traktujemy jako ceny wartości z linii czasu, dystansu albo minut.
            if (
                distanceRegex.containsMatchIn(line) ||
                durationRegex.containsMatchIn(line) ||
                pickupTimeRegex.containsMatchIn(line) ||
                deliveryTimeRegex.containsMatchIn(line)
            ) {
                return@mapNotNull null
            }

            val value = match.groupValues[1]
            val numeric = value.toNumber() ?: return@mapNotNull null
            if (numeric !in 2.0..500.0) return@mapNotNull null

            AmountMatch(
                value = value,
                start = match.range.first,
                endExclusive = match.range.last + 1
            )
        }.toList()
    }

    /**
     * Najwyższy priorytet ma czas powiązany ze słowem "total" / "łącznie".
     * Obsługuje zarówno "26 min ... total", jak i "total 26 min".
     */
    private fun findTotalDuration(block: String): Int? {
        val durations = durationRegex.findAll(block).toList()
        if (durations.isEmpty()) return null

        for (totalMatch in totalRegex.findAll(block)) {
            val before = durations
                .asSequence()
                .filter { it.range.last < totalMatch.range.first }
                .filter { totalMatch.range.first - it.range.last <= 180 }
                .maxByOrNull { it.range.last }
                ?.minutes()

            if (before != null) return before

            val after = durations
                .asSequence()
                .filter { it.range.first > totalMatch.range.last }
                .filter { it.range.first - totalMatch.range.last <= 180 }
                .minByOrNull { it.range.first }
                ?.minutes()

            if (after != null) return after
        }

        return null
    }

    /**
     * Bardzo ważny fallback dla Ubera: OCR czasem zgubi słowo "total", ale nadal
     * odczyta np. "26 min (5.4 km)". Czas z tej samej linii co dystans ma wtedy
     * większy sens niż "Pickup in 3 min".
     */
    private fun findDurationOnDistanceLine(block: String): Int? {
        for (line in block.lines()) {
            if (!distanceRegex.containsMatchIn(line)) continue

            val duration = durationRegex.find(line)?.minutes()
            if (duration != null) return duration
        }

        return null
    }

    /**
     * Ostatni fallback: bierzemy czas w minutach tylko z linii, która nie wygląda
     * jak ETA do odbioru. Dzięki temu "Pickup in 3 min" nie stanie się czasem całej dostawy.
     */
    private fun findBestNonPickupDuration(block: String): Int? {
        for (line in block.lines()) {
            if (pickupEtaRegex.containsMatchIn(line)) continue

            val duration = durationRegex.find(line)?.minutes()
            if (duration != null) return duration
        }

        return null
    }

    private fun MatchResult.minutes(): Int? =
        groupValues
            .getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..360 }

    private fun MatchResult.toMinuteOfDay(): Int? {
        val hour = groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = groupValues.getOrNull(2)?.toIntOrNull() ?: return null

        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun lineContaining(text: String, position: Int): String {
        val start = text.lastIndexOf('\n', startIndex = (position - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', startIndex = position)
            .let { if (it < 0) text.length else it }

        return text.substring(start, end)
    }

    private fun String.toNumber(): Double? =
        replace(',', '.').toDoubleOrNull()

    private data class AmountMatch(
        val value: String,
        val start: Int,
        val endExclusive: Int
    )

    private data class Candidate(
        val startPosition: Int,
        val offer: Offer
    )
}
