package pl.fujara.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Minimalny zapis historii Pyszne potrzebny do podsumowan.
 * Nie zapisujemy pelnego OCR, adresu klienta ani surowego numeru zlecenia.
 * [key] jest lokalnym hashem uzywanym tylko do blokowania duplikatow.
 */
data class PyszneDeliveryLog(
    val key: String,
    val fingerprint: String,
    val date: LocalDate,
    val acceptedMinuteOfDay: Int?,
    val restaurant: String,
    val amountPln: Double,
    val distanceKm: Double,
    val durationSeconds: Int,
    val savedAtMillis: Long = System.currentTimeMillis()
)

enum class PyszneSaveResult {
    SAVED,
    DUPLICATE
}

object PyszneHistoryParser {
    private val polishMonths = mapOf(
        "stycznia" to 1,
        "lutego" to 2,
        "marca" to 3,
        "kwietnia" to 4,
        "maja" to 5,
        "czerwca" to 6,
        "lipca" to 7,
        "sierpnia" to 8,
        "wrzesnia" to 9,
        "września" to 9,
        "pazdziernika" to 10,
        "października" to 10,
        "listopada" to 11,
        "grudnia" to 12
    )

    private val englishMonths = mapOf(
        "january" to 1,
        "february" to 2,
        "march" to 3,
        "april" to 4,
        "may" to 5,
        "june" to 6,
        "july" to 7,
        "august" to 8,
        "september" to 9,
        "october" to 10,
        "november" to 11,
        "december" to 12
    )

    private val plDateRegex = Regex(
        """(?i)\b(\d{1,2})\s+(stycznia|lutego|marca|kwietnia|maja|czerwca|lipca|sierpnia|wrze[sś]nia|pa[zź]dziernika|listopada|grudnia)\s+(\d{4})\b"""
    )
    private val enDateRegex = Regex(
        """(?i)\b(\d{1,2})\s+(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{4})\b|\b(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2}),?\s+(\d{4})\b"""
    )
    private val timeRegex = Regex("""(?i)\b(\d{1,2}):(\d{2})\s*(AM|PM)?\b""")
    private val orderIdRegex = Regex("""^#?([A-Z0-9]{6})$""")
    private val pickupLabelRegex = Regex("""(?i)^(?:odbi[oó]r|pickup)\b\s*[:\-]?\s*(.*)$""")
    private val historyDetailsRegex = Regex(
        """(?i)(?:szczeg[oó][lł]y\s+(?:zlecenia|przychod[oó]w)|order\s+details|earnings\s+details)"""
    )
    private val deliveredMarkerRegex = Regex(
        """(?i)(?:zlecenie\s+dostarczone|job\s+delivered|delivered)"""
    )
    private val stopLabelRegex = Regex(
        """(?i)^(?:zlecenie\s+przyj[eę]te|zlecenie\s+dostarczone|job\s+accepted|job\s+delivered|czas\s+aktywno[sś]ci|active\s+time|szacowana\s+odleglo[sś][cć]|szacowana\s+odległość|estimated\s+distance|szczeg[oó][lł]y\s+przychod[oó]w|earnings\s+details|stawka\s+bazowa|base\s+(?:pay|rate)|dodatkowe\s+korzy[sś]ci|przyznany\s+napiwek|tip|inne|suma\s+przychod[oó]w|total\s+(?:earnings|income|revenue))\b"""
    )

    fun parse(
        sourceText: String,
        offer: Offer,
        fallbackDate: LocalDate = LocalDate.now()
    ): PyszneDeliveryLog? {
        // Przycisk ZAPISZ DANE ma pojawiac sie tylko w historii zakonczonego
        // zlecenia, nigdy na karcie nowej oferty.
        if (!historyDetailsRegex.containsMatchIn(sourceText) || !deliveredMarkerRegex.containsMatchIn(sourceText)) return null

        val durationSeconds = offer.durationSeconds ?: return null
        if (durationSeconds <= 0 || offer.distanceKm <= 0.0 || offer.amountPln < 0.0) return null

        val lines = sourceText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val date = parseDateFromText(sourceText) ?: fallbackDate
        val acceptedMinute = parseAcceptedMinute(lines)
        val deliveredMinute = parseDeliveredMinute(lines)
        val restaurant = parseRestaurant(lines).ifBlank { "Nieznana restauracja" }
        val rawOrderId = parseOrderId(lines)

        val fallbackIdentity = listOf(
            "fallback",
            date.toString(),
            acceptedMinute?.toString().orEmpty(),
            deliveredMinute?.toString().orEmpty(),
            normalizeRestaurant(restaurant),
            (offer.amountPln * 100.0).toInt().toString(),
            (offer.distanceKm * 1000.0).toInt().toString(),
            durationSeconds.toString()
        ).joinToString("|")
        val identity = rawOrderId?.let { "id|${it.uppercase(Locale.ROOT)}" } ?: fallbackIdentity

        return PyszneDeliveryLog(
            key = sha256(identity).take(24),
            fingerprint = sha256(fallbackIdentity).take(24),
            date = date,
            acceptedMinuteOfDay = acceptedMinute,
            restaurant = restaurant,
            amountPln = offer.amountPln,
            distanceKm = offer.distanceKm,
            durationSeconds = durationSeconds
        )
    }

    fun parseDateFromText(text: String): LocalDate? {
        plDateRegex.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = polishMonths[match.groupValues[2].lowercase(Locale.ROOT)] ?: return@let
            val year = match.groupValues[3].toIntOrNull() ?: return@let
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }

        enDateRegex.find(text)?.let { match ->
            val day: Int
            val monthName: String
            val year: Int
            if (match.groupValues[1].isNotBlank()) {
                day = match.groupValues[1].toIntOrNull() ?: return@let
                monthName = match.groupValues[2]
                year = match.groupValues[3].toIntOrNull() ?: return@let
            } else {
                monthName = match.groupValues[4]
                day = match.groupValues[5].toIntOrNull() ?: return@let
                year = match.groupValues[6].toIntOrNull() ?: return@let
            }
            val month = englishMonths[monthName.lowercase(Locale.ROOT)] ?: return@let
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        return null
    }

    private fun parseAcceptedMinute(lines: List<String>): Int? =
        parseLabeledMinute(lines, Regex("""(?i)^(?:zlecenie\s+przyj[eę]te|job\s+accepted)\b"""))

    private fun parseDeliveredMinute(lines: List<String>): Int? =
        parseLabeledMinute(lines, Regex("""(?i)^(?:zlecenie\s+dostarczone|job\s+delivered)\b"""))

    private fun parseLabeledMinute(lines: List<String>, label: Regex): Int? {
        lines.forEachIndexed { index, line ->
            if (!label.containsMatchIn(line)) return@forEachIndexed
            val context = buildString {
                append(line)
                lines.getOrNull(index + 1)?.let { append(' ').append(it) }
            }
            timeRegex.find(context)?.let { return it.toMinuteOfDay() }
        }
        return null
    }

    private fun MatchResult.toMinuteOfDay(): Int? {
        var hour = groupValues[1].toIntOrNull() ?: return null
        val minute = groupValues[2].toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        val suffix = groupValues[3].uppercase(Locale.ROOT)
        if (suffix.isNotBlank()) {
            if (hour !in 1..12) return null
            hour = when {
                suffix == "AM" && hour == 12 -> 0
                suffix == "PM" && hour != 12 -> hour + 12
                else -> hour
            }
        }
        if (hour !in 0..23) return null
        return hour * 60 + minute
    }

    private fun parseOrderId(lines: List<String>): String? {
        val banned = setOf("FUJARA", "PYSZNE", "STUART", "GLOVOO")
        return lines
            .asSequence()
            .map { it.replace(" ", "").uppercase(Locale.ROOT) }
            .mapNotNull { line -> orderIdRegex.matchEntire(line)?.groupValues?.getOrNull(1) }
            .firstOrNull { value -> value !in banned }
    }

    private fun parseRestaurant(lines: List<String>): String {
        lines.forEachIndexed { index, line ->
            val match = pickupLabelRegex.find(line) ?: return@forEachIndexed
            val inline = match.groupValues.getOrNull(1).orEmpty().trim()
            if (inline.isNotBlank() && !looksLikeFieldLabel(inline)) return cleanRestaurant(inline)

            val pieces = mutableListOf<String>()
            for (offset in 1..3) {
                val next = lines.getOrNull(index + offset)?.trim().orEmpty()
                if (next.isBlank()) continue
                if (looksLikeFieldLabel(next)) break
                if (timeRegex.containsMatchIn(next) && pieces.isEmpty()) break
                pieces += next
                if (pieces.joinToString(" ").length >= 80) break
            }
            if (pieces.isNotEmpty()) return cleanRestaurant(pieces.joinToString(" "))
        }
        return ""
    }

    private fun looksLikeFieldLabel(value: String): Boolean =
        stopLabelRegex.containsMatchIn(value)

    private fun cleanRestaurant(value: String): String = value
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '|')
        .take(140)

    fun normalizeRestaurant(value: String): String = value
        .substringBefore(" (")
        .substringBefore(" – ")
        .substringBefore(" - ")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}


data class PyszneDayReference(
    val date: LocalDate,
    val orderCount: Int,
    val amountPln: Double,
    val capturedAtMillis: Long = System.currentTimeMillis()
)

/** Odczyt kontrolny z ekranu Pyszne "Podsumowanie dnia". */
object PyszneDayReferenceParser {
    private val daySummaryMarker = Regex(
        """(?i)(?:podsumowanie\s+dnia|daily\s+summary|day\s+summary)"""
    )
    private val orderCountRegex = Regex(
        """(?i)\b(\d{1,3})\s*(?:zlece[nń]|zleceń|zlecenia|offers?|orders?)\s*(?:accepted|zaakceptowan\p{L}*)?\b"""
    )
    private val moneyRegex = Regex(
        """(?i)(\d{1,5}(?:[ .]\d{3})*[,.]\d{2})\s*(?:zł|zl|pln)(?![\p{L}\p{N}])"""
    )

    fun parse(text: String, fallbackDate: LocalDate = LocalDate.now()): PyszneDayReference? {
        if (!daySummaryMarker.containsMatchIn(text)) return null
        val count = orderCountRegex.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 1..300 }
            .maxOrNull() ?: return null

        // Na ekranie dnia wystepuja kwoty pojedynczych dostaw. Laczny przychod
        // jest od nich wiekszy i bywa wyswietlany kilka razy, wiec wybieramy
        // najwieksza jawna kwote walutowa z tego ekranu.
        val amount = moneyRegex.findAll(text)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.replace(" ", "")
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
            }
            .filter { it in 0.01..100_000.0 }
            .maxOrNull() ?: return null

        return PyszneDayReference(
            date = PyszneHistoryParser.parseDateFromText(text) ?: fallbackDate,
            orderCount = count,
            amountPln = amount
        )
    }
}

class PyszneLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("pyszne_delivery_history", Context.MODE_PRIVATE)

    @Synchronized
    fun save(entry: PyszneDeliveryLog): PyszneSaveResult {
        val current = all().toMutableList()
        if (current.any { it.key == entry.key || it.fingerprint == entry.fingerprint }) {
            return PyszneSaveResult.DUPLICATE
        }
        current += entry
        persist(current.sortedWith(compareBy<PyszneDeliveryLog> { it.date }.thenBy { it.acceptedMinuteOfDay ?: Int.MAX_VALUE }))
        return PyszneSaveResult.SAVED
    }

    fun contains(entry: PyszneDeliveryLog): Boolean = all().any { saved ->
        saved.key == entry.key || saved.fingerprint == entry.fingerprint
    }

    fun all(): List<PyszneDeliveryLog> {
        val raw = prefs.getString(KEY_ENTRIES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    decode(obj)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun forDate(date: LocalDate): List<PyszneDeliveryLog> = all().filter { it.date == date }

    @Synchronized
    fun saveDayReference(reference: PyszneDayReference) {
        val refs = allDayReferences().toMutableMap()
        refs[reference.date] = reference
        val obj = JSONObject()
        refs.toSortedMap().forEach { (date, item) ->
            obj.put(
                date.toString(),
                JSONObject()
                    .put("count", item.orderCount)
                    .put("amount", item.amountPln)
                    .put("capturedAt", item.capturedAtMillis)
            )
        }
        prefs.edit().putString(KEY_DAY_REFERENCES, obj.toString()).apply()
    }

    fun dayReference(date: LocalDate): PyszneDayReference? = allDayReferences()[date]

    private fun allDayReferences(): Map<LocalDate, PyszneDayReference> {
        val raw = prefs.getString(KEY_DAY_REFERENCES, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                    val item = obj.optJSONObject(key) ?: continue
                    val count = item.optInt("count", -1)
                    val amount = item.optDouble("amount", Double.NaN)
                    if (count < 0 || !amount.isFinite()) continue
                    put(
                        date,
                        PyszneDayReference(
                            date = date,
                            orderCount = count,
                            amountPln = amount,
                            capturedAtMillis = item.optLong("capturedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun deleteDate(date: LocalDate) {
        persist(all().filterNot { it.date == date })
        val refs = allDayReferences().toMutableMap().apply { remove(date) }
        val obj = JSONObject()
        refs.toSortedMap().forEach { (refDate, item) ->
            obj.put(refDate.toString(), JSONObject().put("count", item.orderCount).put("amount", item.amountPln).put("capturedAt", item.capturedAtMillis))
        }
        prefs.edit().putString(KEY_DAY_REFERENCES, obj.toString()).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).remove(KEY_DAY_REFERENCES).apply()
    }

    private fun persist(entries: List<PyszneDeliveryLog>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("key", entry.key)
                    .put("fingerprint", entry.fingerprint)
                    .put("date", entry.date.toString())
                    .put("acceptedMinute", entry.acceptedMinuteOfDay ?: JSONObject.NULL)
                    .put("restaurant", entry.restaurant)
                    .put("amount", entry.amountPln)
                    .put("distance", entry.distanceKm)
                    .put("durationSeconds", entry.durationSeconds)
                    .put("savedAt", entry.savedAtMillis)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun decode(obj: JSONObject): PyszneDeliveryLog? = runCatching {
        PyszneDeliveryLog(
            key = obj.getString("key"),
            fingerprint = obj.optString("fingerprint", obj.getString("key")),
            date = LocalDate.parse(obj.getString("date")),
            acceptedMinuteOfDay = if (obj.isNull("acceptedMinute")) null else obj.getInt("acceptedMinute"),
            restaurant = obj.optString("restaurant", "Nieznana restauracja"),
            amountPln = obj.getDouble("amount"),
            distanceKm = obj.getDouble("distance"),
            durationSeconds = obj.getInt("durationSeconds"),
            savedAtMillis = obj.optLong("savedAt", 0L)
        )
    }.getOrNull()

    companion object {
        private const val KEY_ENTRIES = "entries_v1"
        private const val KEY_DAY_REFERENCES = "day_references_v1"
    }
}

data class PyszneRestaurantSummary(
    val name: String,
    val orderCount: Int,
    val grossPln: Double,
    val distanceKm: Double,
    val durationSeconds: Int,
    val netPln: Double,
    val netPerHour: Double?,
    val netPerKm: Double?,
    val status: ProfitabilityStatus,
    val goodOrders: Int,
    val borderlineOrders: Int,
    val poorOrders: Int
)

data class PyszneDaySummary(
    val date: LocalDate,
    val orderCount: Int,
    val grossPln: Double,
    val distanceKm: Double,
    val durationSeconds: Int,
    val netPln: Double,
    val netPerHour: Double?,
    val netPerKm: Double?,
    val status: ProfitabilityStatus,
    val goodOrders: Int,
    val borderlineOrders: Int,
    val poorOrders: Int,
    val restaurants: List<PyszneRestaurantSummary>
)

object PyszneDaySummaryCalculator {
    fun calculate(
        date: LocalDate,
        entries: List<PyszneDeliveryLog>,
        rules: ProfitabilityCalculator.Rules,
        decisionBasis: DecisionBasis,
        zusPercent: Double
    ): PyszneDaySummary {
        val daily = entries.filter { it.date == date }
        val totalResult = profitabilityFor(daily, rules, decisionBasis, zusPercent)
        val orderStatuses = daily.map { entry ->
            profitabilityFor(listOf(entry), rules, decisionBasis, zusPercent).status
        }

        val restaurants = daily
            .groupBy { PyszneHistoryParser.normalizeRestaurant(it.restaurant).ifBlank { "nieznana restauracja" } }
            .map { (_, group) ->
                val result = profitabilityFor(group, rules, decisionBasis, zusPercent)
                val statuses = group.map { entry ->
                    profitabilityFor(listOf(entry), rules, decisionBasis, zusPercent).status
                }
                PyszneRestaurantSummary(
                    name = group.first().restaurant.substringBefore(" (").trim().ifBlank { "Nieznana restauracja" },
                    orderCount = group.size,
                    grossPln = group.sumOf { it.amountPln },
                    distanceKm = group.sumOf { it.distanceKm },
                    durationSeconds = group.sumOf { it.durationSeconds },
                    netPln = result.netPln,
                    netPerHour = result.netPerHour,
                    netPerKm = result.netPerKm,
                    status = result.status,
                    goodOrders = statuses.count { it == ProfitabilityStatus.PROFITABLE },
                    borderlineOrders = statuses.count { it == ProfitabilityStatus.ALMOST_PROFITABLE },
                    poorOrders = statuses.count { it == ProfitabilityStatus.UNPROFITABLE }
                )
            }
            .sortedWith(
                compareByDescending<PyszneRestaurantSummary> { it.netPerHour ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.orderCount }
            )

        return PyszneDaySummary(
            date = date,
            orderCount = daily.size,
            grossPln = daily.sumOf { it.amountPln },
            distanceKm = daily.sumOf { it.distanceKm },
            durationSeconds = daily.sumOf { it.durationSeconds },
            netPln = totalResult.netPln,
            netPerHour = totalResult.netPerHour,
            netPerKm = totalResult.netPerKm,
            status = totalResult.status,
            goodOrders = orderStatuses.count { it == ProfitabilityStatus.PROFITABLE },
            borderlineOrders = orderStatuses.count { it == ProfitabilityStatus.ALMOST_PROFITABLE },
            poorOrders = orderStatuses.count { it == ProfitabilityStatus.UNPROFITABLE },
            restaurants = restaurants
        )
    }

    private fun profitabilityFor(
        entries: List<PyszneDeliveryLog>,
        rules: ProfitabilityCalculator.Rules,
        decisionBasis: DecisionBasis,
        zusPercent: Double
    ): Profitability {
        val offer = Offer(
            amountPln = entries.sumOf { it.amountPln },
            distanceKm = entries.sumOf { it.distanceKm },
            durationSeconds = entries.sumOf { it.durationSeconds }.takeIf { it > 0 },
            applyExtraTimeBuffer = false
        )
        return ProfitabilityCalculator.calculate(
            offer = offer,
            rules = rules,
            currentMinuteOfDay = 0,
            decisionBasis = decisionBasis,
            zusPercent = zusPercent
        )
    }
}

fun PyszneDaySummary.shareText(nickname: String = ""): String {
    val who = nickname.trim().takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
    val locale = Locale.forLanguageTag("pl-PL")
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    return buildString {
        appendLine("FUJARA · Pyszne · ${date.format(DateTimeFormatter.ISO_DATE)}")
        appendLine("$who$orderCount zleceń · ${String.format(locale, "%.2f", grossPln)} zł")
        appendLine("${String.format(locale, "%.1f", distanceKm)} km · ${hours}h ${minutes}min")
        appendLine("${String.format(locale, "%.0f", netPerHour ?: 0.0)} zł/h · ${String.format(locale, "%.2f", netPerKm ?: 0.0)} zł/km po kosztach")
        append("SUPER $goodOrders · NA STYK $borderlineOrders · FUJARA $poorOrders")
    }
}
