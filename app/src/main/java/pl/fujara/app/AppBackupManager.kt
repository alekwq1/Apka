package pl.fujara.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Lokalny backup FUJARY. Dane nigdy nie sa wysylane przez ten mechanizm poza urzadzenie.
 * Eksport przez SAF zapisuje tylko plik wybrany przez uzytkownika.
 */
class AppBackupManager(private val context: Context) {
    private val preferenceNames = listOf(
        "delivery_assistant",
        "pyszne_delivery_history",
        "pyszne_confirmed_results"
    )

    private val backupDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    fun exportJson(): String {
        val root = JSONObject()
            .put("format", "fujara-backup")
            .put("formatVersion", 1)
            .put("createdAtMillis", System.currentTimeMillis())

        val stores = JSONObject()
        preferenceNames.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            stores.put(name, encodePreferences(prefs))
        }
        root.put("preferences", stores)
        return root.toString(2)
    }

    fun importJson(raw: String) {
        val root = JSONObject(raw)
        require(root.optString("format") == "fujara-backup") { "Nieprawidlowy format backupu" }
        val stores = root.getJSONObject("preferences")
        preferenceNames.forEach { name ->
            val encoded = stores.optJSONObject(name) ?: return@forEach
            restorePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), encoded)
        }
    }

    /** Jeden automatyczny snapshot na dzien. Trzymamy maksymalnie 14 ostatnich. */
    fun ensureDailyBackup(now: LocalDate = LocalDate.now()): File? {
        val file = File(backupDir, "fujara-auto-$now.json")
        if (file.exists()) return null
        file.writeText(exportJson())
        backupDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("fujara-auto-") && it.extension == "json" }
            .sortedByDescending { it.name }
            .drop(14)
            .forEach { runCatching { it.delete() } }
        return file
    }

    fun latestAutoBackup(): File? = backupDir.listFiles()
        .orEmpty()
        .filter { it.name.startsWith("fujara-auto-") && it.extension == "json" }
        .maxByOrNull { it.name }

    fun restoreLatestAutoBackup(): Boolean {
        val file = latestAutoBackup() ?: return false
        importJson(file.readText())
        return true
    }

    fun latestAutoBackupDate(): String? = latestAutoBackup()
        ?.name
        ?.removePrefix("fujara-auto-")
        ?.removeSuffix(".json")

    fun clearAutoBackups() {
        backupDir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
    }

    private fun encodePreferences(prefs: SharedPreferences): JSONObject {
        val obj = JSONObject()
        prefs.all.forEach { entry ->
            val key = entry.key
            val value = entry.value
            val encoded = when (value) {
                is String -> JSONObject().put("type", "string").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Set<*> -> JSONObject().put("type", "stringSet").put(
                    "value",
                    JSONArray().apply { value.filterIsInstance<String>().forEach { put(it) } }
                )
                else -> null
            }
            if (encoded != null) obj.put(key, encoded)
        }
        return obj
    }

    private fun restorePreferences(prefs: SharedPreferences, obj: JSONObject) {
        val editor = prefs.edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val encoded = obj.optJSONObject(key) ?: continue
            when (encoded.optString("type")) {
                "string" -> editor.putString(key, encoded.optString("value", ""))
                "int" -> editor.putInt(key, encoded.optInt("value"))
                "long" -> editor.putLong(key, encoded.optLong("value"))
                "float" -> editor.putFloat(key, encoded.optDouble("value").toFloat())
                "boolean" -> editor.putBoolean(key, encoded.optBoolean("value"))
                "stringSet" -> {
                    val array = encoded.optJSONArray("value") ?: JSONArray()
                    val set = buildSet {
                        for (i in 0 until array.length()) add(array.optString(i))
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }
}
