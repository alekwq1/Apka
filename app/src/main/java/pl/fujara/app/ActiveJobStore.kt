package pl.fujara.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trwaly zapis aktywnych zlecen (znaczniki czasu akceptacji / odbioru), zeby po
 * restarcie uslugi pomiar czasu realizacji byl kontynuowany, a nie zaczynany od nowa.
 */
class ActiveJobStore(context: Context) {
    private val prefs = context.getSharedPreferences("fujara_active_jobs", Context.MODE_PRIVATE)

    fun save(jobs: List<ActiveJob>) {
        val array = JSONArray()
        jobs.forEach { j ->
            array.put(
                JSONObject()
                    .put("platform", j.platform.key)
                    .put("acceptedAt", j.acceptedAtMillis)
                    .put("restaurant", j.restaurant ?: JSONObject.NULL)
                    .put("pickup", j.pickupAddress ?: JSONObject.NULL)
                    .put("dropoff", j.dropoffAddress ?: JSONObject.NULL)
                    .put("pickupMin", j.pickupMinuteOfDay ?: JSONObject.NULL)
                    .put("deliveryMin", j.deliveryMinuteOfDay ?: JSONObject.NULL)
                    .put("estMin", j.estimatedMinutes ?: JSONObject.NULL)
                    .put("km", j.distanceKm)
                    .put("amount", j.amountPln)
                    .put("stops", j.stops)
                    .put("stage", j.stage.name)
                    .put("lastSeen", j.lastSeenAtMillis)
                    .put("eta", j.etaMinutes ?: JSONObject.NULL)
                    .put("pickedUpAt", j.pickedUpAtMillis ?: JSONObject.NULL)
                    .put("confirmed", j.confirmed)
                    .put("extraPickups", JSONArray(j.extraPickups))
                    .put("extraDropoffs", JSONArray(j.extraDropoffs))
            )
        }
        prefs.edit().putString("jobs_v3", array.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun load(): List<ActiveJob> {
        val raw = prefs.getString("jobs_v3", null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ActiveJob>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            runCatching {
                ActiveJob(
                    platform = CourierPlatform.entries.firstOrNull { it.key == o.optString("platform") } ?: return@runCatching,
                    acceptedAtMillis = o.getLong("acceptedAt"),
                    restaurant = o.optString("restaurant").takeIf { !o.isNull("restaurant") },
                    pickupAddress = o.optString("pickup").takeIf { !o.isNull("pickup") },
                    dropoffAddress = o.optString("dropoff").takeIf { !o.isNull("dropoff") },
                    pickupMinuteOfDay = if (o.isNull("pickupMin")) null else o.optInt("pickupMin"),
                    deliveryMinuteOfDay = if (o.isNull("deliveryMin")) null else o.optInt("deliveryMin"),
                    estimatedMinutes = if (o.isNull("estMin")) null else o.optInt("estMin"),
                    distanceKm = o.optDouble("km", 0.0),
                    amountPln = o.optDouble("amount", 0.0),
                    stops = o.optInt("stops", 1),
                    stage = runCatching { ActiveJob.Stage.valueOf(o.optString("stage")) }.getOrDefault(ActiveJob.Stage.TO_PICKUP),
                    lastSeenAtMillis = o.optLong("lastSeen", o.getLong("acceptedAt")),
                    etaMinutes = if (o.isNull("eta")) null else o.optInt("eta"),
                    pickedUpAtMillis = if (o.isNull("pickedUpAt")) null else o.optLong("pickedUpAt"),
                    confirmed = o.optBoolean("confirmed", false),
                    extraPickups = o.optJSONArray("extraPickups")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    extraDropoffs = o.optJSONArray("extraDropoffs")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                ).let(out::add)
            }
        }
        return out
    }
}
