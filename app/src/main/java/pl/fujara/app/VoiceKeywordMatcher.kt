package pl.fujara.app

import java.text.Normalizer
import java.util.Locale

/** Dopasowanie slow-kluczy niezaleznie od polskich znakow i koncowek. */
object VoiceKeywordMatcher {
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace('ł', 'l')
            .replace(Regex("""[^\p{L}0-9 ]"""), " ")
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

/** 1.7: komendy glosowe sterujace panelem i mapa aplikacji kurierskiej. */
enum class VoiceCommand { COLLAPSE, EXPAND, ZOOM_IN, ZOOM_OUT }

object VoiceCommandMatcher {
    private val stems: List<Pair<VoiceCommand, List<String>>> = listOf(
        // kolejnosc ma znaczenie: "rozwin" przed "zwin"
        VoiceCommand.EXPAND to listOf("rozwin", "pokaz", "wiecej", "szczegol", "expand", "show", "розгорн", "разверн", "покажи"),
        VoiceCommand.COLLAPSE to listOf("zwin", "schowaj", "ukryj", "minimaliz", "collapse", "hide", "згорн", "сверн", "сховай", "скрой"),
        VoiceCommand.ZOOM_IN to listOf("przybliz", "powieksz", "blizej", "наблиз", "приблиз", "збільш", "увелич"),
        VoiceCommand.ZOOM_OUT to listOf("oddal", "pomniejsz", "oddalic", "віддал", "отдал", "зменш", "уменьш")
    )

    private val normalized by lazy { stems.map { (c, l) -> c to l.map { VoiceKeywordMatcher.normalize(it) } } }

    fun parse(hypotheses: List<String>): VoiceCommand? {
        for (h in hypotheses) {
            val n = VoiceKeywordMatcher.normalize(h)
            if (n.isBlank()) continue
            if (Regex("""\bzoom (in|w gore)\b""").containsMatchIn(n)) return VoiceCommand.ZOOM_IN
            if (Regex("""\bzoom (out|w dol)\b""").containsMatchIn(n)) return VoiceCommand.ZOOM_OUT
            val words = n.split(' ')
            for ((cmd, list) in normalized) {
                if (words.any { w -> list.any { st -> w.startsWith(st) } }) return cmd
            }
        }
        return null
    }
}
