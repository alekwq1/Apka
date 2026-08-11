package pl.deliveryassistant.mvp

object OfferParser {

    private val amountRegex = Regex(
        """(?<!\d)(\d{1,3}(?:[.,]\d{2}))\s*(?:zł|zl|PLN)""",
        RegexOption.IGNORE_CASE
    )

    private val distanceRegex = Regex(
        """(?<!\d)(\d{1,3}(?:[.,]\d{1,2})?)\s*km\b""",
        RegexOption.IGNORE_CASE
    )

    private val durationRegex = Regex(
        """(?<!\d)(\d{1,3})\s*min\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Offer? {
        val normalized = text.replace('\u00A0', ' ')

        val amount = amountRegex
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.toNumber()
            ?: return null

        val distance = distanceRegex
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.toNumber()
            ?: return null

        val duration = durationRegex
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        if (amount <= 0.0 || distance <= 0.0) {
            return null
        }

        return Offer(
            amountPln = amount,
            distanceKm = distance,
            durationMinutes = duration
        )
    }

    private fun String.toNumber(): Double? {
        return replace(',', '.').toDoubleOrNull()
    }
}