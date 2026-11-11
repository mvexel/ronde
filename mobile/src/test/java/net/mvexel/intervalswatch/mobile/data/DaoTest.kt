package net.mvexel.intervalswatch.mobile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.mvexel.intervalswatch.mobile.data.db.PlannedListEntity
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsEntity
import net.mvexel.intervalswatch.mobile.data.db.decode
import net.mvexel.intervalswatch.mobile.data.activity.RoomActivityRepository
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.data.db.encode
import net.mvexel.intervalswatch.mobile.data.db.setHidden
import net.mvexel.intervalswatch.mobile.data.db.setPinned
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room DAOs against an in-memory database (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DaoTest {
    private lateinit var db: RondeDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RondeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun close() = db.close()

    private fun activity(id: String, start: String) = ActivityEntity(id, "A $id", "Run", start, distanceM = 1000.0, isRonde = id == "i2", fetchedAt = 1)

    @Test fun activitiesNewestFirstAndUpsert() = runBlocking {
        val dao = db.activities()
        assertTrue(dao.observeAll().first().isEmpty())
        dao.upsertAll(listOf(activity("i1", "2026-11-01T07:00:00"), activity("i2", "2026-11-03T07:00:00")))
        assertEquals(listOf("i2", "i1"), dao.observeAll().first().map { it.id })
        dao.upsertAll(listOf(activity("i1", "2026-11-01T07:00:00").copy(name = "Renamed")))
        assertEquals("Renamed", dao.observe("i1").first()?.name)
        assertTrue(dao.observe("i2").first()!!.isRonde)
        assertNull(dao.observe("nope").first())
    }

    @Test fun streamsRoundTripAndCascadeOnDelete() = runBlocking {
        db.activities().upsertAll(listOf(activity("i1", "2026-11-01T07:00:00")))
        val repo = RoomActivityRepository(db.activities(), db.streams())
        db.streams().upsert(ActivityStreams("i1", time = doubleArrayOf(0.0, 1.0), heartrate = doubleArrayOf(120.0, 121.0)).encode())
        val s = repo.streams("i1").first()!!
        assertArrayEquals(doubleArrayOf(120.0, 121.0), s.heartrate, 0.0)
        assertNull(s.watts)
        db.activities().delete("i1")
        assertNull(repo.streams("i1").first())
    }

    @Test fun repositoryRefreshStoresWhatTheRemoteReturns() = runBlocking {
        val remote = FakeHistoryRemote(listOf(icu("i5", java.time.LocalDate.now().toString() + "T07:00:00")))
        val repo = RoomActivityRepository(db.activities(), db.streams(), remote)
        repo.refresh()
        assertEquals(listOf("i5"), repo.activities().first().map { it.id })
    }

    @Test fun plannedWorkoutPrefs() = runBlocking {
        val dao = db.plannedWorkoutPrefs()
        assertTrue(dao.hiddenIds().isEmpty())
        assertEquals(0, dao.maxPinnedOrder())
        dao.setHidden(10, true)
        dao.setPinned(11, true)
        dao.setPinned(12, true)
        assertEquals(listOf(10L), dao.hiddenIds())
        assertEquals(2, dao.maxPinnedOrder())
        assertEquals(1, dao.get(11)!!.sortOrder)
        dao.setHidden(10, false)
        dao.setPinned(11, false)
        assertTrue(dao.hiddenIds().isEmpty())
        assertEquals(3, dao.observeAll().first().size)
        assertEquals(false, dao.get(11)!!.pinned)
    }

    @Test fun pinAndHideKeepEachOther() = runBlocking {
        val dao = db.plannedWorkoutPrefs()
        dao.setHidden(5, true)
        dao.setPinned(5, true)
        assertEquals(PlannedWorkoutPrefsEntity(5, pinned = true, hidden = true, sortOrder = 1), dao.get(5))
        dao.setPinned(6, true)
        dao.setHidden(6, true)
        dao.setHidden(5, false)
        assertEquals(PlannedWorkoutPrefsEntity(5, pinned = true, hidden = false, sortOrder = 1), dao.get(5))
        assertEquals(PlannedWorkoutPrefsEntity(6, pinned = true, hidden = true, sortOrder = 2), dao.get(6))
        dao.setPinned(5, false)
        assertEquals(PlannedWorkoutPrefsEntity(5), dao.get(5))
    }

    /** Finding 4: a pin and a hide of the same workout at the same time must both stick (no read-modify-write). */
    @Test fun concurrentPinAndHideLoseNothing() = runBlocking {
        val dao = db.plannedWorkoutPrefs()
        val ids = (1L..150L).toList()
        coroutineScope {
            ids.forEach { id ->
                launch(Dispatchers.IO) { dao.setPinned(id, true) }
                launch(Dispatchers.IO) { dao.setHidden(id, true) }
            }
        }
        val rows = dao.observeAll().first().associateBy { it.eventId }
        ids.forEach { id ->
            val r = rows[id]!!
            assertTrue("pin of $id lost", r.pinned)
            assertTrue("hide of $id lost", r.hidden)
        }
        assertEquals((1..150).toSet(), rows.values.map { it.sortOrder }.toSet()) // the pin order stays unique
    }

    @Test fun movingStreamRoundTrips() = runBlocking {
        db.activities().upsertAll(listOf(activity("i1", "2026-11-01T07:00:00")))
        db.streams().upsert(ActivityStreams("i1", time = doubleArrayOf(0.0, 1.0, 2.0), moving = booleanArrayOf(true, false, true)).encode())
        assertEquals(listOf(true, false, true), db.streams().get("i1")!!.decode().moving!!.toList())
    }

    @Test fun plannedListIsOneRow() = runBlocking {
        val dao = db.plannedList()
        assertNull(dao.get())
        dao.put(PlannedListEntity(json = "a", fetchedAt = 1))
        dao.put(PlannedListEntity(json = "b", fetchedAt = 2))
        assertEquals(PlannedListEntity(json = "b", fetchedAt = 2), dao.observe().first())
        dao.clear()
        assertNull(dao.get())
    }
}
