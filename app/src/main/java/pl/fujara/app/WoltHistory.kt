package pl.fujara.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Wolt → Zarobki brutto:
 *  - ekran "Podsumowanie dzienne": lista "dd.MM.yyyy  kwota" (oficjalne sumy dni),
 *  - ekran dnia: "14 LIS 2026 / 26,78 zł / Zrealizowane zamówienia / <lokal> HH:MM /
 *    Pełna płatność (w tym dystans: 8.22 km)  26,78 zł" (pojedyncze zlecenia).
 * Oba sa zapisywane lokalnie, zeby "Analiza → Wolt" pokazywala prawdziwe kwoty Wolt,
 * a nie tylko szacunki z kart ofert.
 */
data class WoltDayTotal(val date: LocalDate, val grossPln: Double)

data class WoltOrder(
    val date: LocalDate,
    val minuteOfDay: Int,
    val venue: String,
    val grossPln: Double,
    val distanceKm: Double?,
    val stops: Int,
    /** "official" – z ekranu Zarobki brutto; "live" – zmierzone przez FUJARE (akceptacja → dostarczone). */
    val source: String = SOURCE_OFFICIAL,
    /** Zmierzony czas realizacji (akceptacja → dostarczone) – tylko source=live. */
    val durationMinutes: Int? = null,
    val acceptedAtMillis: Long? = null,
    val deliveredAtMillis: Long? = null
) {
    val key: String
        get() = "${date}|$minuteOfDay|${venue.lowercase(Locale.ROOT)}|${String.format(Locale.ROOT, "%.2f", grossPln)}"

    /** Klucz do laczenia wpisu "live" z oficjalnym: ta sama data, kwota i podobny lokal. */
    val matchKey: String
        get() = "${date}|${String.format(Locale.ROOT, "%.2f", grossPln)}"

    companion object {
        const val SOURCE_OFFICIAL = "official"
        const val SOURCE_LIVE = "live"
    }
}

data class WoltDayDetails(val date: LocalDate, val totalPln: Double?, val orders: List<WoltOrder>)

object WoltHistoryParser {
    private val amount = Regex("""(\d{1,3}(?:[   ]\d{3})*|\d{1,6})[,.](\d{2})\s*(?:zł|zl|PLN)""", RegexOption.IGNORE_CASE)
    private val dottedDate = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""")
    private val monthDate = Regex("""(?i)\b(\d{1,2})\s+(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|pa[zź]|lis|gru)\w*\s+(\d{4})\b""")
    private val timeOnly = Regex("""^(\d{1,2}):(\d{2})$""")
    private val distance = Regex("""(?i)dystans\w*:\s*(\d{1,3}(?:[.,]\d{1,2})?)\s*km""")
    private val bundle = Regex("""(?i)\(dostaw\w*:\s*(\d)\)""")
    private val paymentLine = Regex("""(?i)^pe[lł]na\s+p[lł]atno[sś][cć]|^payment|^p[lł]atno[sś][cć]""")

    private val months = mapOf("sty" to 1, "lut" to 2, "mar" to 3, "kwi" to 4, "maj" to 5, "cze" to 6, "lip" to 7, "sie" to 8, "wrz" to 9, "paz" to 10, "paź" to 10, "lis" to 11, "gru" to 12)

    fun isDailySummaryScreen(text: String): Boolean =
        Regex("""(?i)podsumowanie\s+dzienne|daily\s+summary""").containsMatchIn(text) && dottedDate.containsMatchIn(text)

    fun isDayDetailsScreen(text: String): Boolean =
        Regex("""(?i)zrealizowane\s+zam[oó]wienia|completed\s+orders""").containsMatchIn(text) &&
            monthDate.containsMatchIn(text) && Regex("""(?i)dystans|distance""").containsMatchIn(text)

    private fun money(match: MatchResult): Double? {
        val whole = match.groupValues[1].replace(Regex("""[   ]"""), "")
        return "$whole.${match.groupValues[2]}".toDoubleOrNull()
    }

    /** "Podsumowanie dzienne": pary data → kwota. */
    fun parseDailySummary(text: String): List<WoltDayTotal> {
        if (!isDailySummaryScreen(text)) return emptyList()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val startIndex = lines.indexOfFirst { Regex("""(?i)podsumowanie\s+dzienne|daily\s+summary""").containsMatchIn(it) }.coerceAtLeast(0)
        val out = mutableListOf<WoltDayTotal>()
        var pendingDate: LocalDate? = null
        for (line in lines.drop(startIndex)) {
            val dateMatch = dottedDate.find(line)
            if (dateMatch != null) {
                pendingDate = runCatching {
                    LocalDate.of(dateMatch.groupValues[3].toInt(), dateMatch.groupValues[2].toInt(), dateMatch.groupValues[1].toInt())
                }.getOrNull()
                // kwota moze byc w tej samej linii
                val sameLine = amount.find(line.substring(dateMatch.range.last + 1))
                if (sameLine != null && pendingDate != null) {
                    money(sameLine)?.let { out += WoltDayTotal(pendingDate!!, it) }
                    pendingDate = null
                }
                continue
            }
            if (pendingDate != null) {
                val m = amount.find(line) ?: continue
                money(m)?.let { out += WoltDayTotal(pendingDate!!, it) }
                pendingDate = null
            }
        }
        return out.distinctBy { it.date }
    }

    /** Ekran jednego dnia: naglowek z data i suma oraz lista zlecen. */
    fun parseDayDetails(text: String): WoltDayDetails? {
        if (!isDayDetailsScreen(text)) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val headerMatch = monthDate.find(text) ?: return null
        val month = months[headerMatch.groupValues[2].lowercase(Locale.ROOT).take(3)] ?: return null
        val date = runCatching { LocalDate.of(headerMatch.groupValues[3].toInt(), month, headerMatch.groupValues[1].toInt()) }.getOrNull() ?: return null

        val headerLineIndex = lines.indexOfFirst { monthDate.containsMatchIn(it) }
        val total = lines.drop(headerLineIndex + 1).take(2).firstNotNullOfOrNull { amount.find(it)?.let(::money) }

        val ordersStart = lines.indexOfFirst { Regex("""(?i)zrealizowane\s+zam[oó]wienia|completed\s+orders""").containsMatchIn(it) }
        val body = if (ordersStart >= 0) lines.drop(ordersStart + 1) else lines
        val orders = mutableListOf<WoltOrder>()

        var venue: String? = null
        var minute: Int? = null
        var km: Double? = null
        var stops = 1
        fun flush(amountPln: Double) {
            val v = venue ?: return
            val m = minute ?: return
            orders += WoltOrder(date, m, v, amountPln, km, stops)
            venue = null; minute = null; km = null; stops = 1
        }
        for (raw in body) {
            val line = raw.trim()
            timeOnly.find(line)?.let { t ->
                minute = t.groupValues[1].toInt() * 60 + t.groupValues[2].toInt()
            }
            if (timeOnly.matches(line)) continue
            // "Lokal 17:12" w jednej linii
            val trailingTime = Regex("""^(.*\S)\s+(\d{1,2}):(\d{2})$""").find(line)
            if (trailingTime != null && !paymentLine.containsMatchIn(line)) {
                venue = trailingTime.groupValues[1]
                minute = trailingTime.groupValues[2].toInt() * 60 + trailingTime.groupValues[3].toInt()
                stops = bundle.find(venue!!)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                venue = venue!!.replace(bundle, "").trim()
                continue
            }
            if (paymentLine.containsMatchIn(line) || distance.containsMatchIn(line)) {
                km = distance.find(line)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
                amount.find(line)?.let(::money)?.let { flush(it) }
                continue
            }
            val m = amount.find(line)
            if (m != null && venue != null && minute != null) {
                money(m)?.let { flush(it) }
                continue
            }
            if (m == null && line.length in 2..80 && !line.startsWith("(")) {
                // Kolejny lokal.
                venue = line.replace(bundle, "").trim()
                stops = bundle.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            } else if (line.startsWith("(") && venue != null) {
                bundle.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { stops = it }
            }
        }
        return WoltDayDetails(date, total, orders.distinctBy { it.key })
    }
}

/** Oficjalne kwoty Wolt (sumy dni + zlecenia) – JSONL w filesDir. */
class WoltOrderStore(context: Context) {
    private val ordersFile = File(context.filesDir, ORDERS_FILE)
    private val totalsFile = File(context.filesDir, TOTALS_FILE)
    private val lock = Any()

    @Volatile private var ordersCache: MutableList<WoltOrder>? = null
    @Volatile private var totalsCache: MutableMap<LocalDate, Double>? = null

    fun saveOrders(orders: List<WoltOrder>): Int {
        synchronized(lock) {
            val list = loadOrdersLocked()
            val known = list.map { it.key }.toMutableSet()
            var added = 0
            orders.forEach { order ->
                if (known.add(order.key)) {
                    list += order
                    added++
                    runCatching { ordersFile.appendText(encodeOrder(order).toString() + "\n") }
                }
            }
            return added
        }
    }

    fun saveDayTotals(totals: List<WoltDayTotal>): Int {
        synchronized(lock) {
            val map = loadTotalsLocked()
            var changed = 0
            totals.forEach { t ->
                val old = map[t.date]
                if (old == null || kotlin.math.abs(old - t.grossPln) > 0.005) {
                    map[t.date] = t.grossPln
                    changed++
                }
            }
            if (changed > 0) rewriteTotalsLocked(map)
            return changed
        }
    }

    fun orders(): List<WoltOrder> = synchronized(lock) { loadOrdersLocked().toList() }
    fun ordersForDate(date: LocalDate): List<WoltOrder> = orders().filter { it.date == date }.sortedBy { it.minuteOfDay }

    /**
     * Zlecenia dnia bez duplikatow: oficjalny wpis Wolt i zmierzony przez FUJARE wpis "live"
     * o tej samej kwocie sa laczone (kwota/dystans z Wolt, czas realizacji z pomiaru).
     */
    fun mergedForDate(date: LocalDate): List<WoltOrder> = WoltOrderMerger.merge(ordersForDate(date))

    /** Zapis zmierzonego zlecenia (akceptacja → dostarczone). Zwraca true, gdy dodano nowy wpis. */
    fun saveLive(order: WoltOrder): Boolean = saveOrders(listOf(order.copy(source = WoltOrder.SOURCE_LIVE))) > 0
    fun dayTotal(date: LocalDate): Double? = synchronized(lock) { loadTotalsLocked()[date] }
    fun dayTotals(): Map<LocalDate, Double> = synchronized(lock) { loadTotalsLocked().toMap() }

    fun clear() {
        synchronized(lock) {
            ordersCache = mutableListOf()
            totalsCache = mutableMapOf()
            runCatching { ordersFile.delete() }
            runCatching { totalsFile.delete() }
        }
    }

    fun exportJson(): JSONObject = JSONObject()
        .put("orders", JSONArray().apply { orders().forEach { put(encodeOrder(it)) } })
        .put("totals", JSONArray().apply { dayTotals().forEach { (d, v) -> put(JSONObject().put("date", d.toString()).put("gross", v)) } })

    fun importJson(obj: JSONObject) {
        obj.optJSONArray("orders")?.let { arr ->
            val list = mutableListOf<WoltOrder>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::decodeOrder)?.let(list::add)
            saveOrders(list)
        }
        obj.optJSONArray("totals")?.let { arr ->
            val list = mutableListOf<WoltDayTotal>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { WoltDayTotal(LocalDate.parse(o.getString("date")), o.getDouble("gross")) }.getOrNull()?.let(list::add)
            }
            saveDayTotals(list)
        }
    }

    private fun loadOrdersLocked(): MutableList<WoltOrder> {
        ordersCache?.let { return it }
        val list = mutableListOf<WoltOrder>()
        if (ordersFile.exists()) runCatching {
            ordersFile.forEachLine { line ->
                if (line.isNotBlank()) runCatching { JSONObject(line) }.getOrNull()?.let(::decodeOrder)?.let(list::add)
            }
        }
        ordersCache = list
        return list
    }

    private fun loadTotalsLocked(): MutableMap<LocalDate, Double> {
        totalsCache?.let { return it }
        val map = mutableMapOf<LocalDate, Double>()
        if (totalsFile.exists()) runCatching {
            totalsFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                runCatching { map[LocalDate.parse(o.getString("date"))] = o.getDouble("gross") }
            }
        }
        totalsCache = map
        return map
    }

    private fun rewriteTotalsLocked(map: Map<LocalDate, Double>) {
        totalsCache = map.toMutableMap()
        runCatching {
            totalsFile.bufferedWriter().use { w ->
                map.entries.sortedBy { it.key }.forEach { (d, v) -> w.write(JSONObject().put("date", d.toString()).put("gross", v).toString()); w.newLine() }
            }
        }
    }

    private fun encodeOrder(o: WoltOrder): JSONObject = JSONObject()
        .put("date", o.date.toString())
        .put("minute", o.minuteOfDay)
        .put("venue", o.venue)
        .put("gross", o.grossPln)
        .put("km", o.distanceKm ?: JSONObject.NULL)
        .put("stops", o.stops)
        .put("source", o.source)
        .put("duration", o.durationMinutes ?: JSONObject.NULL)
        .put("acceptedAt", o.acceptedAtMillis ?: JSONObject.NULL)
        .put("deliveredAt", o.deliveredAtMillis ?: JSONObject.NULL)

    private fun decodeOrder(o: JSONObject): WoltOrder? = runCatching {
        WoltOrder(
            date = LocalDate.parse(o.getString("date")),
            minuteOfDay = o.getInt("minute"),
            venue = o.getString("venue"),
            grossPln = o.getDouble("gross"),
            distanceKm = if (o.isNull("km")) null else o.optDouble("km"),
            stops = o.optInt("stops", 1),
            source = o.optString("source", WoltOrder.SOURCE_OFFICIAL),
            durationMinutes = if (o.isNull("duration")) null else o.optInt("duration"),
            acceptedAtMillis = if (o.isNull("acceptedAt")) null else o.optLong("acceptedAt"),
            deliveredAtMillis = if (o.isNull("deliveredAt")) null else o.optLong("deliveredAt")
        )
    }.getOrNull()

    companion object {
        const val ORDERS_FILE = "wolt_orders.jsonl"
        const val TOTALS_FILE = "wolt_day_totals.jsonl"
    }
}

object WoltOrderMerger {
    fun merge(orders: List<WoltOrder>): List<WoltOrder> {
        val official = orders.filter { it.source == WoltOrder.SOURCE_OFFICIAL }
        val live = orders.filter { it.source == WoltOrder.SOURCE_LIVE }.toMutableList()
        val out = mutableListOf<WoltOrder>()
        official.forEach { off ->
            val match = live.firstOrNull { it.matchKey == off.matchKey && kotlin.math.abs(it.minuteOfDay - off.minuteOfDay) <= 90 }
            if (match != null) {
                live.remove(match)
                out += off.copy(durationMinutes = match.durationMinutes, acceptedAtMillis = match.acceptedAtMillis, deliveredAtMillis = match.deliveredAtMillis)
            } else {
                out += off
            }
        }
        out += live
        return out.sortedBy { it.minuteOfDay }
    }
}
