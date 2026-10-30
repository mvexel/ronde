package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.wear.ui.isRep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    @Test fun onlyTargetedWorkStepsAreReps() {
        val rep = WorkoutStep("VO2", StepKind.ACTIVE, durationSec = 300, targets = listOf(Target(Metric.POWER, 300.0, 316.0)), primary = Metric.POWER)
        assertTrue(rep.isRep())
        assertFalse(WorkoutStep("Strides", StepKind.ACTIVE, durationSec = 20).isRep())
        assertFalse(Workout.open(Sport.RUN, "2026-10-30T07:00:00").steps.single().isRep())
        assertFalse(rep.copy(kind = StepKind.REST).isRep())
    }

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

    @Test fun totalTimeFitsAFlank() {
        assertEquals("31:04", totalTimeText(1864.0))
        assertEquals("59:59", totalTimeText(3599.0))
        assertEquals("1:00", totalTimeText(3600.0))
    }

    @Test fun ringSegmentsShareTheCircle() {
        val steps = listOf(
            WorkoutStep("a", StepKind.WARMUP, durationSec = 60),
            WorkoutStep("b", StepKind.ACTIVE, durationSec = 180),
            WorkoutStep("c", StepKind.COOLDOWN),
        )
        val seg = ringSegments(steps)
        assertEquals(3, seg.size)
        assertEquals(0f, seg[0].startDeg, 0f)
        // 60 s : 180 s, and the lap step weighs 120 s.
        assertEquals(seg[0].sweepDeg * 3, seg[1].sweepDeg, 1e-3f)
        assertEquals(360f, seg.last().startDeg + seg.last().sweepDeg + 2.4f, 1e-3f)
        assertEquals(seg[1].startDeg + seg[1].sweepDeg / 2, ringNowDeg(seg, 1, 0.5), 1e-3f)
        assertEquals(360f, ringNowDeg(seg, 3, 0.0), 0f)
    }

    @Test fun averagesNeedSomeData() {
        val step = WorkoutStep("Run", StepKind.ACTIVE, durationSec = 60)
        val w = Workout(1, "t", Sport.RUN, startLocal = "2026-10-30T07:00:00", steps = listOf(step))
        val s = Session(
            w, 0, step, null, StepProgress(0.0, 60.0, 60.0, null, 0.0), 600.0, 2000.0,
            heartRate = null, speed = null, power = null, cadence = null, powerStatus = null,
            gpsReady = true, paused = false, finished = false, ended = false, avgHeartRate = 151.6,
        )
        assertEquals(2000.0 / 600.0, averageSpeed(s)!!, 1e-9)
        assertNull(averageSpeed(s.copy(activeSec = 5.0)))
        assertEquals("151", fieldValue(s, net.mvexel.intervalswatch.shared.Field.AVG_HR, "7:00"))
        assertNull(fieldValue(s, net.mvexel.intervalswatch.shared.Field.AVG_POWER, "7:00"))
    }

    @Test fun rideSummaryShowsPowerAndSpeedNotPace() {
        val ride = Samples.workouts.first { it.sport == Sport.RIDE }
        val s = sessionOf(ride, distanceM = 18000.0, activeSec = 3600.0, avgHr = 140.0, avgPower = 201.6)
        assertEquals("140" to "202 W · 18.0 km/h", summaryDetail(s))
    }

    @Test fun runSummaryShowsPace() {
        val run = Samples.workouts.first { it.sport == Sport.RUN }
        val s = sessionOf(run, distanceM = 5000.0, activeSec = 1500.0, avgHr = null, avgPower = null)
        assertEquals(null to "5:00 /km", summaryDetail(s))
    }

    private fun sessionOf(w: Workout, distanceM: Double, activeSec: Double, avgHr: Double?, avgPower: Double?) = Session(
        workout = w, stepIndex = 0, step = null, next = null, progress = StepProgress(0.0, null, 0.0, null, 0.0),
        activeSec = activeSec, distanceM = distanceM, heartRate = null, speed = null, power = null, cadence = null,
        powerStatus = null, gpsReady = true, paused = false, finished = true, ended = true,
        avgHeartRate = avgHr, avgPower = avgPower,
    )
}
