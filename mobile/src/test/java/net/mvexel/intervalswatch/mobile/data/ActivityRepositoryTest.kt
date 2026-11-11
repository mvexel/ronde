package net.mvexel.intervalswatch.mobile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.mvexel.intervalswatch.mobile.IntervalsActivityJson
import net.mvexel.intervalswatch.mobile.IntervalsApi
import net.mvexel.intervalswatch.mobile.RateLimitGate
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.activity.IntervalsActivitySource
import net.mvexel.intervalswatch.mobile.data.activity.RoomActivityRepository
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.fixture
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityRepositoryTest {
    private lateinit var db: RondeDatabase
    private val clock = MutableClock(Instant.parse("2026-11-11T18:00:00Z"))
    private val remote = FakeHistoryRemote()
    private lateinit var repo: RoomActivityRepository

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RondeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomActivityRepository(db.activities(), db.streams(), remote, db.syncState(), clock, rondeClientId = "4242")
    }

    @After fun close() = db.close()

    private suspend fun ids() = repo.activities().first().map { it.id }

    @Test fun refreshStoresTheLastTwoWeeksWithRondeGarminAndStravaMarks() = runBlocking {
        remote.all = IntervalsActivityJson.activities(fixture("activities.json"))
        repo.refresh()
        assertEquals(listOf("list 2026-10-28..now/200"), remote.calls)
        assertEquals(listOf("i1001", "i1000", "i999"), ids())

        val ronde = db.activities().get("i1001")!!
        assertTrue(ronde.isRonde)
        assertEquals(152, ronde.avgHr)
        assertEquals(171, ronde.maxHr)
        assertEquals(555001L, ronde.pairedEventId)
        assertTrue(ronde.detailsAvailable)

        val garmin = db.activities().get("i1000")!!
        assertFalse(garmin.isRonde)
        assertEquals("Garmin Edge 840", garmin.deviceName)
        assertTrue(garmin.isGarmin)
        assertEquals(139, garmin.avgHr)
        assertEquals(201, garmin.avgPower)

        val strava = db.activities().get("i999")!!
        assertFalse(strava.detailsAvailable)
        assertEquals("Strava activity", strava.name)
        assertEquals("STRAVA", strava.source)
    }

    @Test fun refreshIsThrottledUnlessForced() = runBlocking {
        repo.refresh()
        repo.refresh()
        assertEquals(1, remote.calls.size)
        repo.refresh(force = true)
        assertEquals(2, remote.calls.size)
        clock.advanceMinutes(3)
        repo.refresh()
        assertEquals(3, remote.calls.size)
    }

    @Test fun refreshDropsActivitiesDeletedOnTheServerOnlyInsideTheWindow() = runBlocking {
        remote.all = listOf(icu("i1", "2026-11-10T07:00:00"), icu("i2", "2026-11-09T07:00:00"), icu("old", "2026-09-01T07:00:00"))
        repo.refresh()
        assertEquals(listOf("i1", "i2"), ids())
        repo.backfill()
        assertEquals(listOf("i1", "i2", "old"), ids())

        remote.all = listOf(icu("i1", "2026-11-10T07:00:00", name = "Renamed")) // i2 and old deleted on intervals.icu
        repo.refresh(force = true)
        assertEquals(listOf("i1", "old"), ids()) // "old" is outside the 14-day window: the weekly re-sweep handles it
        assertEquals("Renamed", db.activities().get("i1")!!.name)
    }

    @Test fun backfillWalksBackAYearInBoundedPagesThenRestsAWeek() = runBlocking {
        val today = LocalDate.of(2026, 11, 11)
        remote.all = (0L until 500L step 5).map { d -> icu("d$d", "${today.minusDays(d)}T07:00:00") }
        var pages = 0
        while (repo.backfill()) pages++
        pages++ // the last call returned false after fetching its page
        assertEquals(4, remote.calls.size) // (365 - 14) days in 90-day pages
        assertEquals(remote.calls.size, pages)
        assertEquals("list 2026-07-30..2026-10-27/100", remote.calls.first())
        val stored = ids()
        assertTrue("d15" in stored && "d360" in stored)
        assertFalse("d10" in stored) // the last 14 days are refresh()'s job
        assertFalse("d370" in stored) // older than a year: not fetched

        assertFalse(repo.backfill())
        assertEquals(4, remote.calls.size)
        clock.advanceDays(8)
        assertTrue(repo.backfill())
        assertEquals(5, remote.calls.size)
    }

    @Test fun truncatedBackfillPageRefetchesItsOldestDay() = runBlocking {
        val today = LocalDate.of(2026, 11, 11)
        // 3 activities a day for 60 days before the recent window: 180 rows, more than one page of 100.
        remote.all = (15L until 75L).flatMap { d -> (0 until 3).map { h -> icu("d$d-$h", "${today.minusDays(d)}T0$h:00:00") } }
        while (repo.backfill()) Unit
        assertEquals(180, ids().size)
        assertTrue(remote.calls.size in 3..6)
    }

    @Test fun backgroundSyncIsAtMostTwoCallsAndRefreshesOnlyWhenOld() = runBlocking {
        repo.backgroundSync()
        assertEquals(2, remote.calls.size) // refresh + one backfill page
        assertTrue(remote.calls[0].endsWith("now/200"))
        repo.backgroundSync()
        assertEquals(3, remote.calls.size) // backfill page only
        assertFalse(remote.calls[2].endsWith("now/200"))
        clock.advanceMinutes(9 * 60)
        repo.backgroundSync()
        assertTrue(remote.calls[3].endsWith("now/200"))
    }

    @Test fun signedOutDoesNothing() = runBlocking {
        remote.signedIn = false
        repo.refresh()
        repo.backgroundSync()
        assertTrue(remote.calls.isEmpty())
        assertTrue(ids().isEmpty())
        assertNull(db.syncState().get("history.last_refresh_ms"))
    }

    @Test fun clearWipesActivitiesAndSyncState() = runBlocking {
        remote.all = listOf(icu("i1", "2026-11-10T07:00:00"))
        repo.refresh()
        repo.clear()
        assertTrue(ids().isEmpty())
        repo.refresh()
        assertEquals(2, remote.calls.size) // throttle state went with it
    }

    @Test fun refreshOverHttpEndToEnd() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(fixture("activities.json")))
            val api = IntervalsApi(baseUrl = server.url("/").toString().trimEnd('/'), gate = RateLimitGate())
            val source = IntervalsActivitySource(api) { Settings(token = "k") }
            val r = RoomActivityRepository(db.activities(), db.streams(), source, db.syncState(), clock)
            r.refresh()
            assertEquals(3, r.activities().first().size)
            assertEquals("2026-10-28", server.takeRequest().requestUrl!!.queryParameter("oldest"))
        } finally {
            server.shutdown()
        }
    }
}
