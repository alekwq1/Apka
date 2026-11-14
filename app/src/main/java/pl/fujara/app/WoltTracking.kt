package pl.fujara.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Odczyt ekranu Wolt "Finanse": ZAROBKI BRUTTO + termin nastepnej wyplaty.
 * Kwota dotyczy biezacego okresu rozliczeniowego, nie jednego dnia – dlatego
 * zapisujemy snapshoty z czasem i liczymy przyrosty.
 */
data class WoltEarningsSnapshot(
    val capturedAtMillis: Long,
    val grossPln: Double,
    val nextPayoutLabel: String?
) {
    val date: LocalDate
        get() = Instant.ofEpochMilli(capturedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

object WoltFinanceParser {
    private val grossLabel = Regex("""(?i)zarobki\s+brutto|gross\s+earnings|заробіток\s+брутто|заработок\s+брутто""")
    private val amount = Regex("""(?<![\d,.])(\d{1,3}(?:[   ]\d{3})*|\d{1,6})[,.](\d{2})\s*(?:zł|zl|PLN)""", RegexOption.IGNORE_CASE)
    private val nextPayout = Regex("""(?i)(nast[eę]pna\s+wyp[lł]ata[^\n]{0,40}|next\s+payout[^\n]{0,40})""")

    fun isFinanceScreen(text: String): Boolean = grossLabel.containsMatchIn(text)

    fun parse(text: String, nowMillis: Long = System.currentTimeMillis()): WoltEarningsSnapshot? {
        val label = grossLabel.find(text) ?: return null
        // Kwota musi byc w poblizu etykiety (ta sama karta), nie z listy transakcji.
        val window = text.substring(label.range.first, minOf(text.length, label.range.last + 90))
        val match = amount.find(window) ?: return null
        val whole = match.groupValues[1].replace(Regex("""[   ]"""), "")
        val value = "${whole}.${match.groupValues[2]}".toDoubleOrNull() ?: return null
        if (value !in 0.0..100_000.0) return null
        return WoltEarningsSnapshot(
            capturedAtMillis = nowMillis,
            grossPln = value,
            nextPayoutLabel = nextPayout.find(text)?.groupValues?.getOrNull(1)?.trim()
        )
    }
}

/**
 * Ekran aktywnej dostawy Wolt: "Odbierz na 18:04" + nazwa lokalu + "Nawiguj".
 * Po odbiorze pojawia sie "Dostarcz do"/"Dostarcz na".
 */
data class WoltActiveDelivery(
    val pickupMinuteOfDay: Int?,
    val deliveryMinuteOfDay: Int?,
    val venue: String?,
    val stage: Stage
) {
    enum class Stage { TO_PICKUP, TO_CUSTOMER, UNKNOWN }
}

object WoltActiveDeliveryParser {
    private val pickupAt = Regex("""(?i)(?:odbierz|odbi[oó]r)\s+(?:na|o|do)\s+(\d{1,2})[:.](\d{2})""")
    private val deliverAt = Regex("""(?i)(?:dostarcz|dostawa)\s+(?:na|o|do)\s+(\d{1,2})[:.](\d{2})""")
    private val navigate = Regex("""(?i)(?<!\p{L})(?:nawiguj|navigate|навігувати|навигация|маршрут)(?!\p{L})""")
    private val acceptMarker = Regex("""(?i)(?<!\p{L})(?:akceptuj|accept|zaakceptuj)(?!\p{L})""")
    private val earningsMarker = Regex("""(?i)spodziewany\s+zarobek|expected\s+earnings|szacowan\w*\s+zarob""")
    private val pickedUp = Regex("""(?i)(?<!\p{L})(?:dostarcz\s+do|w\s+drodze\s+do\s+klienta|deliver\s+to|odebrano|picked\s+up)(?!\p{L})""")

    fun isActiveDeliveryScreen(text: String): Boolean {
        if (acceptMarker.containsMatchIn(text) || earningsMarker.containsMatchIn(text)) return false
        return navigate.containsMatchIn(text) && (pickupAt.containsMatchIn(text) || deliverAt.containsMatchIn(text) || pickedUp.containsMatchIn(text))
    }

    fun parse(text: String): WoltActiveDelivery? {
        if (!isActiveDeliveryScreen(text)) return null
        val pickup = pickupAt.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }?.takeIf { it in 0..1439 }
        val delivery = deliverAt.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }?.takeIf { it in 0..1439 }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val anchorIndex = lines.indexOfFirst { pickupAt.containsMatchIn(it) || deliverAt.containsMatchIn(it) }
        val venue = if (anchorIndex >= 0) {
            lines.drop(anchorIndex + 1)
                .take(3)
                .firstOrNull { line ->
                    line.length in 3..60 &&
                        !navigate.containsMatchIn(line) &&
                        !Regex("""^\d""").containsMatchIn(line) &&
                        !Regex("""(?i)^(?:odbierz|dostarcz)""").containsMatchIn(line)
                }
        } else {
            null
        }
        val stage = when {
            pickedUp.containsMatchIn(text) || (delivery != null && pickup == null) -> WoltActiveDelivery.Stage.TO_CUSTOMER
            pickup != null -> WoltActiveDelivery.Stage.TO_PICKUP
            else -> WoltActiveDelivery.Stage.UNKNOWN
        }
        return WoltActiveDelivery(pickupMinuteOfDay = pickup, deliveryMinuteOfDay = delivery, venue = venue, stage = stage)
    }
}

/** Dodatkowe informacje z karty oferty Wolt: restauracja i liczba dostaw w paczce. */
object WoltOfferDetailsParser {
    private val from = Regex("""(?i)(?:dostawa|dostaw\(y\)|dostawy|delivery|deliveries)\s+od\s*\n?\s*([^\n]{2,80})""")
    private val bundle = Regex("""(?i)(\d)\s*dostaw""")

    fun restaurant(text: String): String? {
        val raw = from.find(text)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return raw.replace(Regex("""\s*\+\d+$"""), "").trim().trimEnd(',').takeIf { it.length >= 2 }
    }

    fun stops(text: String): Int {
        val lower = text.lowercase()
        if ("dostaw(y) od" in lower || "dostawy od" in lower) {
            return bundle.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 2
        }
        return 1
    }
}

/** Snapshoty "Zarobki brutto" + zestawienie dnia Wolt z zaakceptowanych ofert. */
class WoltEarningsStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    @Volatile
    private var cache: MutableList<WoltEarningsSnapshot>? = null

    /** Zapisuje snapshot, gdy kwota lub dzien sie zmienily (brak spamu co 1,2 s). */
    fun record(snapshot: WoltEarningsSnapshot): Boolean {
        synchronized(lock) {
            val list = loadLocked()
            val last = list.lastOrNull()
            if (last != null && last.date == snapshot.date && kotlin.math.abs(last.grossPln - snapshot.grossPln) < 0.005) {
                return false
            }
            list += snapshot
            runCatching { file.appendText(encode(snapshot).toString() + "\n") }
            if (list.size > 4000) rewriteLocked(list.takeLast(3000))
            return true
        }
    }

    fun all(): List<WoltEarningsSnapshot> = synchronized(lock) { loadLocked().toList() }

    fun latest(): WoltEarningsSnapshot? = all().lastOrNull()

    fun forDate(date: LocalDate): List<WoltEarningsSnapshot> = all().filter { it.date == date }

    /**
     * Szacowany przyrost "Zarobki brutto" w danym dniu: ostatni snapshot dnia minus
     * ostatni snapshot sprzed tego dnia (albo pierwszy snapshot dnia, jesli okres
     * rozliczeniowy sie zresetowal lub nie ma wczesniejszych).
     */
    fun dayDelta(date: LocalDate): Double? {
        val list = all()
        val today = list.filter { it.date == date }
        if (today.isEmpty()) return null
        val last = today.last().grossPln
        val baseline = list.lastOrNull { it.date.isBefore(date) }?.grossPln
        val base = when {
            baseline == null -> today.first().grossPln
            baseline > last + 0.005 -> 0.0 // nowy okres rozliczeniowy
            else -> baseline
        }
        return (last - base).coerceAtLeast(0.0)
    }

    fun clear() {
        synchronized(lock) {
            cache = mutableListOf()
            runCatching { file.delete() }
        }
    }

    fun exportJsonArray(): JSONArray = JSONArray().apply { all().forEach { put(encode(it)) } }

    fun importJsonArray(array: JSONArray) {
        synchronized(lock) {
            val list = loadLocked()
            val known = list.map { it.capturedAtMillis }.toMutableSet()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val snap = decode(obj) ?: continue
                if (known.add(snap.capturedAtMillis)) list += snap
            }
            rewriteLocked(list.sortedBy { it.capturedAtMillis })
        }
    }

    private fun loadLocked(): MutableList<WoltEarningsSnapshot> {
        cache?.let { return it }
        val list = mutableListOf<WoltEarningsSnapshot>()
        if (file.exists()) {
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    runCatching { JSONObject(line) }.getOrNull()?.let(::decode)?.let(list::add)
                }
            }
        }
        cache = list
        return list
    }

    private fun rewriteLocked(list: List<WoltEarningsSnapshot>) {
        cache = list.toMutableList()
        runCatching {
            file.bufferedWriter().use { w -> list.forEach { w.write(encode(it).toString()); w.newLine() } }
        }
    }

    private fun encode(s: WoltEarningsSnapshot): JSONObject = JSONObject()
        .put("at", s.capturedAtMillis)
        .put("gross", s.grossPln)
        .put("payout", s.nextPayoutLabel ?: JSONObject.NULL)

    private fun decode(o: JSONObject): WoltEarningsSnapshot? = runCatching {
        WoltEarningsSnapshot(
            capturedAtMillis = o.getLong("at"),
            grossPln = o.getDouble("gross"),
            nextPayoutLabel = if (o.isNull("payout")) null else o.optString("payout")
        )
    }.getOrNull()

    companion object {
        const val FILE_NAME = "wolt_earnings.jsonl"
    }
}

/** Podsumowanie dnia Wolt budowane z zaakceptowanych ofert + snapshotow Finanse. */
data class WoltDaySummary(
    val date: LocalDate,
    val acceptedOrders: Int,
    val shownOffers: Int,
    val estimatedGrossPln: Double,
    val estimatedNetPln: Double,
    val distanceKm: Double,
    val estimatedMinutes: Int,
    val netPerHour: Double?,
    val netPerKm: Double?,
    val financeDeltaPln: Double?,
    val financeLatestGrossPln: Double?,
    val firstAcceptedMillis: Long?,
    val lastAcceptedMillis: Long?
) {
    /** Roznica miedzy tym, co Wolt pokazal w Finansach, a suma szacunkow z ofert. */
    val reconciliationPln: Double?
        get() = financeDeltaPln?.let { it - estimatedGrossPln }
}

object WoltDaySummaryCalculator {
    fun calculate(date: LocalDate, events: List<OfferEvent>, earnings: WoltEarningsStore?): WoltDaySummary {
        val wolt = events.filter { it.platform == CourierPlatform.WOLT && it.date == date }
        val accepted = wolt.filter { it.decision == OfferDecision.ACCEPTED }
        val minutes = accepted.sumOf { it.durationMinutes ?: 0 }
        val net = accepted.sumOf { it.netPln }
        val km = accepted.sumOf { it.distanceKm }
        return WoltDaySummary(
            date = date,
            acceptedOrders = accepted.size,
            shownOffers = wolt.size,
            estimatedGrossPln = accepted.sumOf { it.amountPln },
            estimatedNetPln = net,
            distanceKm = km,
            estimatedMinutes = minutes,
            netPerHour = minutes.takeIf { it > 0 }?.let { net / (it / 60.0) },
            netPerKm = km.takeIf { it > 0.0 }?.let { net / it },
            financeDeltaPln = earnings?.dayDelta(date),
            financeLatestGrossPln = earnings?.forDate(date)?.lastOrNull()?.grossPln,
            firstAcceptedMillis = accepted.minOfOrNull { it.shownAtMillis },
            lastAcceptedMillis = accepted.maxOfOrNull { it.shownAtMillis }
        )
    }
}
