package net.mvexel.intervalswatch.mobile.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.mvexel.intervalswatch.mobile.ScreenSettingsStore
import net.mvexel.intervalswatch.mobile.data.DataLayerBuzzer
import net.mvexel.intervalswatch.mobile.data.LibraryRecordingsSource
import net.mvexel.intervalswatch.mobile.data.RecordingsSource
import net.mvexel.intervalswatch.mobile.data.WatchBuzzer
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRepository
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
    val activityRepository: ActivityRepository by lazy { RoomActivityRepository(database.activities(), database.streams()) }
    val session: Session by lazy { AppSession(app, scope, prefsDao) }
    val recordings: RecordingsSource by lazy { LibraryRecordingsSource(app) }
    val screenSettings: ScreenSettingsStore by lazy { ScreenSettingsStore(app) }
    val buzzer: WatchBuzzer by lazy { DataLayerBuzzer(app) }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(context).also { instance = it } }
    }
}
