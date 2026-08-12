package pl.deliveryassistant.mvp


object OfferParser {

    /*
     * OCR potrafi rozdzielac litery jednostek spacjami, np. "k m" albo "m i n".
     * Regexy sa celowo tolerancyjne na taki zapis i typowe pomylki OCR.
     */
    private const val CURRENCY = "(?:P\\s*L\\s*N|z\\s*[łl])"
    private const val KM = "k\\s*(?:m|rn)"
    private const val MIN = "(?:m|rn)\\s*i\\s*n"

    private val amountRegex = Regex(
        """(?:(?:$CURRENCY)\s*(\d{1,4}(?:[.,]\d{1,2})?)|(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:$CURRENCY))""",
        RegexOption.IGNORE_CASE
    )

    // Fallback, gdy OCR zgubi PLN/zl. Wymagamy dwoch miejsc po separatorze,
    // zeby nie brac np. zwyklego dystansu 5.4 jako ceny.
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
        """(?:(?:dostarcz)\s*(?:na|do)\s*|(?:deliver|delivery|drop\s*off)\s*(?:by|at)\s*)(\d{1,2})\s*[:.]\s*(\d{2})""",
        RegexOption.IGNORE_CASE
    )

    private val pickupTimeRegex = Regex(
        """(?:odbierz|pick\s*up|pickup)\s*(?:na|do|by)?\s*(\d{1,2})\s*[:.]\s*(\d{2})""",
        RegexOption.IGNORE_CASE
    )

    /*
     * Uber czesto pokazuje karte w formacie:
     * PLN17.71
     * 31 min (22.4 km) total
     *
     * Ten wzorzec ma pierwszenstwo. Chroni przed bledem, w ktorym niewidoczny
     * element drzewa Accessibility zawierajacy np. "1.00 PLN" byl wybierany
     * zamiast ceny widocznej na karcie.
     */
    private val uberDeliveryPrefixedCardRegex = Regex(
        """(?is)\b(?:delivery|dostawa)\b[\s\S]{0,180}?P\s*L\s*N\s*(\d{1,3}[.,]\d{2})[\s\S]{0,320}?(\d{1,3})\s*$MIN\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*\)\s*(?:total|łącznie|lacznie|razem)?"""
    )

    private val uberDeliveryCardRegex = Regex(
        """(?is)\b(?:delivery|dostawa)\b[\s\S]{0,180}?(?:$CURRENCY\s*)?(\d{1,3}[.,]\d{2})\s*(?:$CURRENCY)?[\s\S]{0,320}?(\d{1,3})\s*$MIN\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*\)\s*(?:total|łącznie|lacznie|razem)?"""
    )

    private val uberCardRegex = Regex(
        """(?is)(?:$CURRENCY\s*)?(\d{1,3}[.,]\d{2})\s*(?:$CURRENCY)?[\s\S]{0,320}?(\d{1,3})\s*$MIN\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*\)\s*(?:total|łącznie|lacznie|razem)?"""
    )

    fun parse(text: String): Offer? {
        val normalized = normalize(text)

        parseUberCard(normalized, uberDeliveryPrefixedCardRegex)?.let { return it }
        parseUberCard(normalized, uberDeliveryCardRegex)?.let { return it }
        parseUberCard(normalized, uberCardRegex)?.let { return it }

        val amountMatches = findAmountMatches(normalized)
        if (amountMatches.isEmpty()) return null

        val distances = distanceRegex.findAll(normalized).toList()
        if (distances.isEmpty()) return null

        val candidates = amountMatches.mapNotNull { amountMatch ->
            val amount = amountMatch.value.toNumber() ?: return@mapNotNull null
            if (amount !in 0.01..1000.0) return@mapNotNull null

            val distanceMatch = distances
                .filter { matchDistance(it, amountMatch) <= 900 }
                .minByOrNull { matchDistance(it, amountMatch) }
                ?: return@mapNotNull null

            val distance = distanceMatch.groupValues.getOrNull(1)?.toNumber()
                ?: return@mapNotNull null
            if (distance !in 0.01..500.0) return@mapNotNull null

            val localStart = if (distanceMatch.range.first < amountMatch.start) {
                (distanceMatch.range.first - 80).coerceAtLeast(0)
            } else {
                amountMatch.start
            }
            val localEnd = maxOf(amountMatch.endExclusive, distanceMatch.range.last + 1)
                .plus(650)
                .coerceAtMost(normalized.length)
            val block = normalized.substring(localStart, localEnd)

            val pickupTime = pickupTimeRegex.find(block)?.toMinuteOfDay()
            val deliveryTime = deliveryTimeRegex.find(block)?.toMinuteOfDay()

            val directDuration = if (deliveryTime == null) {
                findTotalDuration(block)
                    ?: findDurationOnDistanceLine(block)
                    ?: findBestNonPickupDuration(block)
            } else {
                null
            }

            val score = candidateScore(
                text = normalized,
                amountMatch = amountMatch,
                distanceMatch = distanceMatch,
                durationMinutes = directDuration,
                localBlock = block
            )

            Candidate(
                score = score,
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

        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { it.startPosition }
        )?.offer
    }

    private fun parseUberCard(text: String, regex: Regex): Offer? {
        for (match in regex.findAll(text)) {
            val amount = match.groupValues.getOrNull(1)?.toNumber() ?: continue
            val duration = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            val distance = match.groupValues.getOrNull(3)?.toNumber() ?: continue

            if (amount !in 0.01..1000.0) continue
            if (duration !in 1..360) continue
            if (distance !in 0.01..500.0) continue

            val contextStart = (match.range.first - 180).coerceAtLeast(0)
            val contextEnd = (match.range.last + 220).coerceAtMost(text.length)
            val context = text.substring(contextStart, contextEnd)

            return Offer(
                amountPln = amount,
                distanceKm = distance,
                durationMinutes = duration,
                pickupTimeMinutesOfDay = pickupTimeRegex.find(context)?.toMinuteOfDay(),
                deliveryTimeMinutesOfDay = deliveryTimeRegex.find(context)?.toMinuteOfDay()
            )
        }
        return null
    }

    private fun candidateScore(
        text: String,
        amountMatch: AmountMatch,
        distanceMatch: MatchResult,
        durationMinutes: Int?,
        localBlock: String
    ): Int {
        var score = 0

        if (amountMatch.explicitCurrency) score += 60

        val gap = matchDistance(distanceMatch, amountMatch)
        score += (45 - gap / 18).coerceAtLeast(0)

        if (durationMinutes != null) score += 25
        if (totalRegex.containsMatchIn(localBlock)) score += 18

        val lower = localBlock.lowercase()
        if ("confirm" in lower || "zaakceptuj" in lower) score += 8
        if ("delivery" in lower || "dostawa" in lower) score += 6

        val line = lineContaining(text, amountMatch.start).lowercase()
        if ("pln" in line || "zł" in line || "zl" in line) score += 12

        val amount = amountMatch.value.toNumber() ?: 0.0
        if (amount >= 4.0) score += 3

        return score
    }

    private fun matchDistance(match: MatchResult, amount: AmountMatch): Int {
        return when {
            match.range.first >= amount.endExclusive -> match.range.first - amount.endExclusive
            amount.start >= match.range.last + 1 -> amount.start - (match.range.last + 1)
            else -> 0
        }
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
                endExclusive = match.range.last + 1,
                explicitCurrency = true
            )
        }.toList()

        val bare = bareAmountRegex.findAll(text).mapNotNull { match ->
            val line = lineContaining(text, match.range.first)

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
                endExclusive = match.range.last + 1,
                explicitCurrency = false
            )
        }.toList()

        return (withCurrency + bare)
            .distinctBy { "${it.start}:${it.endExclusive}" }
            .sortedBy { it.start }
    }

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

    private fun findDurationOnDistanceLine(block: String): Int? {
        for (line in block.lines()) {
            if (!distanceRegex.containsMatchIn(line)) continue

            val duration = durationRegex.find(line)?.minutes()
            if (duration != null) return duration
        }

        return null
    }

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
        val endExclusive: Int,
        val explicitCurrency: Boolean
    )

    private data class Candidate(
        val score: Int,
        val startPosition: Int,
        val offer: Offer
    )
}
