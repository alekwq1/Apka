package pl.fujara.app

/**
 * Parser tekstu oferty. Najpierw uzywa formatu konkretnej platformy, a dopiero
 * pozniej bezpiecznego fallbacku. To jest celowe: ogolny regex nie powinien
 * brac np. napiwku, reszty gotowkowej albo czasu do restauracji za wartosc
 * calej oferty.
 */
object OfferParser {
    private const val CURRENCY = "(?:P[\\t ]*L[\\t ]*N|z[\\t ]*[łl])"
    private const val KM = "k[\\t ]*(?:m|rn)"
    private const val MIN = "(?:m|rn)[\\t ]*i[\\t ]*n"

    private val amountRegex = Regex(
        """(?:(?:$CURRENCY)[\t ]*(\d{1,4}(?:[.,]\d{1,2})?)|(\d{1,4}(?:[.,]\d{1,2})?)[\t ]*(?:$CURRENCY))""",
        RegexOption.IGNORE_CASE
    )
    private val bareAmountRegex = Regex("""(?<![\d:])(\d{1,3}[.,]\d{2})(?!\d)""")
    private val distanceRegex = Regex(
        """(?<!\d)(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\b""",
        RegexOption.IGNORE_CASE
    )
    private val durationRegex = Regex(
        """(?<!\d)(\d{1,3})\s*$MIN\b""",
        RegexOption.IGNORE_CASE
    )
    private val durationRangeRegex = Regex(
        """(?<!\d)(\d{1,3})\s*[-–—]\s*(\d{1,3})\s*$MIN\b""",
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
    /*
     * Pyszne pokazuje plan jako godziny zegarowe, np. "Dostarcz na 13:37".
     * OCR potrafi zgubic dwukropek albo odczytac go jako kropke/przecinek,
     * dlatego separator jest celowo bardziej tolerancyjny. Nadal wymagamy
     * slowa "Dostarcz" / "Odbierz", wiec nie bierzemy godzin z mapy lub paska.
     */
    private const val CLOCK_SEPARATOR = "(?:\\s*[:.;,·-]\\s*|\\s+)"
    private val deliveryTimeRegex = Regex(
        """(?:(?:dostarcz\w*)\s*(?:na|do)\s*|(?:deliver|delivery|drop\s*off)\s*(?:by|at)\s*)(\d{1,2})$CLOCK_SEPARATOR(\d{2})""",
        RegexOption.IGNORE_CASE
    )
    private val pickupTimeRegex = Regex(
        """(?:odbierz\w*|pick\s*up|pickup)\s*(?:na|do|by|at)?\s*(\d{1,2})$CLOCK_SEPARATOR(\d{2})""",
        RegexOption.IGNORE_CASE
    )
    private val deliveryCompactTimeRegex = Regex(
        """(?:(?:dostarcz\w*)\s*(?:na|do)\s*|(?:deliver|delivery|drop\s*off)\s*(?:by|at)\s*)(\d{3,4})(?!\d)""",
        RegexOption.IGNORE_CASE
    )
    private val pickupCompactTimeRegex = Regex(
        """(?:odbierz\w*|pick\s*up|pickup)\s*(?:na|do|by|at)?\s*(\d{3,4})(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    private val uberDeliveryPrefixedCardRegex = Regex(
        """(?is)\b(?:delivery|dostawa)\b[\s\S]{0,220}?P\s*L\s*N\s*(\d{1,3}[.,]\d{2})[\s\S]{0,360}?(\d{1,3})\s*$MIN\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*\)\s*(?:total|łącznie|lacznie|razem)?"""
    )
    private val uberDeliveryCardRegex = Regex(
        """(?is)\b(?:delivery|dostawa)\b[\s\S]{0,220}?(?:$CURRENCY\s*)?(\d{1,3}[.,]\d{2})\s*(?:$CURRENCY)?[\s\S]{0,360}?(\d{1,3})\s*$MIN\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*\)\s*(?:total|łącznie|lacznie|razem)?"""
    )
    private val uberCardRegex = Regex(
        """(?is)(?:$CURRENCY\s*)?(\d{1,3}[.,]\d{2})\s*(?:$CURRENCY)?[\s\S]{0,360}?(\d{1,3})\s*$MIN\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*\)\s*(?:total|łącznie|lacznie|razem)?"""
    )

    private val boltTotalRowRegex = Regex(
        """(?is)(\d{1,3}(?:[.,]\d{1,2})?)\s*$KM\s*[,·|]\s*(\d{1,3})\s*$MIN\s*[,·|]\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:$CURRENCY)"""
    )

    private const val EARNINGS_LABEL =
        """(?:spodziewan\w*\s+zarob\w*|szacowan\w*\s+zarob\w*|estimated\s+earnings|expected\s+earnings)"""

    private val woltPrefixedAmountBeforeLabelRegex = Regex(
        """(?is)(?:$CURRENCY)[\t ]*(\d{1,4}(?:[.,]\d{1,2})?)[\s\S]{0,160}?$EARNINGS_LABEL"""
    )
    private val woltAmountBeforeLabelRegex = Regex(
        """(?is)(\d{1,4}(?:[.,]\d{1,2})?)[\t ]*(?:$CURRENCY)[\s\S]{0,160}?$EARNINGS_LABEL"""
    )
    private val woltAmountAfterLabelRegex = Regex(
        """(?is)$EARNINGS_LABEL[\s\S]{0,140}?(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:$CURRENCY)"""
    )

    private val stuartAmountRegex = Regex(
        """(?is)(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:$CURRENCY)[\s\S]{0,160}?$EARNINGS_LABEL"""
    )
    private val milesTotalRegex = Regex(
        """(?i)(\d{1,3}(?:[.,]\d{1,2})?)\s*(?:mi|miles?)\b\s*(?:total|łącznie|lacznie)?"""
    )
    private val stopsDistanceRegex = Regex(
        """(?i)(\d{1,4}(?:[.,]\d{1,2})?)\s*$KM\s*(?:[·•|,-]\s*)?\d+\s*(?:stops?|przystank\w*)"""
    )

    fun parse(text: String, platform: CourierPlatform? = null): Offer? {
        val normalized = normalize(text)
        return when (platform) {
            // Dla rozpoznanej platformy wolimy brak wyniku niz pewny falszywy wynik
            // z mapy/tla. Fallback ogolny zostaje tylko tam, gdzie nie mamy jeszcze
            // stabilnego formatu platformy (Glovo / tryb globalny).
            CourierPlatform.UBER -> parseUber(normalized)
            CourierPlatform.WOLT -> parseWolt(normalized)
            CourierPlatform.BOLT -> parseBolt(normalized)
            CourierPlatform.PYSZNE -> parsePyszne(normalized)
            CourierPlatform.STUART -> parseStuart(normalized)
            CourierPlatform.GLOVO -> parseGeneric(normalized)
            CourierPlatform.GLOBAL, null ->
                parseUber(normalized)
                    ?: parseBolt(normalized)
                    ?: parseWolt(normalized)
                    ?: parseStuart(normalized)
                    ?: parseGeneric(normalized)
        }
    }

    private fun parseUber(text: String): Offer? {
        parseUberCard(text, uberDeliveryPrefixedCardRegex)?.let { return it }
        parseUberCard(text, uberDeliveryCardRegex)?.let { return it }
        parseUberCard(text, uberCardRegex)?.let { return it }
        parseUberStopsCard(text)?.let { return it }
        return null
    }


    private fun parseUberStopsCard(text: String): Offer? {
        val lower = text.lowercase()
        val hasUberOfferMarkers =
            ("pickup" in lower || "pick up" in lower || "odbiór" in lower || "odbior" in lower) &&
                ("delivery" in lower || "deliver" in lower || "dostawa" in lower)
        if (!hasUberOfferMarkers) return null

        val distanceMatch = stopsDistanceRegex.find(text) ?: return null
        val distance = distanceMatch.groupValues.getOrNull(1)?.toNumber() ?: return null
        if (distance !in 0.01..500.0) return null

        val amount = findAmountMatches(text)
            .filter { it.explicitCurrency }
            .filter { it.start <= distanceMatch.range.first }
            .minByOrNull { distanceMatch.range.first - it.endExclusive }
            ?.value
            ?.toNumber()
            ?: return null
        if (amount !in 0.01..1000.0) return null

        return Offer(
            amountPln = amount,
            distanceKm = distance,
            durationMinutes = null,
            pickupTimeMinutesOfDay = findPickupTime(text),
            deliveryTimeMinutesOfDay = findDeliveryTime(text)
        )
    }

    private fun parseBolt(text: String): Offer? {
        // Bolt na dole karty pokazuje podsumowanie calej trasy, np.
        // 7.7 km, 35 min, 20,62 zl. To ma pierwszenstwo przed ~17 min do lokalu.
        val match = boltTotalRowRegex.findAll(text).lastOrNull() ?: return null
        val distance = match.groupValues[1].toNumber() ?: return null
        val duration = match.groupValues[2].toIntOrNull() ?: return null
        val amount = match.groupValues[3].toNumber() ?: return null
        if (!valid(amount, distance, duration)) return null
        return Offer(amountPln = amount, distanceKm = distance, durationMinutes = duration)
    }

    private fun parseWolt(text: String): Offer? {
        val lower = text.lowercase()
        if (
            "spodziewany zarob" !in lower &&
            "szacowan" !in lower &&
            "estimated earnings" !in lower &&
            "expected earnings" !in lower
        ) return null

        val amount = (woltPrefixedAmountBeforeLabelRegex.find(text)?.groupValues?.getOrNull(1)
            ?: woltAmountBeforeLabelRegex.find(text)?.groupValues?.getOrNull(1)
            ?: woltAmountAfterLabelRegex.find(text)?.groupValues?.getOrNull(1))
            ?.toNumber() ?: return null

        // "Calkowita kwota reszty" jest osobna kwota i nie moze wygrac z zarobkiem.
        val distance = distanceRegex.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toNumber() }
            .filter { it in 0.01..500.0 }
            .maxOrNull() ?: return null

        val duration = durationRangeRegex.find(text)?.let { match ->
            val a = match.groupValues[1].toIntOrNull()
            val b = match.groupValues[2].toIntOrNull()
            listOfNotNull(a, b).maxOrNull()?.takeIf { it in 1..360 }
        } ?: findBestNonPickupDuration(text)

        if (amount !in 0.01..1000.0 || distance !in 0.01..500.0) return null
        return Offer(amountPln = amount, distanceKm = distance, durationMinutes = duration)
    }

    private fun parseStuart(text: String): Offer? {
        val lower = text.lowercase()
        if (
            "estimated earnings" !in lower &&
            "expected earnings" !in lower &&
            "szacowan" !in lower &&
            "stuart" !in lower
        ) return null

        val amount = stuartAmountRegex.find(text)?.groupValues?.getOrNull(1)?.toNumber()
            ?: findAmountMatches(text).firstOrNull()?.value?.toNumber()
            ?: return null

        val miles = milesTotalRegex.find(text)?.groupValues?.getOrNull(1)?.toNumber()
        val distanceKm = miles?.times(1.609344)
            ?: distanceRegex.findAll(text)
                .mapNotNull { it.groupValues.getOrNull(1)?.toNumber() }
                .filter { it in 0.01..500.0 }
                .maxOrNull()
            ?: return null

        val duration = durationRangeRegex.find(text)?.let { range ->
            listOfNotNull(
                range.groupValues[1].toIntOrNull(),
                range.groupValues[2].toIntOrNull()
            ).maxOrNull()?.takeIf { it in 1..360 }
        } ?: durationRegex.findAll(text)
            .mapNotNull { it.minutes() }
            .filter { it in 1..360 }
            .maxOrNull()

        if (amount !in 0.01..1000.0 || distanceKm !in 0.01..500.0) return null
        return Offer(amountPln = amount, distanceKm = distanceKm, durationMinutes = duration)
    }

    private fun parsePyszne(text: String): Offer? {
        val lower = text.lowercase()
        val hasOfferMarker =
            "zaakceptuj zlecenie" in lower ||
                "zaakceptuj zlecen" in lower ||
                "accept job" in lower ||
                "accept offer" in lower ||
                "accept order" in lower ||
                ("odbierz na" in lower && "dostarcz na" in lower) ||
                (("pickup" in lower || "pick up" in lower) &&
                    ("delivery" in lower || "deliver" in lower) &&
                    "accept" in lower)

        // Historia zlecen Pyszne zawiera kwoty, kilometry i godziny, ale nie jest oferta.
        if (!hasOfferMarker) return null

        val generic = parseGeneric(text) ?: return null

        /*
         * W Pyszne czas odbioru/dostawy bywa w innym bloku OCR niz kwota i
         * dystans. parseGeneric analizuje lokalny fragment wokol kwoty, wiec na
         * prawdziwym ekranie mogl poprawnie policzyc PLN/km, ale zgubic
         * "Dostarcz na ..." i pokazac NO TIME.
         *
         * Dla Pyszne szukamy harmonogramu jeszcze raz w CALYM tekscie ekranu.
         * Jesli mamy godzine dostawy, nie uzywamy zadnego przypadkowego czasu
         * "min" z mapy - calkowity czas ma byc liczony: teraz -> Dostarcz na.
         */
        val pickupTime = findPickupTime(text) ?: generic.pickupTimeMinutesOfDay
        val deliveryTime = findDeliveryTime(text) ?: generic.deliveryTimeMinutesOfDay

        return generic.copy(
            durationMinutes = if (deliveryTime != null) null else generic.durationMinutes,
            pickupTimeMinutesOfDay = pickupTime,
            deliveryTimeMinutesOfDay = deliveryTime
        )
    }

    private fun parseUberCard(text: String, regex: Regex): Offer? {
        for (match in regex.findAll(text)) {
            val amount = match.groupValues.getOrNull(1)?.toNumber() ?: continue
            val duration = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            val distance = match.groupValues.getOrNull(3)?.toNumber() ?: continue
            if (!valid(amount, distance, duration)) continue

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

    private fun parseGeneric(text: String): Offer? {
        val amountMatches = findAmountMatches(text)
        if (amountMatches.isEmpty()) return null
        val distances = distanceRegex.findAll(text).toList()
        if (distances.isEmpty()) return null

        val candidates = amountMatches.mapNotNull { amountMatch ->
            val amount = amountMatch.value.toNumber() ?: return@mapNotNull null
            if (amount !in 0.01..1000.0) return@mapNotNull null

            val distanceMatch = distances
                .filter { matchDistance(it, amountMatch) <= 900 }
                .minByOrNull { matchDistance(it, amountMatch) }
                ?: return@mapNotNull null
            val distance = distanceMatch.groupValues.getOrNull(1)?.toNumber() ?: return@mapNotNull null
            if (distance !in 0.01..500.0) return@mapNotNull null

            val localStart = if (distanceMatch.range.first < amountMatch.start) {
                (distanceMatch.range.first - 80).coerceAtLeast(0)
            } else amountMatch.start
            val localEnd = maxOf(amountMatch.endExclusive, distanceMatch.range.last + 1)
                .plus(650)
                .coerceAtMost(text.length)
            val block = text.substring(localStart, localEnd)

            val pickupTime = findPickupTime(block)
            val deliveryTime = findDeliveryTime(block)
            val directDuration = if (deliveryTime == null) {
                findTotalDuration(block)
                    ?: findDurationOnDistanceLine(block)
                    ?: findBestNonPickupDuration(block)
            } else null

            Candidate(
                score = candidateScore(text, amountMatch, distanceMatch, directDuration, block),
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

        return candidates.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.startPosition })?.offer
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
        if ("confirm" in lower || "zaakceptuj" in lower || "accept" in lower) score += 8
        if ("delivery" in lower || "dostawa" in lower) score += 6
        if ("tips" in lower || "napiw" in lower || "reszty" in lower) score -= 30
        if ("job history" in lower || "historia" in lower) score -= 45

        val line = lineContaining(text, amountMatch.start).lowercase()
        if ("pln" in line || "zł" in line || "zl" in line) score += 12
        val amount = amountMatch.value.toNumber() ?: 0.0
        if (amount >= 4.0) score += 3
        return score
    }

    private fun findAmountMatches(text: String): List<AmountMatch> {
        val withCurrency = amountRegex.findAll(text).mapNotNull { match ->
            val value = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            AmountMatch(value, match.range.first, match.range.last + 1, true)
        }.toList()

        val bare = bareAmountRegex.findAll(text).mapNotNull { match ->
            val line = lineContaining(text, match.range.first)
            if (
                distanceRegex.containsMatchIn(line) || durationRegex.containsMatchIn(line) ||
                pickupTimeRegex.containsMatchIn(line) || deliveryTimeRegex.containsMatchIn(line)
            ) return@mapNotNull null
            val value = match.groupValues[1]
            val numeric = value.toNumber() ?: return@mapNotNull null
            if (numeric !in 2.0..500.0) return@mapNotNull null
            AmountMatch(value, match.range.first, match.range.last + 1, false)
        }.toList()

        return (withCurrency + bare)
            .distinctBy { "${it.start}:${it.endExclusive}" }
            .sortedBy { it.start }
    }

    private fun findTotalDuration(block: String): Int? {
        val durations = durationRegex.findAll(block).toList()
        if (durations.isEmpty()) return null
        for (totalMatch in totalRegex.findAll(block)) {
            val before = durations.asSequence()
                .filter { it.range.last < totalMatch.range.first }
                .filter { totalMatch.range.first - it.range.last <= 180 }
                .maxByOrNull { it.range.last }
                ?.minutes()
            if (before != null) return before
            val after = durations.asSequence()
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
            durationRangeRegex.find(line)?.let { range ->
                return listOfNotNull(
                    range.groupValues[1].toIntOrNull(),
                    range.groupValues[2].toIntOrNull()
                ).maxOrNull()?.takeIf { it in 1..360 }
            }
            durationRegex.find(line)?.minutes()?.let { return it }
        }
        return null
    }

    private fun findBestNonPickupDuration(block: String): Int? {
        for (line in block.lines()) {
            if (pickupEtaRegex.containsMatchIn(line)) continue
            durationRangeRegex.find(line)?.let { range ->
                return listOfNotNull(
                    range.groupValues[1].toIntOrNull(),
                    range.groupValues[2].toIntOrNull()
                ).maxOrNull()?.takeIf { it in 1..360 }
            }
            durationRegex.find(line)?.minutes()?.let { return it }
        }
        return null
    }

    private fun matchDistance(match: MatchResult, amount: AmountMatch): Int = when {
        match.range.first >= amount.endExclusive -> match.range.first - amount.endExclusive
        amount.start >= match.range.last + 1 -> amount.start - (match.range.last + 1)
        else -> 0
    }

    private fun normalize(text: String): String = text
        .replace('\u00A0', ' ')
        .replace('\u202F', ' ')
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .lines()
        .joinToString("\n") { line -> line.replace(Regex("[\\t ]+"), " ").trim() }

    private fun MatchResult.minutes(): Int? = groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..360 }

    private fun MatchResult.toMinuteOfDay(): Int? {
        val hour = groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun findDeliveryTime(text: String): Int? =
        deliveryTimeRegex.findAll(text)
            .mapNotNull { it.toMinuteOfDay() }
            .lastOrNull()
            ?: deliveryCompactTimeRegex.findAll(text)
                .mapNotNull { it.compactClockToMinuteOfDay() }
                .lastOrNull()

    private fun findPickupTime(text: String): Int? =
        pickupTimeRegex.findAll(text)
            .mapNotNull { it.toMinuteOfDay() }
            .lastOrNull()
            ?: pickupCompactTimeRegex.findAll(text)
                .mapNotNull { it.compactClockToMinuteOfDay() }
                .lastOrNull()

    private fun MatchResult.compactClockToMinuteOfDay(): Int? {
        val digits = groupValues.getOrNull(1)?.filter(Char::isDigit) ?: return null
        if (digits.length !in 3..4) return null

        val hourText = digits.dropLast(2)
        val minuteText = digits.takeLast(2)
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun lineContaining(text: String, position: Int): String {
        val start = text.lastIndexOf('\n', startIndex = (position - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', startIndex = position).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    private fun valid(amount: Double, distance: Double, duration: Int): Boolean =
        amount in 0.01..1000.0 && distance in 0.01..500.0 && duration in 1..360

    private fun String.toNumber(): Double? = replace(',', '.').toDoubleOrNull()

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
