package pl.fujara.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Start / Pauza / Stop zmiany – trwale w filesDir + SharedPreferences (stan). */
class WorkSessionStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val prefs = context.getSharedPreferences("fujara_work_session", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun load(): WorkShift {
        val segments = mutableListOf<WorkSegment>()
        if (file.exists()) runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { JSONObject(line) }.getOrNull()?.let { o ->
                    segments += WorkSegment(o.getLong("start"), if (o.isNull("end")) null else o.getLong("end"))
                }
            }
        }
        val state = runCatching { WorkState.valueOf(prefs.getString("state", WorkState.STOPPED.name) ?: "STOPPED") }.getOrDefault(WorkState.STOPPED)
        // Otwarty odcinek bez stanu RUNNING = niespojnosc (np. restart) – domykamy po 12 h.
        val fixed = segments.map { seg ->
            if (seg.endMillis == null && state != WorkState.RUNNING) seg.copy(endMillis = seg.startMillis + 12 * 3_600_000L) else seg
        }
        return WorkShift(fixed, state)
    }

    private fun save(shift: WorkShift) {
        runCatching {
            file.bufferedWriter().use { w ->
                shift.all.forEach { seg ->
                    w.write(JSONObject().put("start", seg.startMillis).put("end", seg.endMillis ?: JSONObject.NULL).toString())
                    w.newLine()
                }
            }
        }
        prefs.edit().putString("state", shift.state.name).apply()
    }

    fun state(): WorkState = synchronized(lock) { load().state }
    fun segments(): List<WorkSegment> = synchronized(lock) { load().all }

    fun start(nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) { load().also { it.start(nowMillis); save(it) }.state }
    fun pause(nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) { load().also { it.pause(nowMillis); save(it) }.state }
    fun stop(nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) { load().also { it.stop(nowMillis); save(it) }.state }

    fun todayMinutes(nowMillis: Long = System.currentTimeMillis()): Int = synchronized(lock) { load().todayMinutes(nowMillis) }
    fun minutesForDate(date: LocalDate, nowMillis: Long = System.currentTimeMillis()): Int = synchronized(lock) { load().minutesForDate(date, nowMillis) }

    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
            prefs.edit().clear().apply()
        }
    }

    fun exportJsonArray(): JSONArray = JSONArray().apply {
        segments().forEach { put(JSONObject().put("start", it.startMillis).put("end", it.endMillis ?: JSONObject.NULL)) }
    }

    fun importJsonArray(array: JSONArray) {
        synchronized(lock) {
            val existing = load()
            val known = existing.all.map { it.startMillis }.toMutableSet()
            val merged = existing.all.toMutableList()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val seg = runCatching { WorkSegment(o.getLong("start"), if (o.isNull("end")) null else o.getLong("end")) }.getOrNull() ?: continue
                if (known.add(seg.startMillis)) merged += seg
            }
            save(WorkShift(merged.sortedBy { it.startMillis }, existing.state))
        }
    }

    companion object {
        const val FILE_NAME = "work_segments.jsonl"
    }
}
