package net.mvexel.intervalswatch.mobile.data.activity

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.mvexel.intervalswatch.mobile.IcuInterval
import net.mvexel.intervalswatch.mobile.IcuStream
import net.mvexel.intervalswatch.mobile.data.db.ActivityDetailEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreamsEntity
import net.mvexel.intervalswatch.mobile.data.db.StreamCodec
import net.mvexel.intervalswatch.mobile.data.db.decode
import net.mvexel.intervalswatch.shared.IntervalsParser
import net.mvexel.intervalswatch.shared.Recording
import net.mvexel.intervalswatch.shared.TimeSpan
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.shared.ActivityStreams as SharedStreams
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams as DbStreams

/** Text forms for the list columns of [ActivityDetailEntity], and the mappings into [ActivityDetail]. */
internal object ActivityDetailCodec {
    /** Infinite target bounds (one-sided targets) must survive the round trip. */
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }
    private val stepsSerializer = ListSerializer(WorkoutStep.serializer())

    fun encodeSpans(spans: List<TimeSpan>?): String? = spans?.joinToString(";") { "${it.startSec},${it.endSec}" }

    fun decodeSpans(s: String?): List<TimeSpan>? = s?.let { text ->
        if (text.isEmpty()) emptyList()
        else text.split(';').mapNotNull { p ->
            val (a, b) = p.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            TimeSpan(a.toDoubleOrNull() ?: return@mapNotNull null, b.toDoubleOrNull() ?: return@mapNotNull null)
        }
    }

    fun encodeSteps(steps: List<WorkoutStep>?): String? = steps?.let { json.encodeToString(stepsSerializer, it) }

    fun decodeSteps(s: String?): List<WorkoutStep>? = s?.let { runCatching { json.decodeFromString(stepsSerializer, it) }.getOrNull() }
}

/** Streams intervals.icu is asked for. `velocity_smooth` is preferred; plain `velocity` is the fallback. */
internal val WANTED_STREAMS = listOf("time", "velocity_smooth", "velocity", "heartrate", "watts", "distance", "cadence", "latlng")

/** intervals.icu streams → the Room row. Streams whose length differs from `time` are dropped. */
internal fun icuStreamsToEntity(id: String, streams: List<IcuStream>, routeFallback: List<Pair<Double, Double>>?): ActivityStreamsEntity {
    val by = streams.associateBy { it.type }
    val time = by["time"]?.data
    fun col(type: String) = by[type]?.data?.takeIf { time != null && it.size == time.size && it.any { v -> !v.isNaN() } }
    val ll = by["latlng"]?.takeIf { it.data2 != null && it.data2.size == it.data.size }
    val latlng = when {
        ll != null -> DoubleArray(ll.data.size * 2) { i -> if (i % 2 == 0) ll.data[i / 2] else ll.data2!![i / 2] }
        !routeFallback.isNullOrEmpty() -> DoubleArray(routeFallback.size * 2) { i ->
            routeFallback[i / 2].let { if (i % 2 == 0) it.first else it.second }
        }
        else -> null
    }
    return ActivityStreamsEntity(
        activityId = id,
        time = time?.let(StreamCodec::encode),
        velocity = (col("velocity_smooth") ?: col("velocity"))?.let(StreamCodec::encode),
        heartrate = col("heartrate")?.let(StreamCodec::encode),
        watts = col("watts")?.let(StreamCodec::encode),
        distance = col("distance")?.let(StreamCodec::encode),
        latlng = latlng?.let(StreamCodec::encode),
        cadence = col("cadence")?.let(StreamCodec::encode),
    )
}

/** The watch's own recording → the Room row (route interleaved from the samples' positions). */
internal fun recordingToEntity(id: String, rec: Recording): ActivityStreamsEntity {
    val s = SharedStreams.from(rec)
    val pts = rec.samples.filter { it.lat != null && it.lon != null }
    val latlng = if (pts.isEmpty()) null else DoubleArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].lat!! else pts[i / 2].lon!! }
    return ActivityStreamsEntity(
        activityId = id,
        time = StreamCodec.encode(s.timeSec),
        velocity = s.velocityMps?.let(StreamCodec::encode),
        heartrate = s.heartrate?.let(StreamCodec::encode),
        watts = s.watts?.let(StreamCodec::encode),
        distance = s.distanceM?.let(StreamCodec::encode),
        latlng = latlng?.let(StreamCodec::encode),
        cadence = s.cadence?.let(StreamCodec::encode),
    )
}

/**
 * `icu_intervals` → spans in seconds from the start, in order. The time stream turns the stream indexes into seconds
 * when it has them; `start_time`/`end_time` are used otherwise.
 */
internal fun intervalsToSpans(intervals: List<IcuInterval>?, time: DoubleArray?): List<TimeSpan>? {
    if (intervals.isNullOrEmpty()) return null
    val spans = intervals.mapNotNull { iv ->
        val fromIdx = if (time != null && iv.startIndex != null && iv.endIndex != null &&
            iv.startIndex in time.indices && iv.endIndex in time.indices
        ) TimeSpan(time[iv.startIndex], time[iv.endIndex]) else null
        val span = fromIdx ?: run {
            val a = iv.startTime ?: return@mapNotNull null
            val b = iv.endTime ?: return@mapNotNull null
            TimeSpan(a, b)
        }
        span.takeIf { it.endSec > it.startSec }
    }.sortedBy { it.startSec }
    return spans.ifEmpty { null }
}

/** Shared streams from the Room row; null without a usable time stream. */
internal fun DbStreams.toShared(pauses: List<TimeSpan>): SharedStreams? {
    val t = time?.takeIf { it.size >= 2 } ?: return null
    fun ok(a: DoubleArray?) = a?.takeIf { it.size == t.size }
    return SharedStreams(
        timeSec = t,
        velocityMps = ok(velocity),
        heartrate = ok(heartrate),
        watts = ok(watts),
        cadence = ok(cadence),
        distanceM = ok(distance),
        pauses = pauses,
    )
}

internal fun DbStreams.route(): List<LatLon>? {
    val ll = latlng ?: return null
    val pts = (0 until ll.size / 2).mapNotNull { i ->
        val lat = ll[2 * i]; val lon = ll[2 * i + 1]
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) null else LatLon(lat, lon)
    }
    return pts.takeIf { it.size >= 2 }
}

/** [ActivityDetail] from what Room holds; streams and the rest are null when [detail] is. */
internal fun buildDetail(a: ActivityEntity, detail: ActivityDetailEntity?, streams: ActivityStreamsEntity?): ActivityDetail {
    val db = if (detail != null) streams?.decode() else null
    val pauses = ActivityDetailCodec.decodeSpans(detail?.pauses).orEmpty()
    return ActivityDetail(
        id = a.id,
        name = a.name,
        sport = a.type.takeIf { it.isNotBlank() }?.let(IntervalsParser::sportOf),
        startLocal = a.startLocal,
        distanceM = a.distanceM,
        movingTimeSec = a.movingTimeSec,
        elapsedTimeSec = a.elapsedTimeSec,
        avgHr = a.avgHr?.toDouble(),
        maxHr = a.maxHr?.toDouble(),
        avgPower = a.avgPower?.toDouble(),
        load = a.load,
        isRonde = a.isRonde,
        deviceName = a.deviceName,
        detailsAvailable = a.detailsAvailable,
        streams = db?.toShared(pauses),
        route = db?.route(),
        plannedSteps = ActivityDetailCodec.decodeSteps(detail?.plannedSteps)?.ifEmpty { null },
        boundaries = ActivityDetailCodec.decodeSpans(detail?.boundaries)?.ifEmpty { null },
    )
}
