package net.mvexel.intervalswatch.mobile

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import net.mvexel.intervalswatch.shared.Sync
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutBundle

/**
 * Puts the full list of upcoming workouts into one Data Layer item. The item persists and is
 * delivered to the watch whenever it reconnects, so the phone app doesn't need to be running.
 */
object WatchSync {

    suspend fun push(context: Context, workouts: List<Workout>) {
        val bundle = WorkoutBundle(System.currentTimeMillis(), workouts)
        val request = PutDataMapRequest.create(Sync.DATA_PATH).apply {
            dataMap.putString(Sync.KEY_JSON, Sync.encode(bundle))
            dataMap.putLong(Sync.KEY_SYNCED_AT, bundle.syncedAtMillis)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    suspend fun connectedWatches(context: Context): List<String> =
        runCatching {
            Wearable.getNodeClient(context).connectedNodes.await().map { it.displayName }
        }.getOrDefault(emptyList())

    /** Fetch from intervals.icu and push to the watch. Returns what was sent. */
    suspend fun syncNow(context: Context, settings: Settings): List<Workout> {
        val workouts = IntervalsApi().plannedWorkouts(settings)
        push(context, workouts)
        return workouts
    }
}
