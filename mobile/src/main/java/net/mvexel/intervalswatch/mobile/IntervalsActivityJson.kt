package net.mvexel.intervalswatch.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One intervals.icu `Activity` (list row or single GET), only the fields Ronde uses. Numbers are read leniently:
 * the spec says int32 for some fields, but a float there must not break the History tab.
 */
data class IcuActivity(
    val id: String,
    val name: String?,
    val type: String?,
    val startDateLocal: String?,
    /** m. */
    val distance: Double?,
    val movingTime: Int?,
    val elapsedTime: Int?,
    /** m/s. */
    val averageSpeed: Double?,
    val averageHeartrate: Double?,
    val maxHeartrate: Double?,
    val averageWatts: Double?,
    val trainingLoad: Int?,
    val externalId: String?,
    val source: String?,
    val oauthClientId: String?,
    val deviceName: String?,
    val pairedEventId: Long?,
    val streamTypes: List<String>?,
    /** When intervals.icu last analysed it; changes when the activity is re-processed or edited. */
    val analyzed: String?,
    val trainer: Boolean?,
    /** Strava stubs carry an explanation here. */
    val note: String?,
    /** Only with `intervals=true` on the single-activity GET. */
    val intervals: List<IcuInterval>? = null,
) {
    /** "An empty stub object is returned for Strava activities" (API spec): no streams, intervals or summary. */
    val isStravaStub: Boolean
        get() = source.equals("STRAVA", ignoreCase = true) || note?.contains("strava", ignoreCase = true) == true

    /** Ronde uploads carry `external_id=intervalswatch-<epoch>`; OAuth uploads also carry Ronde's client id. */
    fun isRonde(rondeClientId: String?): Boolean =
        externalId?.startsWith("intervalswatch-") == true ||
            (rondeClientId != null && oauthClientId != null && oauthClientId == rondeClientId)
}

/** One `Interval` of `icu_intervals`. Times are seconds from the activity start; indexes point into the streams. */
data class IcuInterval(
    val type: String?,
    val label: String?,
    val startIndex: Int?,
    val endIndex: Int?,
    val startTime: Double?,
    val endTime: Double?,
)

/** One `ActivityStream`. Null samples become NaN. `latlng` keeps latitude in [data] and longitude in [data2]. */
class IcuStream(val type: String, val data: DoubleArray, val data2: DoubleArray?)

object IntervalsActivityJson {
    /** The `fields=` set for History rows: everything [IcuActivity] reads except intervals. */
    val LIST_FIELDS = listOf(
        "id", "name", "type", "start_date_local", "distance", "moving_time", "elapsed_time", "average_speed",
        "average_heartrate", "max_heartrate", "icu_average_watts", "icu_training_load", "external_id", "source",
        "oauth_client_id", "device_name", "paired_event_id", "stream_types", "analyzed", "trainer", "_note",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun activities(body: String): List<IcuActivity> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { (it as? JsonObject)?.let(::activity) }

    fun activity(body: String): IcuActivity =
        activity(json.parseToJsonElement(body).jsonObject) ?: throw IllegalArgumentException("activity without id")

    private fun activity(o: JsonObject): IcuActivity? {
        val id = o.str("id") ?: return null
        return IcuActivity(
            id = id,
            name = o.str("name"),
            type = o.str("type"),
            startDateLocal = o.str("start_date_local"),
            distance = o.num("distance"),
            movingTime = o.num("moving_time")?.toInt(),
            elapsedTime = o.num("elapsed_time")?.toInt(),
            averageSpeed = o.num("average_speed"),
            averageHeartrate = o.num("average_heartrate"),
            maxHeartrate = o.num("max_heartrate"),
            averageWatts = o.num("icu_average_watts") ?: o.num("average_watts"),
            trainingLoad = o.num("icu_training_load")?.toInt(),
            externalId = o.str("external_id"),
            source = o.str("source"),
            oauthClientId = o.str("oauth_client_id"),
            deviceName = o.str("device_name"),
            pairedEventId = o.num("paired_event_id")?.toLong(),
            streamTypes = (o["stream_types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            analyzed = o.str("analyzed"),
            trainer = (o["trainer"] as? JsonPrimitive)?.booleanOrNull,
            note = o.str("_note"),
            intervals = (o["icu_intervals"] as? JsonArray)?.let(::intervals),
        )
    }

    /** `GET /activity/{id}/intervals` returns `{icu_intervals: [...]}`; a bare array is accepted too. */
    fun intervals(body: String): List<IcuInterval> = when (val e = json.parseToJsonElement(body)) {
        is JsonArray -> intervals(e)
        is JsonObject -> (e["icu_intervals"] as? JsonArray)?.let(::intervals).orEmpty()
        else -> emptyList()
    }

    private fun intervals(a: JsonArray): List<IcuInterval> = a.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        IcuInterval(
            type = o.str("type"),
            label = o.str("label"),
            startIndex = o.num("start_index")?.toInt(),
            endIndex = o.num("end_index")?.toInt(),
            startTime = o.num("start_time"),
            endTime = o.num("end_time"),
        )
    }

    fun streams(body: String): List<IcuStream> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val type = o.str("type") ?: return@mapNotNull null
            val data = (o["data"] as? JsonArray) ?: return@mapNotNull null
            IcuStream(type, doubles(data), (o["data2"] as? JsonArray)?.let(::doubles))
        }

    /** `GET /activity/{id}/map`: `{latlngs: [[lat, lng], ...]}`; null entries are skipped. */
    fun mapLatLngs(body: String): List<Pair<Double, Double>> {
        val o = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return (o["latlngs"] as? JsonArray).orEmpty().mapNotNull { p ->
            val a = p as? JsonArray ?: return@mapNotNull null
            val lat = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lng = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            lat to lng
        }
    }

    /** Numbers as is, booleans (the `moving` stream) as 1/0, anything else NaN. */
    private fun doubles(a: JsonArray) = DoubleArray(a.size) { i ->
        (a[i] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.booleanOrNull?.let { if (it) 1.0 else 0.0 } } ?: Double.NaN
    }

    private fun JsonObject.str(k: String): String? = when (val v = this[k]) {
        null, JsonNull -> null
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }

    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.doubleOrNull }
}
