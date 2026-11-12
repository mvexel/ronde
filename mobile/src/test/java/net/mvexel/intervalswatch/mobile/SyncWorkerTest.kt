package net.mvexel.intervalswatch.mobile

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.runBlocking
import net.mvexel.intervalswatch.mobile.data.MutableClock
import net.mvexel.intervalswatch.mobile.data.PlanSyncTest
import net.mvexel.intervalswatch.mobile.data.db.PlannedListDao
import net.mvexel.intervalswatch.mobile.data.db.PlannedListEntity
import net.mvexel.intervalswatch.mobile.data.plan.PlanSync
import net.mvexel.intervalswatch.mobile.fakes.FakePrefsDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {
    private class MemListDao : PlannedListDao {
        val row = MutableStateFlow<PlannedListEntity?>(null)
        override fun observe() = row
        override suspend fun get() = row.value
        override suspend fun put(list: PlannedListEntity) { row.value = list }
        override suspend fun clear() { row.value = null }
    }

    /** Finding 5: reading the hidden set fails (e.g. Room busy or corrupt) → retry, not failure. */
    @Test fun hiddenSetFailureIsARetry() = runBlocking {
        val prefs = object : net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsDao by FakePrefsDao() {
            override suspend fun hiddenIds(): List<Long> = throw IllegalStateException("database is locked")
        }
        val watch = PlanSyncTest.FakeWatch()
        val sync = PlanSync({ emptyList() }, watch, prefs, MemListDao(), MutableClock(Instant.EPOCH))
        var history = 0
        val r = SyncWorker.run(plan = { sync.sync(Settings(token = "x")) }, history = { history++ })
        assertTrue(r is Result.Retry)
        assertEquals(1, history) // History still gets its turn
    }

    @Test fun outcomes() = runBlocking {
        assertTrue(SyncWorker.run({}, {}) is Result.Success)
        assertTrue(SyncWorker.run({ throw IOException("offline") }, {}) is Result.Retry)
        assertTrue(SyncWorker.run({ throw AuthExpiredException("expired") }, { error("not reached") }) is Result.Failure)
        assertTrue(SyncWorker.run({}, { throw IOException("history down") }) is Result.Success)
    }
}
