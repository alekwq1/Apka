package pl.fujara.app

import java.text.Normalizer
import java.util.Locale

data class BlacklistEntry(
    val name: String,
    val address: String = ""
) {
    val displayLabel: String
        get() = when {
            name.isNotBlank() && address.isNotBlank() -> "$name · $address"
            name.isNotBlank() -> name
            else -> address
        }
}

/**
 * Prosty format lokalny: jedna pozycja na linie, nazwa i opcjonalny adres
 * rozdzielone tabulatorem. Stare wpisy 0.8.0 (sama nazwa na linii) pozostaja
 * kompatybilne.
 */
object BlacklistEntryCodec {
    fun parse(raw: String): List<BlacklistEntry> = raw
        .lines()
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            val name = parts.getOrNull(0).orEmpty().trim()
            val address = parts.getOrNull(1).orEmpty().trim()
            if (name.isBlank() && address.isBlank()) null else BlacklistEntry(name, address)
        }
        .distinctBy { "${BlacklistMatcher.normalize(it.name)}|${BlacklistMatcher.normalize(it.address)}" }

    fun serialize(entries: List<BlacklistEntry>): String = entries
        .filter { it.name.isNotBlank() || it.address.isNotBlank() }
        .joinToString("\n") { entry ->
            val name = entry.name.replace('\t', ' ').replace('\n', ' ').trim()
            val address = entry.address.replace('\t', ' ').replace('\n', ' ').trim()
            if (address.isBlank()) name else "$name\t$address"
        }
}

/** Lokalny matcher list blokowanych miejsc i odbiorcow. */
object BlacklistMatcher {
    fun findHits(
        screenText: String,
        restaurantsRaw: String,
        customersRaw: String
    ): BlacklistHits {
        val lines = screenText
            .lines()
            .map(::normalize)
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return BlacklistHits()

        return BlacklistHits(
            restaurant = findFirst(lines, BlacklistEntryCodec.parse(restaurantsRaw)),
            customer = findFirst(lines, BlacklistEntryCodec.parse(customersRaw))
        )
    }

    private fun findFirst(lines: List<String>, entries: List<BlacklistEntry>): String? =
        entries.firstOrNull { entry -> matches(lines, entry) }?.displayLabel

    private fun matches(lines: List<String>, entry: BlacklistEntry): Boolean {
        val name = normalize(entry.name)
        val address = normalize(entry.address)
        if (name.isBlank() && address.isBlank()) return false

        val exactName = name.isNotBlank() && lines.any { line ->
            // Pelna nazwa albo nazwa po prefiksie typu "Odbior:" / "Dostawa od".
            // Nie stosujemy contains() dla wpisu bez adresu, wiec "McDonald's"
            // nie trafi w "McDonald's Morena".
            line == name || line.endsWith(" $name")
        }

        val nameInContext = name.isBlank() || lines.any { line ->
            line == name ||
                line.endsWith(" $name") ||
                line.startsWith("$name ") ||
                line.contains(" $name ")
        }

        val addressInContext = address.isBlank() || lines.any { line ->
            line == address ||
                line.endsWith(" $address") ||
                line.startsWith("$address ") ||
                line.contains(" $address ")
        }

        return when {
            // Sama nazwa = scisle dopasowanie nazwy lokalu/odbiorcy.
            address.isBlank() -> exactName
            // Sam adres jest dozwolony, jesli uzytkownik chce blokowac konkretny punkt.
            name.isBlank() -> addressInContext
            // Nazwa + adres rozroznia filie o tej samej nazwie.
            else -> nameInContext && addressInContext
        }
    }

    internal fun normalize(value: String): String {
        val lowered = value.lowercase(Locale.ROOT).replace('ł', 'l')
        val withoutDiacritics = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutDiacritics
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
