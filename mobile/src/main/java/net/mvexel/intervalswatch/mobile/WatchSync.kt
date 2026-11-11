package net.mvexel.intervalswatch.mobile

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import net.mvexel.intervalswatch.mobile.app.AppContainer
import net.mvexel.intervalswatch.mobile.data.plan.WatchLink
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.SettingsSync
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

    /** Tells the watch whether the phone can sync. Carries no credential. */
    suspend fun pushLoggedIn(context: Context, loggedIn: Boolean) {
        val request = PutDataMapRequest.create(Sync.AUTH_PATH).apply {
            dataMap.putBoolean(Sync.KEY_LOGGED_IN, loggedIn)
            dataMap.putLong(Sync.KEY_SYNCED_AT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
    }

    /** Asks the watch to play the sample buzz at [strength] (settings.md §4 row 6). */
    suspend fun testBuzz(context: Context, strength: BuzzStrength) {
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrDefault(emptyList())
        nodes.forEach { n ->
            runCatching { Wearable.getMessageClient(context).sendMessage(n.id, SettingsSync.TEST_BUZZ_PATH, SettingsSync.encodeTestBuzz(strength)).await() }
        }
    }

    suspend fun connectedWatches(context: Context): List<String> =
        runCatching {
            Wearable.getNodeClient(context).connectedNodes.await().map { it.displayName }
        }.getOrDefault(emptyList())

    /**
     * Fetch from intervals.icu, store, and push to the watch, leaving out what is hidden on the Plan tab (read right
     * before the push). Returns everything fetched, hidden ones included. Goes through the process-wide
     * [net.mvexel.intervalswatch.mobile.app.AppContainer.planSync], so it is serialised with every other push.
     */
    suspend fun syncNow(context: Context, settings: Settings): List<Workout> =
        AppContainer.from(context).planSync.sync(settings).workouts

    /** The hidden set is now read inside the sync, right before the push; [hiddenIds] is ignored. */
    @Deprecated("hiddenIds read before the fetch goes stale; use syncNow(context, settings)", ReplaceWith("syncNow(context, settings)"))
    @Suppress("UNUSED_PARAMETER")
    suspend fun syncNow(context: Context, settings: Settings, hiddenIds: Collection<Long>): List<Workout> = syncNow(context, settings)
}

/** [WatchLink] over the Wear Data Layer. */
class DataLayerWatchLink(context: Context) : WatchLink {
    private val app = context.applicationContext
    override suspend fun push(workouts: List<Workout>) = WatchSync.push(app, workouts)
    override suspend fun pushLoggedIn(loggedIn: Boolean) = WatchSync.pushLoggedIn(app, loggedIn)
}
