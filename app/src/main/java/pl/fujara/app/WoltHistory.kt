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
    /** Data + minuta + kwota: nazwa lokalu bywa rozne odczytana (OCR / dostepnosc), wiec nie wchodzi do klucza. */
    val key: String
        get() = "$source|${date}|$minuteOfDay|${String.format(Locale.ROOT, "%.2f", grossPln)}"

    /** Klucz do laczenia wpisu "live" z oficjalnym: ta sama data, kwota i podobny lokal. */
    val matchKey: String
        get() = "${date}|${String.format(Locale.ROOT, "%.2f", grossPln)}"

    companion object {
        const val SOURCE_OFFICIAL = "official"
        const val SOURCE_LIVE = "live"
    }
}

data class WoltDayDetails(val date: LocalDate, val totalPln: Double?, val orders: List<WoltOrder>) {
    val ordersSum: Double get() = orders.sumOf { it.grossPln }
    val matchesTotal: Boolean get() = totalPln != null && orders.isNotEmpty() && kotlin.math.abs(ordersSum - totalPln) < 0.06
}

/** 1.8: odsiew smieci z odczytu Wolt (pasek statusu, naglowek z data, okienko nawigacji). */
object WoltOrderSanity {
    private val monthDate = Regex("""(?i)\b\d{1,2}\s+(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|pa[zź]|lis|gru)\w*\s+\d{4}\b""")
    private val navigation = Regex("""(?i)kieruj|w\s*kierunku|skr[eę][cć]\s+w|zjed[zź]|nawigac|[↑↗↖←→↙↘]""")
    private val statusBar = Regex("""^\d{1,2}:\d{2}\b.*\d""")

    fun isGarbageVenue(venue: String): Boolean {
        val v = venue.trim()
        if (v.isBlank()) return true
        if (monthDate.containsMatchIn(v) || navigation.containsMatchIn(v) || statusBar.containsMatchIn(v)) return true
        val letters = v.count { it.isLetter() }
        return letters < 3
    }

    /** Usuwa smieci i "zlecenie" rowne sumie dnia, gdy reszta juz sie do niej sumuje. */
    fun clean(orders: List<WoltOrder>, dayTotal: Double?): List<WoltOrder> {
        val ok = orders.filter { it.grossPln > 0.0 && (it.source == WoltOrder.SOURCE_LIVE || !isGarbageVenue(it.venue)) }
        if (dayTotal == null || ok.size < 2) return ok
        val suspicious = ok.filter { kotlin.math.abs(it.grossPln - dayTotal) < 0.06 }
        return ok.filterNot { s ->
            s in suspicious && kotlin.math.abs(ok.filter { it !== s }.sumOf { it.grossPln } - dayTotal) < 0.06 * ok.size
        }
    }
}

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

    /**
     * "Podsumowanie dzienne": pary data → kwota.
     *
     * BLAD 1.3: w drzewie dostepnosci Wolt kwota wiersza moze byc PRZED data
     * ("62,44 zł / 14.11.2026 / 243,28 zł / 13.11.2026"). Parowanie "data, potem kwota"
     * przesuwalo kwoty o jeden dzien (14.11 = 243,28 zł). Teraz sprawdzamy oba uklady
     * i wybieramy ten, ktory zgadza sie z suma "Zrealizowane zamówienia" / "Następna wypłata".
     * Gdy nie da sie tego potwierdzic, zapisujemy tylko pary z tej samej linii.
     */
    fun parseDailySummary(text: String): List<WoltDayTotal> {
        if (!isDailySummaryScreen(text)) return emptyList()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val startIndex = lines.indexOfFirst { Regex("""(?i)podsumowanie\s+dzienne|daily\s+summary""").containsMatchIn(it) }.coerceAtLeast(0)

        data class Tok(val date: LocalDate?, val amount: Double?, val line: Int)
        val tokens = mutableListOf<Tok>()
        val sameLine = mutableListOf<WoltDayTotal>()
        lines.drop(startIndex + 1).forEachIndexed { idx, line ->
            val d = dottedDate.find(line)?.let { m ->
                runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
            }
            val a = amount.find(line)?.let(::money)
            if (d != null && a != null) sameLine += WoltDayTotal(d, a)
            if (d != null) tokens += Tok(d, null, idx)
            if (a != null) tokens += Tok(null, a, idx)
        }
        if (sameLine.isNotEmpty() && sameLine.size == tokens.count { it.date != null }) return sameLine.distinctBy { it.date }

        val dates = tokens.filter { it.date != null }
        val amounts = tokens.filter { it.amount != null }
        fun pairAfter(): List<WoltDayTotal> = dates.mapNotNull { d ->
            val nextDate = dates.firstOrNull { it.line > d.line }?.line ?: Int.MAX_VALUE
            amounts.firstOrNull { it.line >= d.line && it.line < nextDate }?.let { WoltDayTotal(d.date!!, it.amount!!) }
        }
        fun pairBefore(): List<WoltDayTotal> = dates.mapNotNull { d ->
            val prevDate = dates.lastOrNull { it.line < d.line }?.line ?: Int.MIN_VALUE
            amounts.lastOrNull { it.line <= d.line && it.line > prevDate }?.let { WoltDayTotal(d.date!!, it.amount!!) }
        }
        val after = pairAfter().distinctBy { it.date }
        val before = pairBefore().distinctBy { it.date }

        val periodTotal = periodTotal(text)
        fun matches(list: List<WoltDayTotal>) =
            list.size == dates.size && periodTotal != null && kotlin.math.abs(list.sumOf { it.grossPln } - periodTotal) < 0.06
        return when {
            matches(after) && !matches(before) -> after
            matches(before) && !matches(after) -> before
            matches(after) && matches(before) -> if (tokens.firstOrNull()?.date != null) after else before
            // Brak sumy kontrolnej: ufamy tylko jednoznacznemu ukladowi (pierwszy token wyznacza kolejnosc).
            periodTotal == null && tokens.firstOrNull()?.date != null && after.size == dates.size -> after
            periodTotal == null && tokens.firstOrNull()?.amount != null && before.size == dates.size -> before
            else -> emptyList()
        }
    }

    /** Suma okresu: "Zrealizowane zamówienia 625,10 zł" albo "Następna wypłata … 625,10 zł". */
    fun periodTotal(text: String): Double? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val summaryIdx = lines.indexOfFirst { Regex("""(?i)^podsumowanie$|^summary$""").matches(it) }
        if (summaryIdx >= 0) {
            lines.drop(summaryIdx + 1).take(4).firstNotNullOfOrNull { amount.find(it)?.let(::money) }?.let { return it }
        }
        val payoutIdx = lines.indexOfFirst { Regex("""(?i)nast[eę]pna\s+wyp[lł]ata|next\s+payout""").containsMatchIn(it) }
        if (payoutIdx >= 0) {
            lines.drop(payoutIdx).take(3).firstNotNullOfOrNull { amount.find(it)?.let(::money) }?.let { return it }
        }
        return null
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
        // 1.8: nigdy nie czytamy naglowka (data + suma dnia) ani paska statusu jako zlecenia.
        val totalLine = if (total != null) lines.drop(headerLineIndex + 1).take(2).indexOfFirst { amount.containsMatchIn(it) }.let { if (it < 0) headerLineIndex else headerLineIndex + 1 + it } else headerLineIndex
        val body = lines.drop(maxOf(ordersStart + 1, totalLine + 1, 0))
        val orders = mutableListOf<WoltOrder>()

        var venue: String? = null
        var minute: Int? = null
        var km: Double? = null
        var stops = 1
        fun flush(amountPln: Double) {
            val v = venue?.replace(bundle, "")?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotBlank() }
            if (v == null || WoltOrderSanity.isGarbageVenue(v) || amountPln <= 0.0) {
                venue = null; minute = null; km = null; stops = 1
                return
            }
            val m = minute ?: return
            orders += WoltOrder(date, m, v, amountPln, km, stops)
            venue = null; minute = null; km = null; stops = 1
        }
        fun addVenueText(text: String) {
            bundle.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { stops = it }
            val clean = text.replace(bundle, "").trim()
            if (clean.isBlank()) return
            // Nazwa lokalu potrafi zajac dwie linie ("SMASHYNSKI, SMASHYNSKI - smash" / "burgers").
            venue = if (venue == null) clean else "$venue $clean"
        }
        for (raw in body) {
            val line = raw.trim()
            if (timeOnly.matches(line)) {
                val t = timeOnly.find(line)!!
                minute = t.groupValues[1].toInt() * 60 + t.groupValues[2].toInt()
                continue
            }
            val trailingTime = Regex("""^(.*\S)\s+(\d{1,2}):(\d{2})$""").find(line)
            if (trailingTime != null && !paymentLine.containsMatchIn(line) && amount.find(line) == null) {
                addVenueText(trailingTime.groupValues[1])
                minute = trailingTime.groupValues[2].toInt() * 60 + trailingTime.groupValues[3].toInt()
                continue
            }
            if (paymentLine.containsMatchIn(line) || distance.containsMatchIn(line)) {
                km = distance.find(line)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() ?: km
                amount.find(line)?.let(::money)?.let { flush(it) }
                continue
            }
            val m = amount.find(line)
            if (m != null) {
                if (venue != null && minute != null) money(m)?.let { flush(it) }
                continue
            }
            if (line.length in 1..80) addVenueText(line)
        }
        return WoltDayDetails(date, total, WoltOrderSanity.clean(orders.distinctBy { it.key }, total))
    }

    /**
     * Z kilku odczytow tego samego ekranu (drzewo dostepnosci, OCR) wybieramy ten, ktorego
     * zlecenia sumuja sie do sumy dnia; OCR bywa zasmiecony paskiem statusu i nawigacja.
     */
    fun bestDayDetails(texts: List<String>): WoltDayDetails? {
        val parsed = texts.mapNotNull { parseDayDetails(it) }
        if (parsed.isEmpty()) return null
        return parsed.firstOrNull { it.matchesTotal } ?: parsed.maxByOrNull { d -> d.orders.size - if (d.totalPln != null && d.orders.sumOf { it.grossPln } > d.totalPln + 0.05) 100 else 0 }
    }
}

/** Oficjalne kwoty Wolt (sumy dni + zlecenia) – JSONL w filesDir. */
class WoltOrderStore(context: Context) {
    private val migrationPrefs = context.getSharedPreferences("fujara_wolt_migrations", Context.MODE_PRIVATE)
    private val ordersFile = File(context.filesDir, ORDERS_FILE)
    private val totalsFile = File(context.filesDir, TOTALS_FILE)
    private val lock = Any()

    @Volatile private var ordersCache: MutableList<WoltOrder>? = null
    @Volatile private var totalsCache: MutableMap<LocalDate, Double>? = null

    fun saveOrders(orders: List<WoltOrder>): Int {
        synchronized(lock) {
            val list = loadOrdersLocked()
            var changed = 0
            orders.forEach { order ->
                val idx = list.indexOfFirst { it.key == order.key }
                if (idx < 0) {
                    list += order
                    changed++
                } else {
                    val better = richer(list[idx], order)
                    if (better != list[idx]) {
                        list[idx] = better
                        changed++
                    }
                }
            }
            if (changed > 0) rewriteOrdersLocked(list)
            return changed
        }
    }

    /** Z dwoch odczytow tego samego zlecenia bierzemy pelniejszy (dystans, dluzsza nazwa, pomiar czasu). */
    private fun richer(a: WoltOrder, b: WoltOrder): WoltOrder = a.copy(
        venue = if (b.venue.length > a.venue.length) b.venue else a.venue,
        distanceKm = a.distanceKm ?: b.distanceKm,
        stops = maxOf(a.stops, b.stops),
        durationMinutes = a.durationMinutes ?: b.durationMinutes,
        acceptedAtMillis = a.acceptedAtMillis ?: b.acceptedAtMillis,
        deliveredAtMillis = a.deliveredAtMillis ?: b.deliveredAtMillis
    )

    private fun rewriteOrdersLocked(list: List<WoltOrder>) {
        ordersCache = list.toMutableList()
        runCatching {
            ordersFile.bufferedWriter().use { w -> list.forEach { w.write(encodeOrder(it).toString()); w.newLine() } }
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
    fun mergedForDate(date: LocalDate): List<WoltOrder> = WoltOrderMerger.merge(WoltOrderSanity.clean(ordersForDate(date), dayTotal(date)))

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
        // Deduplikacja starych wpisow (1.3 zapisywal ten sam kurs kilka razy z roznie odczytana nazwa).
        val deduped = mutableListOf<WoltOrder>()
        list.forEach { o ->
            val idx = deduped.indexOfFirst { it.key == o.key }
            if (idx < 0) deduped += o else deduped[idx] = richer(deduped[idx], o)
        }
        ordersCache = deduped
        if (deduped.size != list.size) rewriteOrdersLocked(deduped)
        return deduped
    }

    private fun loadTotalsLocked(): MutableMap<LocalDate, Double> {
        totalsCache?.let { return it }
        // Migracja 1.4: sumy dni zapisane przez 1.3 mogly byc przesuniete o jeden dzien – kasujemy je;
        // odtworza sie po ponownym otwarciu Wolt → Zarobki brutto.
        if (!migrationPrefs.getBoolean("totals_v2", false)) {
            runCatching { totalsFile.delete() }
            migrationPrefs.edit().putBoolean("totals_v2", true).apply()
        }
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
            // Zmierzony wpis "live" ma nazwe z ekranu zlecenia (czasem adres), wiec laczymy tez po kwocie.
            val match = live.firstOrNull { it.matchKey == off.matchKey && kotlin.math.abs(it.minuteOfDay - off.minuteOfDay) <= 90 }
                ?: live.filter { kotlin.math.abs(it.grossPln - off.grossPln) <= 1.0 && kotlin.math.abs(it.minuteOfDay - off.minuteOfDay) <= 120 }
                    .minByOrNull { kotlin.math.abs(it.minuteOfDay - off.minuteOfDay) }
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
