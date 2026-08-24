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
 * Nie zapisujemy pelnego OCR ani adresu klienta. Numer zlecenia zapisujemy lokalnie
 * tylko po to, aby user widzial co juz ma zebrane; [key] nadal blokuje duplikaty.
 */
data class PyszneDeliveryLog(
    val key: String,
    val fingerprint: String,
    /** Jawny numer zlecenia jest przechowywany tylko lokalnie, aby pokazac userowi co juz zapisano. */
    val orderId: String? = null,
    val date: LocalDate,
    val acceptedMinuteOfDay: Int?,
    val restaurant: String,
    val amountPln: Double,
    val distanceKm: Double,
    val durationSeconds: Int,
    val cancelled: Boolean = false,
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
        """(?i)(?:zlecenie\s+dostarczone|\bdostarczone\b|job\s+delivered|\bdelivered\b)"""
    )
    private val cancelledMarkerRegex = Regex(
        """(?i)(?:zlecenie\s+anulowane|\banulowane\b|job\s+cancelled|order\s+cancelled|\bcancelled\b|\bcanceled\b)"""
    )
    private val stopLabelRegex = Regex(
        """(?i)^(?:zlecenie\s+przyj[eę]te|zlecenie\s+dostarczone|zlecenie\s+anulowane|job\s+accepted|job\s+delivered|job\s+cancelled|order\s+cancelled|czas\s+aktywno[sś]ci|active\s+time|szacowana\s+odleglo[sś][cć]|szacowana\s+odległość|estimated\s+distance|szczeg[oó][lł]y\s+przychod[oó]w|earnings\s+details|stawka\s+bazowa|base\s+(?:pay|rate)|dodatkowe\s+korzy[sś]ci|przyznany\s+napiwek|tip|inne|suma\s+przychod[oó]w|total\s+(?:earnings|income|revenue))\b"""
    )
    private val moneyOnlyRegex = Regex("""(?i)^\s*[-+]?\d{1,5}(?:[ .]\d{3})*[,.]\d{2}\s*(?:zł|zl|pln)?\s*$""")
    private val distanceOnlyRegex = Regex("""(?i)^\s*\d{1,4}(?:[,.]\d{1,2})?\s*km\s*$""")
    private val durationOnlyRegex = Regex("""(?i)^\s*\d{1,3}\s*(?:min|m)(?:\s*\d{1,2}\s*(?:sec|sek|s))?\s*$""")

    fun parse(
        sourceText: String,
        offer: Offer,
        fallbackDate: LocalDate = LocalDate.now()
    ): PyszneDeliveryLog? {
        // Przycisk ZAPISZ DANE ma pojawiac sie tylko w historii zakonczonego
        // zlecenia, nigdy na karcie nowej oferty. Zakonczone obejmuje tez
        // zlecenia anulowane - Pyszne wlicza je do liczby zaakceptowanych ofert.
        val cancelled = cancelledMarkerRegex.containsMatchIn(sourceText)
        val completed = deliveredMarkerRegex.containsMatchIn(sourceText) || cancelled
        if (!historyDetailsRegex.containsMatchIn(sourceText) || !completed) return null

        val durationSeconds = offer.durationSeconds ?: return null
        if (durationSeconds <= 0 || offer.distanceKm <= 0.0 || offer.amountPln < 0.0) return null

        val lines = sourceText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val date = parseDateFromText(sourceText) ?: fallbackDate
        val acceptedMinute = parseAcceptedMinute(lines)
        val finishedMinute = if (cancelled) parseCancelledMinute(lines) else parseDeliveredMinute(lines)
        val restaurant = parseRestaurant(lines).ifBlank { "Nieznana restauracja" }
        val rawOrderId = parseOrderId(lines)

        val fallbackIdentity = listOf(
            "fallback",
            date.toString(),
            acceptedMinute?.toString().orEmpty(),
            finishedMinute?.toString().orEmpty(),
            if (cancelled) "cancelled" else "delivered",
            normalizeRestaurant(restaurant),
            (offer.amountPln * 100.0).toInt().toString(),
            (offer.distanceKm * 1000.0).toInt().toString(),
            durationSeconds.toString()
        ).joinToString("|")
        val identity = rawOrderId?.let { "id|${it.uppercase(Locale.ROOT)}" } ?: fallbackIdentity

        return PyszneDeliveryLog(
            key = sha256(identity).take(24),
            fingerprint = sha256(fallbackIdentity).take(24),
            orderId = rawOrderId,
            date = date,
            acceptedMinuteOfDay = acceptedMinute,
            restaurant = restaurant,
            amountPln = offer.amountPln,
            distanceKm = offer.distanceKm,
            durationSeconds = durationSeconds,
            cancelled = cancelled
        )
    }

    fun parseDateFromText(text: String): LocalDate? = parseDatesFromText(text).firstOrNull()

    /**
     * Zwraca wszystkie jawnie widoczne daty. Podsumowanie dnia nie korzysta juz
     * z daty telefonu jako fallbacku - to blokuje zapis starego ekranu pod
     * dzisiejsza data podczas przejsc/animacji Pyszne.
     */
    fun parseDatesFromText(text: String): List<LocalDate> {
        val found = mutableListOf<Pair<Int, LocalDate>>()

        plDateRegex.findAll(text).forEach { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            val month = polishMonths[match.groupValues[2].lowercase(Locale.ROOT)] ?: return@forEach
            val year = match.groupValues[3].toIntOrNull() ?: return@forEach
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { found += match.range.first to it }
        }

        enDateRegex.findAll(text).forEach { match ->
            val day: Int
            val monthName: String
            val year: Int
            if (match.groupValues[1].isNotBlank()) {
                day = match.groupValues[1].toIntOrNull() ?: return@forEach
                monthName = match.groupValues[2]
                year = match.groupValues[3].toIntOrNull() ?: return@forEach
            } else {
                monthName = match.groupValues[4]
                day = match.groupValues[5].toIntOrNull() ?: return@forEach
                year = match.groupValues[6].toIntOrNull() ?: return@forEach
            }
            val month = englishMonths[monthName.lowercase(Locale.ROOT)] ?: return@forEach
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { found += match.range.first to it }
        }

        return found.sortedBy { it.first }.map { it.second }
    }

    private fun parseAcceptedMinute(lines: List<String>): Int? =
        parseLabeledMinute(lines, Regex("""(?i)^(?:zlecenie\s+przyj[eę]te|job\s+accepted)\b"""))

    private fun parseDeliveredMinute(lines: List<String>): Int? =
        parseLabeledMinute(lines, Regex("""(?i)^(?:zlecenie\s+dostarczone|job\s+delivered)\b"""))

    private fun parseCancelledMinute(lines: List<String>): Int? =
        parseLabeledMinute(lines, Regex("""(?i)^(?:zlecenie\s+anulowane|job\s+cancelled|order\s+cancelled)\b"""))

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
            if (isRestaurantCandidate(inline)) return cleanRestaurant(inline)

            // OCR Pyszne czasem porzadkuje dwie kolumny inaczej niz wizualnie.
            // Nie bierzemy wiec pierwszej linii po "Odbior" w ciemno (np. 24,51 zl),
            // tylko szukamy pierwszego sensownego tekstu restauracji do kolejnego pola.
            val pieces = mutableListOf<String>()
            for (offset in 1..8) {
                val next = lines.getOrNull(index + offset)?.trim().orEmpty()
                if (next.isBlank()) continue
                if (looksLikeFieldLabel(next)) {
                    if (pieces.isNotEmpty()) break
                    continue
                }
                if (!isRestaurantCandidate(next)) continue

                pieces += next
                // Druga linia bywa tylko kontynuacja dlugiej nazwy/adresu. Trzeciej
                // nie doklejamy, aby nie polaczyc nazwy z kolejnym elementem OCR.
                if (pieces.size >= 2 || pieces.joinToString(" ").length >= 105) break
            }
            if (pieces.isNotEmpty()) return cleanRestaurant(pieces.joinToString(" "))
        }
        return ""
    }

    private fun isRestaurantCandidate(value: String): Boolean {
        val v = value.trim()
        if (v.length !in 2..140) return false
        if (looksLikeFieldLabel(v)) return false
        if (moneyOnlyRegex.matches(v) || distanceOnlyRegex.matches(v) || durationOnlyRegex.matches(v)) return false
        if (timeRegex.matches(v)) return false
        if (orderIdRegex.matches(v.replace(" ", "").uppercase(Locale.ROOT))) return false
        if (parseDateFromText(v) != null) return false
        if (Regex("""(?i)^(?:pyszne(?:\.pl)?|fujara|dostarczone|anulowane|delivered|cancelled|suma\s+przychod[oó]w|szczeg[oó][lł]y.*)$""").matches(v)) return false
        return v.any { it.isLetter() }
    }

    private fun looksLikeFieldLabel(value: String): Boolean =
        stopLabelRegex.containsMatchIn(value)

    private fun cleanRestaurant(value: String): String = value
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '|')
        .take(140)

    fun orderKeyForId(rawOrderId: String): String {
        val normalized = rawOrderId.trim().removePrefix("#").uppercase(Locale.ROOT)
        return sha256("id|$normalized").take(24)
    }

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
    /** Numery zlecen odczytane podczas przewijania listy dnia w Pyszne. */
    val orderIds: List<String> = emptyList(),
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
    private val listOrderIdRegex = Regex("""(?i)#\s*([A-Z0-9]{6})\b""")

    fun parseOrderIds(text: String): List<String> = listOrderIdRegex.findAll(text)
        .map { it.groupValues[1].uppercase(Locale.ROOT) }
        .filter { it !in setOf("FUJARA", "PYSZNE") }
        .distinct()
        .toList()

    fun parse(text: String): PyszneDayReference? {
        if (!daySummaryMarker.containsMatchIn(text)) return null

        // Bez jawnej daty NIC nie zapisujemy. W 0.8.4 fallback LocalDate.now()
        // mogl przypisac stary ekran Pyszne do dzisiejszej daty podczas przejscia.
        val explicitDates = PyszneHistoryParser.parseDatesFromText(text).distinct()
        if (explicitDates.size != 1) return null
        val date = explicitDates.single()

        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val dateIndexes = lines.mapIndexedNotNull { index, line ->
            if (PyszneHistoryParser.parseDateFromText(line) == date) index else null
        }
        if (dateIndexes.isEmpty()) return null

        // Count i kwota musza pochodzic z GORNEJ karty tego samego dnia:
        // data -> Przychody -> kwota -> N offers accepted. Nie laczymy wartosci
        // z roznych fragmentow ekranu ani z poprzedniego dnia.
        val headerWindows = dateIndexes.map { index ->
            lines.subList(index, minOf(lines.size, index + 10)).joinToString("\n")
        }

        val headerCandidates = headerWindows.mapNotNull { window ->
            val counts = orderCountRegex.findAll(window)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .filter { it in 1..300 }
                .toList()
            val amounts = moneyRegex.findAll(window)
                .mapNotNull { match ->
                    match.groupValues.getOrNull(1)
                        ?.replace(" ", "")
                        ?.replace(',', '.')
                        ?.toDoubleOrNull()
                }
                .filter { it in 0.0..100_000.0 }
                .toList()

            val count = counts.firstOrNull() ?: return@mapNotNull null
            // W gornej karcie pierwsza jawna kwota po dacie jest lacznym przychodem.
            val amount = amounts.firstOrNull() ?: return@mapNotNull null
            count to amount
        }

        if (headerCandidates.isEmpty()) return null

        // Jesli OCR zwrocil kilka reprezentacji tego samego ekranu, wszystkie
        // musza wskazywac ten sam zestaw danych. Przy konflikcie czekamy na kolejny skan.
        val distinctCandidates = headerCandidates
            .map { it.first to kotlin.math.round(it.second * 100.0).toInt() }
            .distinct()
        if (distinctCandidates.size != 1) return null

        val (count, amountCents) = distinctCandidates.single()
        return PyszneDayReference(
            date = date,
            orderCount = count,
            amountPln = amountCents / 100.0,
            orderIds = parseOrderIds(text)
        )
    }
}

class PyszneLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("pyszne_delivery_history", Context.MODE_PRIVATE)

    @Synchronized
    fun save(entry: PyszneDeliveryLog): PyszneSaveResult {
        val current = all().toMutableList()
        val duplicateIndex = current.indexOfFirst { it.key == entry.key || it.fingerprint == entry.fingerprint }
        if (duplicateIndex >= 0) {
            val existing = current[duplicateIndex]
            if (needsUpgrade(existing, entry)) {
                current[duplicateIndex] = existing.copy(
                    orderId = existing.orderId ?: entry.orderId,
                    restaurant = if (restaurantQuality(entry.restaurant) > restaurantQuality(existing.restaurant)) entry.restaurant else existing.restaurant,
                    cancelled = existing.cancelled || entry.cancelled
                )
                persist(current.sortedWith(compareBy<PyszneDeliveryLog> { it.date }.thenBy { it.acceptedMinuteOfDay ?: Int.MAX_VALUE }))
            }
            return PyszneSaveResult.DUPLICATE
        }
        current += entry
        persist(current.sortedWith(compareBy<PyszneDeliveryLog> { it.date }.thenBy { it.acceptedMinuteOfDay ?: Int.MAX_VALUE }))
        return PyszneSaveResult.SAVED
    }

    fun contains(entry: PyszneDeliveryLog): Boolean {
        val existing = all().firstOrNull { it.key == entry.key || it.fingerprint == entry.fingerprint } ?: return false
        return !needsUpgrade(existing, entry)
    }

    private fun needsUpgrade(existing: PyszneDeliveryLog, incoming: PyszneDeliveryLog): Boolean =
        (existing.orderId.isNullOrBlank() && !incoming.orderId.isNullOrBlank()) ||
            restaurantQuality(incoming.restaurant) > restaurantQuality(existing.restaurant) ||
            (!existing.cancelled && incoming.cancelled)

    private fun restaurantQuality(value: String): Int {
        val v = value.trim()
        if (isPlaceholderRestaurant(v)) return 0
        if (v.endsWith(")") && !v.contains("(")) return 1
        if (v.startsWith("(") || v.length < 4) return 1
        if (v.contains("(") && v.contains(")") && v.indexOf('(') < v.lastIndexOf(')')) return 4
        if (Regex(""".*\b\d{1,4}[A-Za-z]?\b.*""").matches(v)) return 3
        return 2
    }

    private fun isPlaceholderRestaurant(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank() || v.equals("Nieznana restauracja", ignoreCase = true)) return true
        return Regex("""(?i)^[-+]?\d{1,5}(?:[ .]\d{3})*[,.]\d{2}\s*(?:zł|zl|pln)?$""").matches(v)
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
        val previous = refs[reference.date]
        refs[reference.date] = reference.copy(
            orderIds = mergeOrderIds(previous?.orderIds.orEmpty(), reference.orderIds),
            capturedAtMillis = System.currentTimeMillis()
        )
        persistDayReferences(refs)
    }

    /** Dopisuje numery widoczne po przewinieciu listy, gdy naglowek dnia nie jest juz na ekranie. */
    @Synchronized
    fun mergeDayOrderIds(date: LocalDate, orderIds: List<String>) {
        if (orderIds.isEmpty()) return
        val refs = allDayReferences().toMutableMap()
        val previous = refs[date] ?: return
        refs[date] = previous.copy(
            orderIds = mergeOrderIds(previous.orderIds, orderIds),
            capturedAtMillis = System.currentTimeMillis()
        )
        persistDayReferences(refs)
    }

    fun dayReference(date: LocalDate): PyszneDayReference? = allDayReferences()[date]

    fun latestDayReference(): PyszneDayReference? = allDayReferences().values.maxByOrNull { it.capturedAtMillis }

    private fun mergeOrderIds(first: List<String>, second: List<String>): List<String> =
        (first + second)
            .map { it.trim().removePrefix("#").uppercase(Locale.ROOT) }
            .filter { it.matches(Regex("""[A-Z0-9]{6}""")) }
            .distinct()

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
                            orderIds = item.optJSONArray("orderIds")?.let { array ->
                                buildList {
                                    for (i in 0 until array.length()) {
                                        array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                                    }
                                }
                            }.orEmpty(),
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
        persistDayReferences(refs)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).remove(KEY_DAY_REFERENCES).apply()
    }

    private fun persistDayReferences(refs: Map<LocalDate, PyszneDayReference>) {
        val obj = JSONObject()
        refs.toSortedMap().forEach { (date, item) ->
            val ids = JSONArray().apply { item.orderIds.forEach { put(it) } }
            obj.put(
                date.toString(),
                JSONObject()
                    .put("count", item.orderCount)
                    .put("amount", item.amountPln)
                    .put("orderIds", ids)
                    .put("capturedAt", item.capturedAtMillis)
            )
        }
        prefs.edit().putString(KEY_DAY_REFERENCES, obj.toString()).apply()
    }

    private fun persist(entries: List<PyszneDeliveryLog>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("key", entry.key)
                    .put("fingerprint", entry.fingerprint)
                    .put("orderId", entry.orderId ?: JSONObject.NULL)
                    .put("date", entry.date.toString())
                    .put("acceptedMinute", entry.acceptedMinuteOfDay ?: JSONObject.NULL)
                    .put("restaurant", entry.restaurant)
                    .put("amount", entry.amountPln)
                    .put("distance", entry.distanceKm)
                    .put("durationSeconds", entry.durationSeconds)
                    .put("cancelled", entry.cancelled)
                    .put("savedAt", entry.savedAtMillis)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun decode(obj: JSONObject): PyszneDeliveryLog? = runCatching {
        PyszneDeliveryLog(
            key = obj.getString("key"),
            fingerprint = obj.optString("fingerprint", obj.getString("key")),
            orderId = if (obj.isNull("orderId")) null else obj.optString("orderId").takeIf { it.isNotBlank() },
            date = LocalDate.parse(obj.getString("date")),
            acceptedMinuteOfDay = if (obj.isNull("acceptedMinute")) null else obj.getInt("acceptedMinute"),
            restaurant = obj.optString("restaurant", "Nieznana restauracja").let {
                if (isPlaceholderRestaurant(it)) "Nieznana restauracja" else it
            },
            amountPln = obj.getDouble("amount"),
            distanceKm = obj.getDouble("distance"),
            durationSeconds = obj.getInt("durationSeconds"),
            cancelled = obj.optBoolean("cancelled", false),
            savedAtMillis = obj.optLong("savedAt", 0L)
        )
    }.getOrNull()

    companion object {
        private const val KEY_ENTRIES = "entries_v1"
        private const val KEY_DAY_REFERENCES = "day_references_v2"
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
    val poorOrders: Int,
    val cancelledOrders: Int
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
    val cancelledOrders: Int,
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
                    poorOrders = statuses.count { it == ProfitabilityStatus.UNPROFITABLE },
                    cancelledOrders = group.count { it.cancelled }
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
            cancelledOrders = daily.count { it.cancelled },
            restaurants = restaurants
        )
    }

    /**
     * Podsumowanie dnia moze miec > 6 godzin. Zwykly kalkulator ofert celowo
     * odrzuca tak dlugi czas jako niemozliwy dla pojedynczego zlecenia, dlatego
     * agregaty dnia/restauracji liczymy bez limitu 360 min.
     */
    private fun profitabilityFor(
        entries: List<PyszneDeliveryLog>,
        rules: ProfitabilityCalculator.Rules,
        decisionBasis: DecisionBasis,
        zusPercent: Double
    ): Profitability {
        val gross = entries.sumOf { it.amountPln }
        val distance = entries.sumOf { it.distanceKm }
        val durationSeconds = entries.sumOf { it.durationSeconds }.coerceAtLeast(0)

        val vehicleCostPerKm = rules.vehicleCostPerKm.takeIf { it.isFinite() && it >= 0.0 } ?: 0.35
        val minimumPerKm = rules.minimumNetPerKm.takeIf { it.isFinite() && it >= 0.0 } ?: 2.50
        val tolerancePerKm = rules.toleranceNetPerKm.takeIf { it.isFinite() && it >= 0.0 } ?: 0.50
        val minimumPerHour = rules.minimumNetPerHour.takeIf { it.isFinite() && it >= 0.0 } ?: 35.0
        val tolerancePerHour = rules.toleranceNetPerHour.takeIf { it.isFinite() && it >= 0.0 } ?: 5.0
        val safeZusPercent = zusPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0

        val afterZus = gross * (1.0 - safeZusPercent / 100.0)
        val net = afterZus - distance * vehicleCostPerKm
        val perKm = distance.takeIf { it > 0.0 }?.let { net / it }
        val perHour = durationSeconds.takeIf { it > 0 }?.let { net / (it / 3600.0) }
        val status = aggregateStatus(
            perKm = perKm,
            perHour = perHour,
            minimumPerKm = minimumPerKm,
            tolerancePerKm = tolerancePerKm,
            minimumPerHour = minimumPerHour,
            tolerancePerHour = tolerancePerHour,
            decisionBasis = decisionBasis
        )

        return Profitability(
            grossPln = gross,
            afterZusPln = afterZus,
            netPln = net,
            distanceKm = distance,
            durationMinutes = durationSeconds.takeIf { it > 0 }?.let { kotlin.math.ceil(it / 60.0).toInt() },
            extraTimeMinutes = 0,
            zusPercent = safeZusPercent,
            netPerKm = perKm,
            netPerHour = perHour,
            profitable = when (status) {
                ProfitabilityStatus.PROFITABLE -> true
                ProfitabilityStatus.ALMOST_PROFITABLE, ProfitabilityStatus.UNPROFITABLE -> false
                ProfitabilityStatus.NO_TIME -> null
            },
            status = status,
            pickupTimeMinutesOfDay = null,
            deliveryTimeMinutesOfDay = null,
            durationSource = if (durationSeconds > 0) DurationSource.DIRECT_TOTAL else DurationSource.UNKNOWN
        )
    }

    private fun aggregateStatus(
        perKm: Double?,
        perHour: Double?,
        minimumPerKm: Double,
        tolerancePerKm: Double,
        minimumPerHour: Double,
        tolerancePerHour: Double,
        decisionBasis: DecisionBasis
    ): ProfitabilityStatus {
        val almostKm = (minimumPerKm - tolerancePerKm).coerceAtLeast(0.0)
        val almostHour = (minimumPerHour - tolerancePerHour).coerceAtLeast(0.0)

        fun kmStatus(): ProfitabilityStatus {
            val value = perKm?.takeIf { it.isFinite() } ?: return ProfitabilityStatus.NO_TIME
            return when {
                value >= minimumPerKm -> ProfitabilityStatus.PROFITABLE
                value >= almostKm -> ProfitabilityStatus.ALMOST_PROFITABLE
                else -> ProfitabilityStatus.UNPROFITABLE
            }
        }

        fun hourStatus(): ProfitabilityStatus {
            val value = perHour?.takeIf { it.isFinite() } ?: return ProfitabilityStatus.NO_TIME
            return when {
                value >= minimumPerHour -> ProfitabilityStatus.PROFITABLE
                value >= almostHour -> ProfitabilityStatus.ALMOST_PROFITABLE
                else -> ProfitabilityStatus.UNPROFITABLE
            }
        }

        return when (decisionBasis) {
            DecisionBasis.PER_KM -> kmStatus()
            DecisionBasis.HOURLY -> hourStatus()
            DecisionBasis.MIXED -> {
                val km = kmStatus()
                val hour = hourStatus()
                when {
                    km == ProfitabilityStatus.NO_TIME || hour == ProfitabilityStatus.NO_TIME -> ProfitabilityStatus.NO_TIME
                    km == ProfitabilityStatus.UNPROFITABLE || hour == ProfitabilityStatus.UNPROFITABLE -> ProfitabilityStatus.UNPROFITABLE
                    km == ProfitabilityStatus.ALMOST_PROFITABLE || hour == ProfitabilityStatus.ALMOST_PROFITABLE -> ProfitabilityStatus.ALMOST_PROFITABLE
                    else -> ProfitabilityStatus.PROFITABLE
                }
            }
        }
    }

}

fun PyszneDaySummary.shareText(nickname: String = ""): String {
    val locale = Locale.forLanguageTag("pl-PL")
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val who = nickname.trim().takeIf { it.isNotBlank() }
    val namedRestaurants = restaurants.filterNot { it.name.equals("Nieznana restauracja", ignoreCase = true) }
    val best = namedRestaurants.firstOrNull()
    val worst = namedRestaurants.minByOrNull { it.netPerHour ?: Double.POSITIVE_INFINITY }

    return buildString {
        appendLine("🏁 FUJARA — PODSUMOWANIE DNIA")
        appendLine("🍔 Pyszne • ${date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
        who?.let { appendLine("👤 $it") }
        appendLine()
        appendLine("💰 Przychód: ${String.format(locale, "%.2f", grossPln)} zł")
        appendLine("📦 Zlecenia: $orderCount${if (cancelledOrders > 0) "  •  anulowane: $cancelledOrders" else ""}")
        appendLine("🚗 Dystans: ${String.format(locale, "%.1f", distanceKm)} km")
        appendLine("⏱ Czas: ${hours}h ${minutes}min")
        appendLine()
        appendLine("⚡ PO KOSZTACH")
        appendLine("💵 ${String.format(locale, "%.0f", netPerHour ?: 0.0)} zł/h")
        appendLine("🛣 ${String.format(locale, "%.2f", netPerKm ?: 0.0)} zł/km")
        appendLine()
        appendLine("🟢 SUPER: $goodOrders")
        appendLine("🟡 NA STYK: $borderlineOrders")
        appendLine("🔴 FUJARA: $poorOrders")
        if (best != null || worst != null) {
            appendLine()
            best?.let { appendLine("🏆 Najlepiej: ${it.name}") }
            if (worst != null && worst.name != best?.name) appendLine("🪈 Najsłabiej: ${worst.name}")
        }
        append("\n#FUJARA")
    }
}
