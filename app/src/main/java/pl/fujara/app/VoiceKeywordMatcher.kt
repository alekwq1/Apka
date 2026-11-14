package pl.fujara.app

import java.text.Normalizer
import java.util.Locale

/** Dopasowanie slow-kluczy niezaleznie od polskich znakow i koncowek. */
object VoiceKeywordMatcher {
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace('ł', 'l')
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Czy ktorakolwiek hipoteza rozpoznania zawiera haslo (lub jego rdzen). */
    fun matches(hypotheses: List<String>, keyword: String): Boolean {
        val key = normalize(keyword)
        if (key.isBlank()) return false
        val stem = key.take(maxOf(4, key.length - 2))
        return hypotheses.any { h ->
            val n = normalize(h)
            n.contains(key) || n.split(' ').any { word -> word.startsWith(stem) }
        }
    }

    fun isConfirmation(hypotheses: List<String>, confirmWord: String): Boolean =
        matches(hypotheses, confirmWord) ||
            hypotheses.any { h ->
                val n = normalize(h)
                n.split(' ').any { it in setOf("tak", "potwierdzam", "potwierdz", "jasne", "yes", "confirm", "так", "да") }
            }

    fun isNegation(hypotheses: List<String>): Boolean = hypotheses.any { h ->
        val n = normalize(h)
        n.split(' ').any { it in setOf("nie", "anuluj", "stop", "no", "cancel", "ні", "нет") }
    }
}
