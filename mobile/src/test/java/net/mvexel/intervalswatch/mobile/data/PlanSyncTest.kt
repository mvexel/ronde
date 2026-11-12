package net.mvexel.intervalswatch.mobile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.mvexel.intervalswatch.mobile.AuthExpiredException
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.data.db.setHidden
import net.mvexel.intervalswatch.mobile.data.plan.PlanSync
import net.mvexel.intervalswatch.mobile.data.plan.PlannedFetcher
import net.mvexel.intervalswatch.mobile.data.plan.WatchLink
import net.mvexel.intervalswatch.mobile.data.plan.upcoming
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.Workout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanSyncTest {
    private lateinit var db: RondeDatabase
    private val clock = MutableClock(Instant.parse("2026-11-11T06:00:00Z"))
    private val watch = FakeWatch()
    private val settings = Settings(token = "x")

    private fun w(id: Long, date: String) = Workout(id, "W$id", Sport.RUN, startLocal = "${date}T07:00:00", steps = emptyList())
    private val a = w(1, "2026-11-11")
    private val b = w(2, "2026-11-12")
    private val c = w(3, "2026-11-13")

    class FakeWatch : WatchLink {
        /** Every list pushed, in order; the last one is what the watch shows. */
        val pushes = mutableListOf<List<Long>>()
        val loggedIn = mutableListOf<Boolean>()
        override suspend fun push(workouts: List<Workout>) { pushes += workouts.map { it.id } }
        override suspend fun pushLoggedIn(loggedIn: Boolean) { this.loggedIn += loggedIn }
    }

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RondeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun close() = db.close()

    private fun planSync(fetcher: PlannedFetcher) = PlanSync(fetcher, watch, db.plannedWorkoutPrefs(), db.plannedList(), clock)

    /** Finding 1: a hide while the fetch is in flight must not reach the watch, neither from the sync nor after. */
    @Test fun hideDuringSlowFetchDoesNotReappearOnTheWatch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<Workout>>()
        val sync = planSync { started.complete(Unit); release.await() }

        val running = async { sync.sync(settings) }
        started.await()
        // The user hides workout 2 while intervals.icu is still answering; the Plan VM then asks for a re-push.
        db.plannedWorkoutPrefs().setHidden(2, true)
        val push = async { sync.pushStored() }
        yield()
        release.complete(listOf(a, b, c))

        assertEquals(2, running.await().sent)
        assertEquals(2, push.await()) // waited for the sync, then re-sent from what it stored
        assertTrue(watch.pushes.isNotEmpty())
        watch.pushes.forEach { assertEquals(listOf(1L, 3L), it) }
    }

    /** Finding 2: hide/unhide re-sends the newest stored list, even when a background sync fetched it. */
    @Test fun rePushUsesTheListTheWorkerFetched() = runBlocking {
        var server = listOf(a, b)
        val foreground = planSync { server }
        foreground.sync(settings)
        assertEquals(listOf(1L, 2L), watch.pushes.last())

        // The worker runs in the same process (AppContainer.planSync), or a later one: either way it writes Room.
        server = listOf(a, b, c)
        planSync { server }.sync(settings)
        assertEquals(listOf(1L, 2L, 3L), watch.pushes.last())

        db.plannedWorkoutPrefs().setHidden(1, true)
        assertEquals(2, foreground.pushStored())
        assertEquals(listOf(2L, 3L), watch.pushes.last()) // workout 3 is not dropped
        assertEquals(listOf(1L, 2L, 3L), foreground.observe().first()!!.workouts.map { it.id })
    }

    @Test fun rePushSendsTodayOnward() = runBlocking {
        val sync = planSync { listOf(a, b, c) }
        sync.sync(settings)
        clock.advanceDays(2) // 2026-11-13
        assertEquals(1, sync.pushStored())
        assertEquals(listOf(3L), watch.pushes.last())
        assertEquals(listOf(3L), sync.upcoming(listOf(a, b, c)).map { it.id })
    }

    @Test fun nothingFetchedYetSendsNothing() = runBlocking {
        val sync = planSync { fail("no fetch expected"); emptyList() }
        assertNull(sync.pushStored())
        assertTrue(watch.pushes.isEmpty())
        assertNull(sync.observe().first())
    }

    @Test fun expiredLoginTellsTheWatchAndStoresNothing() = runBlocking {
        val sync = planSync { throw AuthExpiredException("expired") }
        try {
            sync.sync(settings)
            fail()
        } catch (_: AuthExpiredException) {
        }
        assertEquals(listOf(false), watch.loggedIn)
        assertTrue(watch.pushes.isEmpty())
        assertNull(db.plannedList().get())
    }

    @Test fun storedListCarriesItsFetchTimeAndClears() = runBlocking {
        val sync = planSync { listOf(a) }
        sync.sync(settings)
        assertEquals(clock.millis(), sync.observe().first()!!.fetchedAtMillis)
        assertEquals(listOf(true), watch.loggedIn)
        sync.clear()
        assertNull(sync.observe().first())
    }

    @Test fun upcomingKeepsUnreadableDates() {
        val odd = Workout(9, "odd", Sport.RUN, startLocal = "", steps = emptyList())
        assertEquals(listOf(9L, 2L), upcoming(listOf(odd, a, b), LocalDate.parse("2026-11-12")).map { it.id })
    }

    /** Many hides racing many syncs: whatever interleaving, the watch ends with the final hidden set applied. */
    @Test fun concurrentHidesAndSyncsEndConsistent() = runBlocking {
        val sync = planSync { yield(); listOf(a, b, c) }
        val jobs = (1..20).map { i ->
            launch {
                if (i % 2 == 0) sync.sync(settings) else {
                    db.plannedWorkoutPrefs().setHidden(((i / 2) % 3 + 1).toLong(), i % 4 == 1)
                    sync.pushStored()
                }
            }
        }
        jobs.forEach { it.join() }
        sync.pushStored()
        val hidden = db.plannedWorkoutPrefs().hiddenIds().toSet()
        assertEquals(listOf(1L, 2L, 3L).filterNot { it in hidden }, watch.pushes.last())
    }
}
