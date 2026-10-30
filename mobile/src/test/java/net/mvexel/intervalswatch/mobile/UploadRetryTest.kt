package net.mvexel.intervalswatch.mobile

import androidx.work.WorkInfo.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRetryTest {
    @Test fun retriesWhenNothingRunning() {
        assertTrue(UploadWorker.shouldRetryNow(emptyList()))
        assertTrue(UploadWorker.shouldRetryNow(listOf(State.ENQUEUED)))
        assertTrue(UploadWorker.shouldRetryNow(listOf(State.FAILED, State.CANCELLED)))
    }

    @Test fun leavesRunningUploadAlone() {
        assertFalse(UploadWorker.shouldRetryNow(listOf(State.RUNNING)))
        assertFalse(UploadWorker.shouldRetryNow(listOf(State.ENQUEUED, State.RUNNING)))
    }
}
