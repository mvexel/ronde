package net.mvexel.intervalswatch.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.data.plan.PlanSync
import net.mvexel.intervalswatch.mobile.data.session.AppSession
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.Workout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The session's Plan state follows the stored list (findings 2 and 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSessionPlanTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RondeDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = MutableClock(Instant.parse("2026-11-11T06:00:00Z"))
    private fun w(id: Long, date: String) = Workout(id, "W$id", Sport.RUN, startLocal = "${date}T07:00:00", steps = emptyList())

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ctx, RondeDatabase::class.java).build()
    }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private fun planSync(list: List<Workout>) = PlanSync({ list }, PlanSyncTest.FakeWatch(), db.plannedWorkoutPrefs(), db.plannedList(), clock)

    @Test fun planTabShowsWhatTheWorkerFetched() = runBlocking {
        val session = AppSession(ctx, scope, planSync(emptyList()))
        withTimeout(5_000) {
            val empty = session.state.first { it.workoutsLoaded }
            assertTrue(empty.workouts.isEmpty())
            assertNull(empty.workoutsFetchedAt) // never fetched: "not synced yet", not "no longer in your plan"

            // SyncWorker: its own PlanSync call, no session involved.
            planSync(listOf(w(1, "2026-11-10"), w(2, "2026-11-11"), w(3, "2026-11-12"))).sync(Settings(token = "x"))
            val st = session.state.first { it.workouts.isNotEmpty() }
            assertEquals(listOf(2L, 3L), st.workouts.map { it.id }) // today onward
            assertEquals(clock.millis(), st.workoutsFetchedAt)
        }
    }

    /** After process death the list is read back from Room, so the detail screen can find its workout. */
    @Test fun storedListSurvivesARestart() = runBlocking {
        planSync(listOf(w(5, "2026-11-12"))).sync(Settings(token = "x"))
        val session = AppSession(ctx, scope, planSync(emptyList()))
        val st = withTimeout(5_000) { session.state.first { it.workoutsLoaded } }
        assertEquals(listOf(5L), st.workouts.map { it.id })
    }
}
