package net.mvexel.intervalswatch.mobile

import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class HomePartsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun step(kind: StepKind, sec: Int) = WorkoutStep("s", kind, durationSec = sec)
    private val today = LocalDate.of(2026, 10, 30)

    @Test fun arcsAreProportionalAndFillTheCircle() {
        val arcs = ringArcs(listOf(step(StepKind.WARMUP, 600), step(StepKind.ACTIVE, 300), step(StepKind.REST, 300)))
        assertEquals(3, arcs.size)
        assertEquals(arcs[1].sweep, arcs[2].sweep, 0.001f)
        assertEquals(arcs[0].sweep, arcs[1].sweep * 2, 0.001f)
        val last = arcs.last()
        assertEquals(360f, last.start + last.sweep + 2.4f / 2, 0.01f)
    }

    @Test fun planLineCollapsesRepeats() {
        val w = Workout(1, "x", Sport.RUN, startLocal = "2026-10-30T07:00:00", steps = listOf(
            step(StepKind.WARMUP, 600), step(StepKind.ACTIVE, 300), step(StepKind.REST, 120),
            step(StepKind.ACTIVE, 300), step(StepKind.REST, 120), step(StepKind.COOLDOWN, 300),
        ), totalDurationSec = 1740)
        assertEquals("10:00 · 5:00 · 2:00 · 5:00 …", planLine(w))
        assertEquals("Today · Run · 29 min", workoutMeta(w, today))
    }

    @Test fun dayLabels() {
        assertEquals("Today", dayLabel("2026-10-30T07:00:00", today))
        assertEquals("Tomorrow", dayLabel("2026-10-31T07:00:00", today))
    }

    @Test fun retryClearsFailedState() {
        val lib = RecordingLibrary(tmp.root)
        tmp.newFile("1_a.fit")
        lib.markFailed("1_a.fit", "bad")
        lib.clearState("1_a.fit")
        assertEquals(UploadState.PENDING, lib.state("1_a.fit"))
    }

    @Test fun openRecordingsAreLabelled() {
        assertTrue(isOpenRecording(RecordingItem("a.fit", "Open ride", 1, 1, UploadState.UPLOADED)))
    }
}
