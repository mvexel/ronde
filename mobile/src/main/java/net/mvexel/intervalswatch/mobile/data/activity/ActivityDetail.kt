package net.mvexel.intervalswatch.mobile.data.activity

import net.mvexel.intervalswatch.shared.ActivityStreams
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.TimeSpan
import net.mvexel.intervalswatch.shared.WorkoutStep

/** Everything the Activity Detail screen needs for one activity. Null parts were not recorded or not available. */
data class ActivityDetail(
    val id: String,
    val name: String,
    val sport: Sport?,
    val startLocal: String,
    val distanceM: Double?,
    val movingTimeSec: Int?,
    val elapsedTimeSec: Int?,
    val avgHr: Double?,
    val maxHr: Double?,
    val avgPower: Double?,
    val load: Int?,
    val isRonde: Boolean,
    val deviceName: String?,
    val detailsAvailable: Boolean,
    val streams: ActivityStreams?,
    val route: List<LatLon>?,
    val plannedSteps: List<WorkoutStep>?,
    val boundaries: List<TimeSpan>?,
)

data class LatLon(val lat: Double, val lon: Double)

sealed interface DetailLoad {
    data object Loading : DetailLoad
    data class Ready(val detail: ActivityDetail) : DetailLoad
    data class Failed(val message: String, val cached: ActivityDetail?) : DetailLoad
}

interface ActivityDetailLoader {
    /** Cached first, then network if missing or stale. */
    fun load(id: String): kotlinx.coroutines.flow.Flow<DetailLoad>
}
