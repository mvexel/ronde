package net.mvexel.intervalswatch.mobile.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.mvexel.intervalswatch.mobile.DataLayerWatchLink
import net.mvexel.intervalswatch.mobile.IntervalsApi
import net.mvexel.intervalswatch.mobile.data.plan.PlanSync
import net.mvexel.intervalswatch.mobile.data.plan.PlannedFetcher
import net.mvexel.intervalswatch.mobile.ScreenSettingsStore
import net.mvexel.intervalswatch.mobile.SettingsStore
import net.mvexel.intervalswatch.mobile.data.DataLayerBuzzer
import net.mvexel.intervalswatch.mobile.data.LibraryRecordingsSource
import net.mvexel.intervalswatch.mobile.data.RecordingsSource
import net.mvexel.intervalswatch.mobile.data.WatchBuzzer
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetailLoader
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRemoteSource
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRepository
import net.mvexel.intervalswatch.mobile.data.activity.IntervalsActivityDetailLoader
import net.mvexel.intervalswatch.mobile.data.activity.IntervalsActivitySource
import net.mvexel.intervalswatch.mobile.data.activity.RecordingLibraryFitSource
import net.mvexel.intervalswatch.mobile.data.activity.RoomActivityRepository
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsDao
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.data.session.AppSession
import net.mvexel.intervalswatch.mobile.data.session.Session

/** Process-wide singletons, built on first use (the activity, workers and services share them). */
class AppContainer private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: RondeDatabase by lazy { RondeDatabase.build(app) }
    val prefsDao: PlannedWorkoutPrefsDao get() = database.plannedWorkoutPrefs()
    private val settingsStore by lazy { SettingsStore(app) }
    private val activitySource: ActivityRemoteSource by lazy { IntervalsActivitySource(IntervalsApi()) { settingsStore.current() } }
    val activityRepository: ActivityRepository by lazy {
        RoomActivityRepository(database.activities(), database.streams(), activitySource, database.syncState())
    }
    /** Activity Detail data: Room cache, the kept FIT for Ronde runs, else intervals.icu. */
    val activityDetailLoader: ActivityDetailLoader by lazy {
        IntervalsActivityDetailLoader(database.activities(), database.streams(), database.details(), activitySource, RecordingLibraryFitSource(app))
    }
    /**
     * The one path for planned workouts to the watch (foreground sync, hide/unhide re-push, SyncWorker). Its mutex
     * is process-wide, so those never interleave.
     */
    val planSync: PlanSync by lazy {
        PlanSync(PlannedFetcher { IntervalsApi().plannedWorkouts(it) }, DataLayerWatchLink(app), prefsDao, database.plannedList())
    }
    val session: Session by lazy {
        AppSession(app, scope, planSync) {
            runCatching { activityRepository.clear() }
            runCatching { planSync.clear() }
        }
    }
    val recordings: RecordingsSource by lazy { LibraryRecordingsSource(app) }
    val screenSettings: ScreenSettingsStore by lazy { ScreenSettingsStore(app) }
    val buzzer: WatchBuzzer by lazy { DataLayerBuzzer(app) }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(context).also { instance = it } }
    }
}
