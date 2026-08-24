package pl.fujara.app

import java.text.Normalizer
import java.util.Locale

/** Lokalny, prosty matcher nazw wpisanych przez uzytkownika. */
object BlacklistMatcher {
    fun findHits(
        screenText: String,
        restaurantsRaw: String,
        customersRaw: String
    ): BlacklistHits {
        val haystack = normalize(screenText)
        if (haystack.isBlank()) return BlacklistHits()

        return BlacklistHits(
            restaurant = findFirst(haystack, parse(restaurantsRaw)),
            customer = findFirst(haystack, parse(customersRaw))
        )
    }

    private fun parse(raw: String): List<String> = raw
        .lines()
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinctBy(::normalize)

    private fun findFirst(haystack: String, entries: List<String>): String? =
        entries.firstOrNull { entry ->
            val needle = normalize(entry)
            needle.length >= 2 && haystack.contains(needle)
        }

    internal fun normalize(value: String): String {
        // Unicode NFD usuwa wiekszosc polskich znakow, ale `ł` nie sklada sie
        // z litery + znaku diakrytycznego, dlatego mapujemy je jawnie.
        val lowered = value.lowercase(Locale.ROOT).replace('ł', 'l')
        val withoutDiacritics = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutDiacritics
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
