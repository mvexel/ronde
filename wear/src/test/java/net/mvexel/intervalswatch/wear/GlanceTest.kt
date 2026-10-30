package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    @Test fun cueWords() {
        assertEquals("▲ push", cue(-1))
        assertEquals("▼ ease", cue(1))
        assertNull(cue(0))
        assertNull(cue(null))
    }

    @Test fun nextOnlyNearEnd() {
        assertTrue(isNearEnd(StepProgress(0.0, 10.0, 60.0, null, 0.9)))
        assertFalse(isNearEnd(StepProgress(0.0, 11.0, 60.0, null, 0.9)))
        assertTrue(isNearEnd(StepProgress(0.0, null, 60.0, 100.0, 0.9)))
        assertFalse(isNearEnd(StepProgress(0.0, null, 60.0, 400.0, 0.9)))
        assertFalse(isNearEnd(StepProgress(0.0, null, 0.0, null, 0.0)))
    }

    @Test fun stepAverage() {
        assertNull(stepAvgSpeed(30.0, 5.0))
        assertNull(stepAvgSpeed(0.0, 60.0))
        assertEquals(4.0, stepAvgSpeed(240.0, 60.0)!!, 1e-9)
    }

    @Test fun powerText() {
        assertEquals("Power lost", powerStatusText(PowerMeter.Status.DISCONNECTED))
        assertEquals("No power", powerStatusText(PowerMeter.Status.NOT_FOUND))
    }

    @Test fun cadenceDrivesTheLiveValue() {
        val step = WorkoutStep("Run", StepKind.ACTIVE, durationSec = 60, targets = listOf(Target(Metric.CADENCE, 170.0, 180.0)), primary = Metric.CADENCE)
        val w = Workout(1, "t", Sport.RUN, startLocal = "2026-10-30T07:00:00", steps = listOf(step))
        val s = Session(
            w, 0, step, null, StepProgress(0.0, 60.0, 60.0, null, 0.0), 0.0, 0.0,
            heartRate = 150.0, speed = 3.0, power = null, cadence = 165.0, powerStatus = null,
            gpsReady = true, paused = false, finished = false, ended = false,
        )
        assertEquals(165.0, s.live(Metric.CADENCE)!!, 0.0)
        assertEquals(-1, step.primaryTarget!!.compare(s.live(Metric.CADENCE)!!, 0.0))
        assertNull(s.copy(cadence = null).live(Metric.CADENCE))
        assertEquals("170–180 spm", net.mvexel.intervalswatch.shared.Format.target(step.primaryTarget!!, 0.0, w.paceUnit, w.sport))
    }

    @Test fun paceChoiceSwapsHeroAndOther() {
        val avg = paceChoice(3.9, 3.8, PaceView.AVG)
        assertEquals(3.8, avg.hero!!, 1e-9); assertEquals(PaceView.AVG, avg.heroView)
        assertEquals(3.9, avg.other!!, 1e-9)
        val now = paceChoice(3.9, 3.8, PaceView.NOW)
        assertEquals(3.9, now.hero!!, 1e-9); assertEquals(PaceView.AVG, now.otherView)
        // No average yet: fall back to live even when avg is chosen.
        assertEquals(PaceView.NOW, paceChoice(3.9, null, PaceView.AVG).heroView)
    }
}
