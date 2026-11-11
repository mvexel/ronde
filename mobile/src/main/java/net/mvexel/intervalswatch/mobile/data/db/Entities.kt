package net.mvexel.intervalswatch.mobile.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A completed activity from intervals.icu (id like "i12345678"). Fields mirror the API's activity summary. */
@Entity(tableName = "activities", indices = [Index("start_local")])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** intervals.icu activity type, e.g. "Run", "Ride", "VirtualRide". */
    val type: String,
    /** Local start date-time as intervals.icu gives it, e.g. 2026-10-30T07:00:00. */
    @ColumnInfo(name = "start_local") val startLocal: String,
    @ColumnInfo(name = "distance_m") val distanceM: Double? = null,
    @ColumnInfo(name = "moving_time_sec") val movingTimeSec: Int? = null,
    @ColumnInfo(name = "elapsed_time_sec") val elapsedTimeSec: Int? = null,
    /** m/s. */
    @ColumnInfo(name = "avg_speed") val avgSpeed: Double? = null,
    @ColumnInfo(name = "avg_hr") val avgHr: Int? = null,
    @ColumnInfo(name = "avg_power") val avgPower: Int? = null,
    /** Training load. */
    val load: Int? = null,
    @ColumnInfo(name = "external_id") val externalId: String? = null,
    @ColumnInfo(name = "paired_event_id") val pairedEventId: Long? = null,
    /** Recorded with Ronde on the watch (see [isRondeExternalId]). */
    @ColumnInfo(name = "is_ronde") val isRonde: Boolean = false,
    /** Epoch millis of the fetch that wrote this row. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/** Ronde uploads carry `external_id=intervalswatch-<epoch>` (RecordingProtocol.externalId). */
fun isRondeExternalId(externalId: String?): Boolean = externalId?.startsWith("intervalswatch-") == true

/**
 * Sample streams of one activity, each a [StreamCodec] blob of doubles (null = stream absent).
 * [latlng] is interleaved: lat0, lng0, lat1, lng1, ...
 */
@Entity(
    tableName = "activity_streams",
    foreignKeys = [
        ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activity_id"], onDelete = ForeignKey.CASCADE),
    ],
)
class ActivityStreamsEntity(
    @PrimaryKey @ColumnInfo(name = "activity_id") val activityId: String,
    /** Seconds from start. */
    val time: ByteArray? = null,
    /** m/s. */
    val velocity: ByteArray? = null,
    val heartrate: ByteArray? = null,
    val watts: ByteArray? = null,
    /** Metres from start. */
    val distance: ByteArray? = null,
    val latlng: ByteArray? = null,
)

/** Decoded view of [ActivityStreamsEntity]. */
class ActivityStreams(
    val activityId: String,
    val time: DoubleArray? = null,
    val velocity: DoubleArray? = null,
    val heartrate: DoubleArray? = null,
    val watts: DoubleArray? = null,
    val distance: DoubleArray? = null,
    val latlng: DoubleArray? = null,
) {
    val samples: Int get() = time?.size ?: 0
    val hasMap: Boolean get() = (latlng?.size ?: 0) >= 2
}

fun ActivityStreamsEntity.decode() = ActivityStreams(
    activityId,
    time?.let(StreamCodec::decode), velocity?.let(StreamCodec::decode), heartrate?.let(StreamCodec::decode),
    watts?.let(StreamCodec::decode), distance?.let(StreamCodec::decode), latlng?.let(StreamCodec::decode),
)

fun ActivityStreams.encode() = ActivityStreamsEntity(
    activityId,
    time?.let(StreamCodec::encode), velocity?.let(StreamCodec::encode), heartrate?.let(StreamCodec::encode),
    watts?.let(StreamCodec::encode), distance?.let(StreamCodec::encode), latlng?.let(StreamCodec::encode),
)

/** The user's per-workout choices on the Plan tab, keyed by intervals.icu event id. */
@Entity(tableName = "planned_workout_prefs")
data class PlannedWorkoutPrefsEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: Long,
    val pinned: Boolean = false,
    /** Hidden workouts stay on the Plan tab (under "Hidden") but are not sent to the watch. */
    val hidden: Boolean = false,
    /** Order among pinned workouts, ascending. */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
