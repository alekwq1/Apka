package pl.fujara.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Lokalny zbior pokazanych ofert i decyzji (JSON Lines w filesDir).
 * Append-only z okresowym przycinaniem do [maxEvents]. Nic nie wychodzi poza telefon.
 */
class OfferEventStore(context: Context, private val maxEvents: Int = 8000) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    @Volatile
    private var cache: MutableList<OfferEvent>? = null

    fun append(event: OfferEvent) {
        synchronized(lock) {
            val list = loadLocked()
            if (list.any { it.id == event.id }) return
            list += event
            runCatching { file.appendText(encode(event).toString() + "\n") }
            if (list.size > maxEvents + 500) rewriteLocked(list.takeLast(maxEvents))
        }
    }

    fun appendAll(events: List<OfferEvent>) = events.forEach(::append)

    fun all(): List<OfferEvent> = synchronized(lock) { loadLocked().toList() }

    fun forDate(date: LocalDate): List<OfferEvent> = all().filter { it.date == date }

    fun since(fromMillis: Long): List<OfferEvent> = all().filter { it.shownAtMillis >= fromMillis }

    fun delete(id: String) {
        synchronized(lock) {
            val list = loadLocked()
            if (list.removeAll { it.id == id }) rewriteLocked(list)
        }
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
            val known = list.map { it.id }.toMutableSet()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val event = decode(obj) ?: continue
                if (known.add(event.id)) list += event
            }
            rewriteLocked(list.sortedBy { it.shownAtMillis }.takeLast(maxEvents))
        }
    }

    private fun loadLocked(): MutableList<OfferEvent> {
        cache?.let { return it }
        val list = mutableListOf<OfferEvent>()
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

    private fun rewriteLocked(list: List<OfferEvent>) {
        cache = list.toMutableList()
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { writer ->
                list.forEach { writer.write(encode(it).toString()); writer.newLine() }
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun encode(e: OfferEvent): JSONObject = JSONObject()
        .put("id", e.id)
        .put("platform", e.platform.key)
        .put("shownAt", e.shownAtMillis)
        .put("decidedAt", e.decidedAtMillis ?: JSONObject.NULL)
        .put("decision", e.decision.key)
        .put("source", e.decisionSource.key)
        .put("amount", e.amountPln)
        .put("net", e.netPln)
        .put("km", e.distanceKm)
        .put("min", e.durationMinutes ?: JSONObject.NULL)
        .put("perHour", e.netPerHour ?: JSONObject.NULL)
        .put("perKm", e.netPerKm ?: JSONObject.NULL)
        .put("status", e.status.name)
        .put("restaurant", e.restaurant ?: JSONObject.NULL)
        .put("pickup", e.pickupAddress ?: JSONObject.NULL)
        .put("dropoff", e.dropoffAddress ?: JSONObject.NULL)
        .put("pickupMin", e.pickupMinuteOfDay ?: JSONObject.NULL)
        .put("deliveryMin", e.deliveryMinuteOfDay ?: JSONObject.NULL)
        .put("stops", e.stops)
        .put("duringJob", e.duringActiveJob)
        .put("blacklisted", e.blacklisted)

    private fun decode(o: JSONObject): OfferEvent? = runCatching {
        OfferEvent(
            id = o.getString("id"),
            platform = CourierPlatform.entries.firstOrNull { it.key == o.optString("platform") } ?: CourierPlatform.GLOBAL,
            shownAtMillis = o.getLong("shownAt"),
            decidedAtMillis = if (o.isNull("decidedAt")) null else o.getLong("decidedAt"),
            decision = OfferDecision.fromKey(o.optString("decision")),
            decisionSource = DecisionSource.fromKey(o.optString("source")),
            amountPln = o.optDouble("amount", 0.0),
            netPln = o.optDouble("net", 0.0),
            distanceKm = o.optDouble("km", 0.0),
            durationMinutes = if (o.isNull("min")) null else o.optInt("min"),
            netPerHour = if (o.isNull("perHour")) null else o.optDouble("perHour"),
            netPerKm = if (o.isNull("perKm")) null else o.optDouble("perKm"),
            status = runCatching { ProfitabilityStatus.valueOf(o.optString("status")) }.getOrDefault(ProfitabilityStatus.NO_TIME),
            restaurant = o.optString("restaurant").takeIf { !o.isNull("restaurant") && it.isNotBlank() },
            pickupAddress = o.optString("pickup").takeIf { !o.isNull("pickup") && it.isNotBlank() },
            dropoffAddress = o.optString("dropoff").takeIf { !o.isNull("dropoff") && it.isNotBlank() },
            pickupMinuteOfDay = if (o.isNull("pickupMin")) null else o.optInt("pickupMin"),
            deliveryMinuteOfDay = if (o.isNull("deliveryMin")) null else o.optInt("deliveryMin"),
            stops = o.optInt("stops", 1),
            duringActiveJob = o.optBoolean("duringJob", false),
            blacklisted = o.optBoolean("blacklisted", false)
        )
    }.getOrNull()

    companion object {
        const val FILE_NAME = "offer_events.jsonl"
    }
}
