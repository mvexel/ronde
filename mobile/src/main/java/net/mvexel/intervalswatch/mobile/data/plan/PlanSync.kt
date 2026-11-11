package net.mvexel.intervalswatch.mobile.data.plan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mvexel.intervalswatch.mobile.AuthExpiredException
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.db.PlannedListDao
import net.mvexel.intervalswatch.mobile.data.db.PlannedListEntity
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsDao
import net.mvexel.intervalswatch.shared.Sync
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutBundle
import java.time.Clock
import java.time.LocalDate

/** The watch end of plan sync: the Wear Data Layer in the app (WatchSync), a fake in tests. */
interface WatchLink {
    /** Replaces the watch's workout list. */
    suspend fun push(workouts: List<Workout>)

    /** Tells the watch whether the phone can sync. Best effort: must not throw. */
    suspend fun pushLoggedIn(loggedIn: Boolean)
}

/** One intervals.icu call for the planned workouts (IntervalsApi.plannedWorkouts in the app). */
fun interface PlannedFetcher {
    suspend fun fetch(settings: Settings): List<Workout>
}

/** The persisted list of the last fetch. */
data class PlannedList(val workouts: List<Workout>, val fetchedAtMillis: Long)

data class PlanSyncResult(
    /** Everything fetched, hidden ones included. */
    val workouts: List<Workout>,
    /** How many went to the watch. */
    val sent: Int,
)

/**
 * Planned workouts from intervals.icu to Room to the watch. One instance per process (AppContainer.planSync): the
 * foreground sync, the hide/unhide re-push and the background worker all go through it, serialised by [lock], so
 * a push never interleaves with another and none uses a hidden set read before a concurrent hide.
 *
 * - Every fetch is stored ([PlannedListDao]) before it is pushed, so a re-push after hide/unhide sends the newest
 *   list, whoever fetched it.
 * - The hidden set is read right before each push, never before the fetch.
 * - Only today onward goes to the watch, so a list stored days ago does not resend past workouts.
 */
class PlanSync(
    private val fetcher: PlannedFetcher,
    private val watch: WatchLink,
    private val prefs: PlannedWorkoutPrefsDao,
    private val store: PlannedListDao,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val lock: Mutex = Mutex(),
) {
    /** The stored list, null until the first fetch (and after logout). Not filtered: see [upcoming]. */
    fun observe(): Flow<PlannedList?> = store.observe().map { it?.decode() }

    /** Fetches, stores, and sends the not hidden, upcoming workouts to the watch. */
    suspend fun sync(settings: Settings): PlanSyncResult = lock.withLock {
        val workouts = try {
            fetcher.fetch(settings)
        } catch (e: AuthExpiredException) {
            watch.pushLoggedIn(false)
            throw e
        }
        val now = clock.millis()
        store.put(PlannedListEntity(json = Sync.encode(WorkoutBundle(now, workouts)), fetchedAt = now))
        watch.pushLoggedIn(true)
        PlanSyncResult(workouts, pushLocked(workouts))
    }

    /**
     * Re-sends the stored list with the current hidden set (after a hide or unhide), without fetching.
     * Returns how many were sent, or null when nothing was ever fetched (pushing then would wipe the watch's list).
     */
    suspend fun pushStored(): Int? = lock.withLock {
        val list = store.get()?.decode() ?: return@withLock null
        pushLocked(list.workouts)
    }

    /** Forgets the stored list (logout: another account may log in next). */
    suspend fun clear() = lock.withLock { store.clear() }

    /** [workouts] from today on, by their local start date. */
    fun upcoming(workouts: List<Workout>): List<Workout> = upcoming(workouts, LocalDate.now(clock))

    private suspend fun pushLocked(workouts: List<Workout>): Int {
        val ws = visibleForWatch(upcoming(workouts), prefs.hiddenIds())
        watch.push(ws)
        return ws.size
    }
}

/** [workouts] starting on or after [today]; one whose date cannot be read is kept. */
fun upcoming(workouts: List<Workout>, today: LocalDate): List<Workout> {
    val from = today.toString()
    return workouts.filter { w -> w.startLocal.length < 10 || w.startLocal.substring(0, 10) >= from }
}

private fun PlannedListEntity.decode(): PlannedList? =
    runCatching { Sync.decode(json) }.getOrNull()?.let { PlannedList(it.workouts, fetchedAt) }
