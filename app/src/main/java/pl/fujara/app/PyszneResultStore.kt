package pl.fujara.app

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * Trwaly zapis wyniku dnia po kliknieciu "Potwierdz i policz".
 * Snapshot pozwala pokazac wynik ponownie bez ponownego zatwierdzania oraz
 * zbudowac analize tygodnia. Gdy zmieniaja sie zlecenia lub ustawienia,
 * podpis przestaje pasowac i ekran prosi o ponowne przeliczenie.
 */
data class PyszneDayResultSnapshot(
    val date: LocalDate,
    val sourceSignature: String,
    val settingsSignature: String,
    val cashTipsPln: Double,
    val extraPauseMinutes: Int,
    val orderCount: Int,
    val grossPln: Double,
    val distanceKm: Double,
    val durationSeconds: Int,
    val netPln: Double,
    val netPerHour: Double?,
    val netPerKm: Double?,
    val goodOrders: Int,
    val borderlineOrders: Int,
    val poorOrders: Int,
    val cancelledOrders: Int,
    val confirmedAtMillis: Long = System.currentTimeMillis()
)

class PyszneResultStore(context: Context) {
    private val prefs = context.getSharedPreferences("pyszne_confirmed_results", Context.MODE_PRIVATE)

    fun get(date: LocalDate): PyszneDayResultSnapshot? {
        val raw = prefs.getString(date.toString(), null) ?: return null
        return decode(raw)
    }

    fun all(): List<PyszneDayResultSnapshot> = prefs.all.values
        .mapNotNull { it as? String }
        .mapNotNull(::decode)
        .sortedByDescending { it.date }

    fun save(snapshot: PyszneDayResultSnapshot) {
        prefs.edit().putString(snapshot.date.toString(), encode(snapshot)).apply()
    }

    fun delete(date: LocalDate) {
        prefs.edit().remove(date.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(item: PyszneDayResultSnapshot): String = JSONObject()
        .put("date", item.date.toString())
        .put("source", item.sourceSignature)
        .put("settings", item.settingsSignature)
        .put("cashTips", item.cashTipsPln)
        .put("extraPause", item.extraPauseMinutes)
        .put("orders", item.orderCount)
        .put("gross", item.grossPln)
        .put("distance", item.distanceKm)
        .put("duration", item.durationSeconds)
        .put("net", item.netPln)
        .put("perHour", item.netPerHour ?: JSONObject.NULL)
        .put("perKm", item.netPerKm ?: JSONObject.NULL)
        .put("good", item.goodOrders)
        .put("borderline", item.borderlineOrders)
        .put("poor", item.poorOrders)
        .put("cancelled", item.cancelledOrders)
        .put("confirmedAt", item.confirmedAtMillis)
        .toString()

    private fun decode(raw: String): PyszneDayResultSnapshot? = runCatching {
        val obj = JSONObject(raw)
        PyszneDayResultSnapshot(
            date = LocalDate.parse(obj.getString("date")),
            sourceSignature = obj.getString("source"),
            settingsSignature = obj.getString("settings"),
            cashTipsPln = obj.optDouble("cashTips", 0.0),
            extraPauseMinutes = obj.optInt("extraPause", 0).coerceIn(0, 240),
            orderCount = obj.getInt("orders"),
            grossPln = obj.getDouble("gross"),
            distanceKm = obj.getDouble("distance"),
            durationSeconds = obj.getInt("duration"),
            netPln = obj.getDouble("net"),
            netPerHour = if (obj.isNull("perHour")) null else obj.getDouble("perHour"),
            netPerKm = if (obj.isNull("perKm")) null else obj.getDouble("perKm"),
            goodOrders = obj.optInt("good", 0),
            borderlineOrders = obj.optInt("borderline", 0),
            poorOrders = obj.optInt("poor", 0),
            cancelledOrders = obj.optInt("cancelled", 0),
            confirmedAtMillis = obj.optLong("confirmedAt", 0L)
        )
    }.getOrNull()
}

object PyszneResultSignature {
    fun source(entries: List<PyszneDeliveryLog>, reference: PyszneDayReference?): String {
        val payload = buildString {
            entries.sortedWith(compareBy<PyszneDeliveryLog> { it.date }.thenBy { it.orderId ?: it.key }).forEach { entry ->
                append(entry.key).append('|')
                append(entry.orderId.orEmpty().uppercase(Locale.ROOT)).append('|')
                append(entry.date).append('|')
                append(entry.amountPln).append('|')
                append(entry.distanceKm).append('|')
                append(entry.durationSeconds).append('|')
                append(entry.cancelled).append(';')
            }
            append("ref|")
            if (reference != null) {
                append(reference.date).append('|')
                append(reference.orderCount).append('|')
                append(reference.amountPln).append('|')
                append(reference.orderIds.sorted().joinToString(","))
            }
        }
        return sha256(payload)
    }

    fun settings(
        rules: ProfitabilityCalculator.Rules,
        decisionBasis: DecisionBasis,
        zusPercent: Double
    ): String = sha256(
        listOf(
            rules.vehicleCostPerKm,
            rules.minimumNetPerKm,
            rules.toleranceNetPerKm,
            rules.minimumNetPerHour,
            rules.toleranceNetPerHour,
            rules.extraTimeMinutes,
            decisionBasis.key,
            zusPercent
        ).joinToString("|")
    )

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

fun PyszneDaySummary.toSnapshot(
    sourceSignature: String,
    settingsSignature: String
): PyszneDayResultSnapshot = PyszneDayResultSnapshot(
    date = date,
    sourceSignature = sourceSignature,
    settingsSignature = settingsSignature,
    cashTipsPln = cashTipsPln,
    extraPauseMinutes = extraPauseMinutes,
    orderCount = orderCount,
    grossPln = grossPln,
    distanceKm = distanceKm,
    durationSeconds = durationSeconds,
    netPln = netPln,
    netPerHour = netPerHour,
    netPerKm = netPerKm,
    goodOrders = goodOrders,
    borderlineOrders = borderlineOrders,
    poorOrders = poorOrders,
    cancelledOrders = cancelledOrders
)
