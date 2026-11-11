package net.mvexel.intervalswatch.mobile.feature.history.detail

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetail
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetailLoader
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRepository
import net.mvexel.intervalswatch.mobile.data.activity.DetailLoad
import net.mvexel.intervalswatch.mobile.data.activity.LatLon
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams as StoredStreams
import net.mvexel.intervalswatch.shared.ActivityStreams
import net.mvexel.intervalswatch.shared.Sport

/**
 * TODO(detail-loader): temporary. The real [ActivityDetailLoader] (cache + intervals.icu detail, streams, map,
 *  paired event's steps, device name, Strava stub flag) comes from the sync work as `AppContainer.activityDetailLoader`;
 *  the lead wires it in RondeApp at merge and deletes this class.
 *
 * Until then this reads what Room already holds: the activity summary and its streams. No planned steps, so the page
 * shows the unstructured layout.
 */
class RepositoryDetailLoader(private val repo: ActivityRepository) : ActivityDetailLoader {
    override fun load(id: String): Flow<DetailLoad> =
        combine(repo.activity(id), repo.streams(id)) { a, s ->
            if (a == null) DetailLoad.Failed("This activity is not on the phone.", null) else DetailLoad.Ready(a.toDetail(s))
        }.onStart { emit(DetailLoad.Loading) }

    internal companion object {
        fun ActivityEntity.toDetail(s: StoredStreams?): ActivityDetail = ActivityDetail(
            id = id,
            name = name,
            sport = sportOf(type),
            startLocal = startLocal,
            distanceM = distanceM,
            movingTimeSec = movingTimeSec,
            elapsedTimeSec = elapsedTimeSec,
            avgHr = avgHr?.toDouble(),
            maxHr = s?.heartrate?.filter { !it.isNaN() }?.maxOrNull(),
            avgPower = avgPower?.toDouble(),
            load = load,
            isRonde = isRonde,
            deviceName = null,
            detailsAvailable = true,
            streams = s?.time?.takeIf { it.size >= 2 }?.let { t ->
                fun ok(a: DoubleArray?) = a?.takeIf { it.size == t.size }
                ActivityStreams(t, ok(s.velocity), ok(s.heartrate), ok(s.watts), distanceM = ok(s.distance))
            },
            route = s?.latlng?.takeIf { it.size >= 4 }?.let { ll -> (0 until ll.size / 2).map { LatLon(ll[2 * it], ll[2 * it + 1]) } },
            plannedSteps = null,
            boundaries = null,
        )

        fun sportOf(type: String): Sport? = when {
            type.contains("Ride", ignoreCase = true) -> Sport.RIDE
            type.contains("Run", ignoreCase = true) -> Sport.RUN
            type.contains("Walk", ignoreCase = true) || type.contains("Hike", ignoreCase = true) -> Sport.WALK
            type.isBlank() -> null
            else -> Sport.OTHER
        }
    }
}
