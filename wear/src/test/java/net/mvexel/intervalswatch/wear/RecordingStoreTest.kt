package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.RecPause
import net.mvexel.intervalswatch.shared.RecSample
import net.mvexel.intervalswatch.shared.Recording
import net.mvexel.intervalswatch.shared.Sport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStoreTest {
    private fun rec(elapsedSec: Double, pauses: List<RecPause> = emptyList(), samples: Int = 1) =
        Recording(
            name = "Run", sport = Sport.RUN, indoor = true, startEpochMs = 0L,
            samples = List(samples) { RecSample(t = it.toDouble(), distanceM = 0.0) },
            laps = emptyList(), pauses = pauses, elapsedSec = elapsedSec, totalM = 0.0,
        )

    @Test fun under60sActiveIsNotSaved() {
        assertFalse(RecordingStore.isWorthSaving(rec(59.0)))
    }

    @Test fun exactly60sActiveIsSaved() {
        assertTrue(RecordingStore.isWorthSaving(rec(60.0)))
    }

    @Test fun pausesDoNotCountTowardActiveTime() {
        // 90 s on the wall, 40 s paused: 50 s active, so not saved.
        assertFalse(RecordingStore.isWorthSaving(rec(90.0, listOf(RecPause(10.0, 50.0)))))
    }

    @Test fun noSamplesIsNotSaved() {
        assertFalse(RecordingStore.isWorthSaving(rec(120.0, samples = 0)))
    }
}
