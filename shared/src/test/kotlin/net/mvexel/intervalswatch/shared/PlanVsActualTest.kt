package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanVsActualTest {

    /** 1 Hz synthetic activity. Each added second is one sample at the end of that second. */
    private class Synth {
        val t = mutableListOf(0.0)
        val v = mutableListOf(Double.NaN)
        val hr = mutableListOf(Double.NaN)
        val w = mutableListOf(Double.NaN)
        val d = mutableListOf(0.0)
        val moving = mutableListOf(true)
        val pauses = mutableListOf<TimeSpan>()
        val now get() = t.last()

        fun go(sec: Int, speed: Double, bpm: Double = Double.NaN, watts: (Int) -> Double = { Double.NaN }) = apply {
            for (i in 1..sec) {
                t += now + 1; v += speed; hr += bpm; w += watts(i); d += d.last() + speed; moving += true
            }
        }

        fun pause(sec: Int, asSpan: Boolean = true) = apply {
            val start = now
            for (i in 1..sec) { t += now + 1; v += 0.0; hr += 100.0; w += 0.0; d += d.last(); moving += asSpan }
            if (asSpan) pauses += TimeSpan(start, now)
        }

        fun build(
            velocity: Boolean = true, heart: Boolean = true, power: Boolean = false, distance: Boolean = true,
            pauseSpans: Boolean = true, movingFlags: Boolean = false,
        ) = ActivityStreams(
            timeSec = t.toDoubleArray(),
            velocityMps = v.toDoubleArray().takeIf { velocity },
            heartrate = hr.toDoubleArray().takeIf { heart },
            watts = w.toDoubleArray().takeIf { power },
            distanceM = d.toDoubleArray().takeIf { distance },
            moving = moving.toBooleanArray().takeIf { movingFlags },
            pauses = if (pauseSpans) pauses.toList() else emptyList(),
        )
    }

    private fun mps(pace: String): Double {
        val (m, s) = pace.split(":").map { it.toInt() }
        return 1000.0 / (m * 60 + s)
    }

    private val work = Target(Metric.PACE, mps("4:20"), mps("4:10"))
    private val easy = Target(Metric.PACE, mps("6:00"), mps("5:30"))

    /** 10' warmup, 5x(3' @ 4:10-4:20/km, 2' easy), 10' cooldown. */
    private val intervals: List<WorkoutStep> = buildList {
        add(WorkoutStep("Warm up", StepKind.WARMUP, durationSec = 600, targets = listOf(easy), primary = Metric.PACE))
        for (r in 1..5) {
            add(WorkoutStep("Work", StepKind.ACTIVE, durationSec = 180, targets = listOf(work), primary = Metric.PACE, repeat = "$r/5"))
            add(WorkoutStep("Recovery", StepKind.REST, durationSec = 120, repeat = "$r/5"))
        }
        add(WorkoutStep("Cool down", StepKind.COOLDOWN, durationSec = 600))
    }

    private val onPace = mps("4:15")
    private val repSpeeds = listOf(onPace, mps("3:52"), onPace, mps("4:38"), onPace)

    private fun intervalRun(reps: Int = 5, extraSec: Int = 0, pauseInRep1: Int = 0, pauseAsSpan: Boolean = true) = Synth().apply {
        go(600, 2.9, 130.0)
        for (r in 0 until reps) {
            if (r == 0 && pauseInRep1 > 0) { go(90, repSpeeds[r], 165.0); pause(pauseInRep1, pauseAsSpan); go(90, repSpeeds[r], 165.0) }
            else go(180, repSpeeds[r], 165.0)
            go(120, 2.5, 140.0)
        }
        go(600, 2.8, 135.0)
        if (extraSec > 0) go(extraSec, 2.5, 130.0)
    }

    private fun spans(lens: List<Int>) =
        lens.runningFold(0) { acc, x -> acc + x }.zipWithNext { x, y -> TimeSpan(x.toDouble(), y.toDouble()) }

    private fun workSteps(a: PlanAnalysis) = a.steps.filter { it.kind == StepKind.ACTIVE }

    @Test
    fun intervalsOneRepFastOneSlow() {
        val a = PlanVsActual.analyze(intervals, Sport.RUN, intervalRun().build())
        assertEquals(Alignment.PLANNED, a.summary.alignment)
        assertEquals(12, a.summary.stepsCompleted)
        assertFalse(a.summary.endedEarly)
        assertNull(a.summary.extra)
        assertEquals(listOf(Verdict.IN, Verdict.FAST, Verdict.IN, Verdict.SLOW, Verdict.IN), workSteps(a).map { it.verdict })

        val rep2 = a.steps[3]
        assertEquals("2/5", rep2.step.repeat)
        assertEquals(900.0, rep2.actual!!.startSec, 0.0)
        assertEquals(1080.0, rep2.actual!!.endSec, 0.0)
        assertEquals(180.0, rep2.actual!!.movingSec, 0.0)
        assertEquals(100.0, rep2.timeInRange!!.pctAbove, 1e-9)
        assertEquals(165.0, rep2.actual!!.avgHr!!, 1e-9)
        assertEquals(100.0, a.steps[7].timeInRange!!.pctBelow, 1e-9)
        assertEquals(100.0, a.steps[1].timeInRange!!.pctIn, 1e-9)

        assertEquals(Verdict.IN, a.steps[0].verdict)
        assertNull(a.steps[2].verdict)        // recovery without target
        assertNull(a.steps[2].timeInRange)
        assertEquals(3 * 180.0, a.summary.workTimeInRangeSec, 1e-9)
        assertEquals(60.0, a.summary.workPctInRange!!, 1e-9)
        assertEquals(2700.0, a.summary.total!!.movingSec, 0.0)

        assertEquals("4:15 /km", a.steps[1].actual!!.speedText(Sport.RUN, PaceUnit.KM))
        assertEquals("4:20–4:10 /km", PlanVsActual.targetText(work, PaceUnit.KM, Sport.RUN))
    }

    @Test
    fun boundariesWinOverPlannedLengths() {
        // Warmup ran 11', and rep 3 was cut at 2' by a lap press.
        val s = Synth().go(660, 2.9).go(180, onPace).go(120, 2.5).go(180, mps("3:52")).go(120, 2.5)
            .go(120, onPace).go(180, 2.5).go(180, mps("4:38")).go(120, 2.5).go(180, onPace).go(120, 2.5).go(600, 2.8)
        val lens = listOf(660, 180, 120, 180, 120, 120, 180, 180, 120, 180, 120, 600)
        val laps = spans(lens)
        val a = PlanVsActual.analyze(intervals, Sport.RUN, s.build(), boundaries = laps)
        assertEquals(Alignment.BOUNDARIES, a.summary.alignment)
        assertEquals(listOf(Verdict.IN, Verdict.FAST, Verdict.IN, Verdict.SLOW, Verdict.IN), workSteps(a).map { it.verdict })
        assertEquals(660.0, a.steps[0].actual!!.movingSec, 0.0)
        assertEquals(StepStatus.PARTIAL, a.steps[5].status)       // 2' of 3'
        assertEquals(120.0, a.steps[5].actual!!.movingSec, 0.0)
        assertEquals(StepStatus.COMPLETE, a.steps[6].status)      // longer recovery is fine
        assertEquals(11, a.summary.stepsCompleted)
        assertFalse(a.summary.endedEarly)
    }

    @Test
    fun boundariesFewerThanStepsAndMoreThanSteps() {
        val s = intervalRun(extraSec = 300).build()
        val laps = spans(listOf(600, 180, 120))
        val short = PlanVsActual.analyze(intervals, Sport.RUN, s, boundaries = laps)
        assertEquals(3, short.summary.stepsCompleted)
        assertEquals(9, short.summary.stepsNotReached)
        assertTrue(short.summary.endedEarly)
        assertNull(short.summary.extra)

        val all = spans(intervals.map { it.durationSec!! }) + TimeSpan(2700.0, 3000.0)
        val long = PlanVsActual.analyze(intervals, Sport.RUN, s, boundaries = all)
        assertEquals(12, long.summary.stepsCompleted)
        assertEquals(300.0, long.summary.extra!!.movingSec, 0.0)
    }

    @Test
    fun pausesDoNotCountTowardsStepLengthOrAverages() {
        for (asSpan in listOf(true, false)) {
            val s = intervalRun(pauseInRep1 = 60, pauseAsSpan = asSpan).build(movingFlags = !asSpan, pauseSpans = asSpan)
            val a = PlanVsActual.analyze(intervals, Sport.RUN, s)
            val rep1 = a.steps[1]
            assertEquals(240.0, rep1.actual!!.elapsedSec, 0.0)
            assertEquals(180.0, rep1.actual!!.movingSec, 0.0)
            assertEquals(onPace, rep1.actual!!.avgSpeedMps!!, 1e-9)
            assertEquals(165.0, rep1.actual!!.avgHr!!, 1e-9)    // the 100 bpm while paused is left out
            assertEquals(100.0, rep1.timeInRange!!.pctIn, 1e-9)
            assertEquals(Verdict.IN, rep1.verdict)
            assertEquals(840.0, a.steps[2].actual!!.startSec, 0.0)
            assertEquals(12, a.summary.stepsCompleted)
            assertEquals(2700.0, a.summary.total!!.movingSec, 0.0)
        }
    }

    @Test
    fun workoutEndingEarly() {
        val s = Synth().go(600, 2.9).go(180, onPace).go(120, 2.5).go(180, onPace).go(120, 2.5).go(90, onPace).build()
        val a = PlanVsActual.analyze(intervals, Sport.RUN, s)
        assertEquals(5, a.summary.stepsCompleted)
        assertEquals(1, a.summary.stepsPartial)
        assertEquals(6, a.summary.stepsNotReached)
        assertTrue(a.summary.endedEarly)
        assertEquals(StepStatus.PARTIAL, a.steps[5].status)
        assertEquals(90.0, a.steps[5].actual!!.movingSec, 0.0)
        assertEquals(Verdict.IN, a.steps[5].verdict)
        assertNull(a.steps[6].actual)
        assertEquals(StepStatus.NOT_REACHED, a.steps[11].status)
        assertEquals(100.0, a.summary.workPctInRange!!, 1e-9)
    }

    @Test
    fun extraTimeAfterThePlan() {
        val a = PlanVsActual.analyze(intervals, Sport.RUN, intervalRun(extraSec = 300).build())
        assertEquals(12, a.summary.stepsCompleted)
        assertEquals(600.0, a.steps.last().actual!!.movingSec, 0.0)
        val extra = a.summary.extra!!
        assertEquals(2700.0, extra.startSec, 0.0)
        assertEquals(300.0, extra.movingSec, 0.0)
        assertEquals(750.0, extra.distanceM!!, 1e-9)
        // A couple of seconds after the plan is not "extra".
        assertNull(PlanVsActual.analyze(intervals, Sport.RUN, intervalRun(extraSec = 3).build()).summary.extra)
    }

    @Test
    fun missingStreams() {
        // Distance only: pace comes from distance.
        val distOnly = PlanVsActual.analyze(intervals, Sport.RUN, intervalRun().build(velocity = false, heart = false))
        assertEquals(listOf(Verdict.IN, Verdict.FAST, Verdict.IN, Verdict.SLOW, Verdict.IN), workSteps(distOnly).map { it.verdict })
        assertEquals(onPace, distOnly.steps[1].actual!!.avgSpeedMps!!, 1e-9)
        assertNull(distOnly.steps[1].actual!!.avgHr)

        // HR target without an HR stream: actuals but no verdict.
        val hrStep = WorkoutStep("Z2", StepKind.ACTIVE, durationSec = 600,
            targets = listOf(Target(Metric.HEART_RATE, 130.0, 145.0)), primary = Metric.HEART_RATE)
        val noHr = PlanVsActual.analyze(listOf(hrStep), Sport.RUN, Synth().go(600, 3.0).build(heart = false))
        assertEquals(StepStatus.COMPLETE, noHr.steps[0].status)
        assertNull(noHr.steps[0].verdict)
        assertNull(noHr.steps[0].timeInRange)
        assertNull(noHr.summary.workPctInRange)
        assertEquals(1800.0, noHr.steps[0].actual!!.distanceM!!, 1e-9)

        // Velocity only: distance is integrated from it.
        val vOnly = PlanVsActual.analyze(intervals, Sport.RUN, intervalRun().build(distance = false))
        assertEquals(180 * onPace, vOnly.steps[1].actual!!.distanceM!!, 1e-6)

        // Nothing but time and HR: a distance step can't be placed, so it is treated like a lap-press step.
        val distStep = WorkoutStep("2 km", StepKind.WARMUP, distanceM = 2000.0)
        val timed = WorkoutStep("Work", StepKind.ACTIVE, durationSec = 300)
        val hrOnly = PlanVsActual.analyze(listOf(distStep, timed), Sport.RUN, Synth().go(1000, 3.0, 150.0).build(velocity = false, distance = false))
        assertEquals(700.0, hrOnly.steps[0].actual!!.movingSec, 0.0)
        assertEquals(300.0, hrOnly.steps[1].actual!!.movingSec, 0.0)
        assertNull(hrOnly.steps[0].actual!!.avgSpeedMps)
        assertEquals(150.0, hrOnly.steps[1].actual!!.avgHr!!, 1e-9)

        // No samples at all.
        val empty = PlanVsActual.analyze(intervals, Sport.RUN, ActivityStreams(DoubleArray(0)))
        assertEquals(12, empty.summary.stepsNotReached)
        assertNull(empty.summary.total)
    }

    @Test
    fun rampTargetsMoveOverTheStep() {
        val ramp = Target(Metric.POWER, 100.0, 200.0, rampFrom = 100.0, rampTo = 200.0)
        val step = listOf(WorkoutStep("Ramp", StepKind.WARMUP, durationSec = 600, targets = listOf(ramp), primary = Metric.POWER))

        val follows = PlanVsActual.analyze(step, Sport.RIDE, Synth().go(600, 8.0) { i -> 100.0 + 100.0 * (i - 0.5) / 600 }.build(power = true))
        assertEquals(100.0, follows.steps[0].timeInRange!!.pctIn, 1e-9)
        assertEquals(Verdict.IN, follows.steps[0].verdict)

        // Flat at the midpoint: the average matches, but it's only in range around the middle.
        val flat = PlanVsActual.analyze(step, Sport.RIDE, Synth().go(600, 8.0) { 150.0 }.build(power = true)).steps[0]
        assertEquals(Verdict.IN, flat.verdict)
        assertTrue("${flat.timeInRange}", flat.timeInRange!!.pctIn < 10)
        assertEquals(flat.timeInRange!!.pctAbove, flat.timeInRange!!.pctBelow, 1.0)

        val high = PlanVsActual.analyze(step, Sport.RIDE, Synth().go(600, 8.0) { 200.0 }.build(power = true)).steps[0]
        assertEquals(Verdict.HIGH, high.verdict)
        assertEquals("100 → 200 W", PlanVsActual.targetText(ramp, PaceUnit.KM, Sport.RIDE))
    }

    @Test
    fun oneSidedTargets() {
        val cap = Target(Metric.HEART_RATE, 0.0, 140.0)
        val step = listOf(WorkoutStep("Easy", StepKind.ACTIVE, durationSec = 300, targets = listOf(cap), primary = Metric.HEART_RATE))
        val under = PlanVsActual.analyze(step, Sport.RUN, Synth().go(300, 3.0, 100.0).build()).steps[0]
        assertEquals(Verdict.IN, under.verdict)
        assertEquals(100.0, under.timeInRange!!.pctIn, 1e-9)
        val over = PlanVsActual.analyze(step, Sport.RUN, Synth().go(300, 3.0, 150.0).build()).steps[0]
        assertEquals(Verdict.HIGH, over.verdict)
        assertEquals(100.0, over.timeInRange!!.pctAbove, 1e-9)
        assertEquals("≤ 140 bpm", PlanVsActual.targetText(cap, PaceUnit.KM))

        val faster = Target(Metric.PACE, mps("5:00"), Double.POSITIVE_INFINITY)
        val pStep = listOf(WorkoutStep("Steady", StepKind.ACTIVE, durationSec = 300, targets = listOf(faster), primary = Metric.PACE))
        assertEquals(Verdict.IN, PlanVsActual.analyze(pStep, Sport.RUN, Synth().go(300, 5.0).build()).steps[0].verdict)
        assertEquals(Verdict.SLOW, PlanVsActual.analyze(pStep, Sport.RUN, Synth().go(300, 3.0).build()).steps[0].verdict)
        assertEquals("faster than 5:00 /km", PlanVsActual.targetText(faster, PaceUnit.KM))
        assertEquals("≥ 250 W", PlanVsActual.targetText(Target(Metric.POWER, 250.0, Double.POSITIVE_INFINITY), PaceUnit.KM))
    }

    @Test
    fun toleranceAroundTheRange() {
        val step = listOf(WorkoutStep("Work", StepKind.ACTIVE, durationSec = 180, targets = listOf(work), primary = Metric.PACE))
        // 4:08/km is out of 4:10-4:20 but within 1.5 %: IN, though all its time is "above".
        val justFast = PlanVsActual.analyze(step, Sport.RUN, Synth().go(180, mps("4:08")).build()).steps[0]
        assertEquals(Verdict.IN, justFast.verdict)
        assertEquals(100.0, justFast.timeInRange!!.pctAbove, 1e-9)
        val strict = PlanVsActual.analyze(step, Sport.RUN, Synth().go(180, mps("4:08")).build(), tolerance = Tolerance(paceFraction = 0.0))
        assertEquals(Verdict.FAST, strict.steps[0].verdict)
        assertEquals(Verdict.FAST, PlanVsActual.analyze(step, Sport.RUN, Synth().go(180, mps("4:04")).build()).steps[0].verdict)
    }

    @Test
    fun lapPressStepTakesTheTimeBetween() {
        val steps = listOf(
            WorkoutStep("Warm up", StepKind.WARMUP, durationSec = 600),
            WorkoutStep("Until lap", StepKind.ACTIVE, targets = listOf(work), primary = Metric.PACE),
            WorkoutStep("Cool down", StepKind.COOLDOWN, durationSec = 300),
        )
        val a = PlanVsActual.analyze(steps, Sport.RUN, Synth().go(600, 2.9).go(900, onPace).go(300, 2.8).build())
        assertEquals(600.0, a.steps[1].actual!!.startSec, 0.0)
        assertEquals(1500.0, a.steps[1].actual!!.endSec, 0.0)
        assertEquals(Verdict.IN, a.steps[1].verdict)
        assertEquals(2.8, a.steps[2].actual!!.avgSpeedMps!!, 1e-9)
        assertEquals(3, a.summary.stepsCompleted)

        // Too short for both fixed steps: laid out from the start, cooldown not reached.
        val short = PlanVsActual.analyze(steps, Sport.RUN, Synth().go(600, 2.9).go(200, onPace).build())
        assertEquals(200.0, short.steps[1].actual!!.movingSec, 0.0)
        assertEquals(StepStatus.NOT_REACHED, short.steps[2].status)
        assertTrue(short.summary.endedEarly)
    }

    @Test
    fun severalLapPressStepsShareTheSlack() {
        val lap = WorkoutStep("Lap", StepKind.ACTIVE)
        val steps = listOf(
            WorkoutStep("Warm up", StepKind.WARMUP, durationSec = 300), lap,
            WorkoutStep("Work", StepKind.ACTIVE, durationSec = 300), lap,
            WorkoutStep("Cool down", StepKind.COOLDOWN, durationSec = 300),
        )
        val a = PlanVsActual.analyze(steps, Sport.RUN, Synth().go(1500, 3.0).build())
        assertEquals(listOf(0.0, 300.0, 600.0, 900.0, 1200.0), a.steps.map { it.actual!!.startSec })
        assertEquals(listOf(300.0, 600.0, 900.0, 1200.0, 1500.0), a.steps.map { it.actual!!.endSec })
    }

    @Test
    fun parsedRunWithDistanceWarmupAndLapCooldown() {
        val w = Samples.run    // 2 km warmup, 3x(10' tempo, 2' HR recovery), cooldown until lap
        val s = Synth().go(667, 3.0, 130.0)
        repeat(3) { s.go(600, 3.68, 160.0).go(120, 2.8, 126.0) }
        s.go(400, 2.7, 120.0)
        val a = PlanVsActual.analyze(w.steps, w.sport, s.build())
        assertEquals(8, a.summary.stepsCompleted)
        assertEquals(667.0, a.steps[0].actual!!.endSec, 0.0)
        assertEquals(2001.0, a.steps[0].actual!!.distanceM!!, 1e-9)
        assertTrue(a.steps.filter { it.kind == StepKind.ACTIVE }.all { it.verdict == Verdict.IN })
        assertEquals(Metric.HEART_RATE, a.steps[2].target!!.metric)
        assertEquals(Verdict.IN, a.steps[2].verdict)
        val cd = a.steps.last()
        assertEquals(400.0, cd.actual!!.movingSec, 0.0)
        assertNull(a.summary.extra)
    }

    @Test
    fun rideShowsKmh() {
        val p = Target(Metric.POWER, 300.0, 316.0)
        val step = listOf(WorkoutStep("VO2", StepKind.ACTIVE, durationSec = 480, targets = listOf(p), primary = Metric.POWER))
        val a = PlanVsActual.analyze(step, Sport.RIDE, Synth().go(480, 10.0) { 305.0 }.build(power = true))
        assertEquals(Verdict.IN, a.steps[0].verdict)
        assertEquals("36.0 km/h", a.steps[0].actual!!.speedText(Sport.RIDE, PaceUnit.KM))
        assertEquals("22.4 mph", PlanVsActual.speedText(10.0, Sport.RIDE, PaceUnit.MILE))
        assertEquals(Verdict.LOW, PlanVsActual.analyze(step, Sport.RIDE, Synth().go(480, 10.0) { 280.0 }.build(power = true)).steps[0].verdict)
    }

    @Test
    fun watchRecordingRoundTrip() {
        val steps = intervals.take(3)
        val b = RecordingBuilder("Test", Sport.RUN, false, 0L)
        var d = 0.0
        val speeds = listOf(2.9 to 600, mps("3:52") to 180, 2.5 to 120)
        var t = 0.0
        speeds.forEachIndexed { idx, (v, len) ->
            repeat(len) {
                b.onStep(idx, steps[idx], t, d)
                t += 1; d += v
                b.add(RecSample(t, d, speed = v, hr = 150))
            }
        }
        val rec = b.build(t, d)
        val a = PlanVsActual.analyze(steps, Sport.RUN, ActivityStreams.from(rec), ActivityStreams.stepBoundaries(rec))
        assertNotNull(a.steps[1].verdict)
        assertEquals(Verdict.FAST, a.steps[1].verdict)
        assertEquals(3, a.summary.stepsCompleted)
    }

    // ---- review fixes ----

    /**
     * Drives [WorkoutEngine] and [RecordingBuilder] at 1 Hz like the watch's ExerciseService does. [closeAtPlanEnd]
     * false is the older watch, which left the last lap open until the recording ended.
     */
    private fun watchRun(steps: List<WorkoutStep>, secs: Int, speed: (Int) -> Double, closeAtPlanEnd: Boolean = true): Recording {
        val e = WorkoutEngine(Workout(1, "w", Sport.RUN, startLocal = "", steps = steps)).also { it.start() }
        val b = RecordingBuilder("w", Sport.RUN, false, T0, 1)
        var d = 0.0
        b.add(RecSample(0.0, 0.0))
        for (t in 0 until secs) {
            e.update(t.toDouble(), d)
            val cur = e.current
            if (cur != null) b.onStep(e.index, cur, t.toDouble(), d) else if (closeAtPlanEnd) b.finishPlan(t.toDouble(), d)
            val v = speed(t)
            d += v
            b.add(RecSample((t + 1).toDouble(), d, speed = v, hr = 150))
        }
        return b.build(secs.toDouble(), d)
    }

    private val T0 = 1_700_000_000_000L

    private fun roundTrip(rec: Recording) = FitReader.read(FitWriter.encode(rec))

    private val short3 = listOf(
        WorkoutStep("Warm up", StepKind.WARMUP, durationSec = 60),
        WorkoutStep("Work", StepKind.ACTIVE, durationSec = 120, targets = listOf(work), primary = Metric.PACE),
        WorkoutStep("Cool down", StepKind.COOLDOWN, durationSec = 60),
    )

    @Test
    fun postPlanTimeOnAWatchRecordingIsExtra() {
        for (closeAtPlanEnd in listOf(true, false)) {
            val rec = roundTrip(watchRun(short3, 300, { 3.0 }, closeAtPlanEnd))
            val a = PlanVsActual.analyze(short3, Sport.RUN, ActivityStreams.from(rec), ActivityStreams.stepBoundaries(rec))
            val msg = "closeAtPlanEnd=$closeAtPlanEnd"
            assertEquals(msg, Alignment.BOUNDARIES, a.summary.alignment)
            assertEquals(msg, 3, a.summary.stepsCompleted)
            assertEquals(msg, 60.0, a.steps[2].actual!!.movingSec, 1.0)
            assertEquals(msg, 60.0, a.summary.extra!!.movingSec, 1.0)
            assertEquals(msg, 240.0, a.summary.extra!!.startSec, 1.0)
        }
        // The new watch closes the last lap where the plan ended.
        assertEquals(240.0, watchRun(short3, 300, { 3.0 }).laps.last().endSec, 0.0)
    }

    @Test
    fun lapsCarryTheirStepIndexThroughFit() {
        val b = RecordingBuilder("w", Sport.RUN, false, T0)
        var d = 0.0
        b.add(RecSample(0.0, 0.0))
        for (t in 0..120) {
            // Step 1 lasted less than a tick: the engine went from 0 to 2 at t = 60.
            val idx = if (t < 60) 0 else 2
            b.onStep(idx, short3[idx], t.toDouble(), d)
            val v = if (idx == 0) 2.9 else 2.5
            if (t < 120) { d += v; b.add(RecSample(t + 1.0, d, speed = v)) }
        }
        val rec = b.build(120.0, d)
        assertEquals(listOf(0, 2), rec.laps.map { it.stepIndex })
        val back = roundTrip(rec)
        assertEquals(listOf(0, 2), back.laps.map { it.stepIndex })

        val bounds = ActivityStreams.stepBoundaries(back)
        assertEquals(listOf(0, 1, 2), bounds.map { it.stepIndex })
        assertEquals(bounds[1].startSec, bounds[1].endSec, 0.0)      // the skipped step is an empty span

        // Indexed, and positional after losing the index (as a store that keeps only start,end would).
        for (b2 in listOf(bounds, bounds.map { TimeSpan(it.startSec, it.endSec) })) {
            val a = PlanVsActual.analyze(short3, Sport.RUN, ActivityStreams.from(back), b2)
            assertEquals(Alignment.BOUNDARIES, a.summary.alignment)
            assertEquals(StepStatus.NOT_REACHED, a.steps[1].status)
            assertEquals(60.0, a.steps[2].actual!!.movingSec, 0.0)
            assertEquals(2.5, a.steps[2].actual!!.avgSpeedMps!!, 1e-9)
            assertEquals(StepStatus.COMPLETE, a.steps[2].status)
        }
    }

    @Test
    fun olderFileWithoutStepIndexReadsAndFallsBackOnKindMismatch() {
        // An older recording (no step index) that lost step 1's lap: lap kinds no longer line up with the steps.
        val rec = Recording(
            "w", Sport.RUN, false, T0,
            samples = (0..120).map { RecSample(it.toDouble(), it * 2.5) },
            laps = listOf(RecLap(0.0, 60.0, 0.0, 150.0, StepKind.WARMUP), RecLap(60.0, 120.0, 150.0, 300.0, StepKind.COOLDOWN)),
            pauses = emptyList(), elapsedSec = 120.0, totalM = 300.0,
        )
        val back = roundTrip(rec)
        assertEquals(listOf(null, null), back.laps.map { it.stepIndex })
        val a = PlanVsActual.analyze(short3, Sport.RUN, ActivityStreams.from(back), ActivityStreams.stepBoundaries(back))
        assertEquals(Alignment.PLANNED, a.summary.alignment)
    }

    @Test
    fun indexedBoundariesWithWrongKindOrOrderAreIgnored() {
        val s = Synth().go(240, 3.0).build()
        val wrongKind = listOf(TimeSpan(0.0, 60.0, 0, StepKind.WARMUP), TimeSpan(60.0, 180.0, 1, StepKind.REST))
        assertEquals(Alignment.PLANNED, PlanVsActual.analyze(short3, Sport.RUN, s, wrongKind).summary.alignment)
        val outOfRange = listOf(TimeSpan(0.0, 60.0, 0), TimeSpan(60.0, 180.0, 5))
        assertEquals(Alignment.PLANNED, PlanVsActual.analyze(short3, Sport.RUN, s, outOfRange).summary.alignment)
        val backwards = listOf(TimeSpan(0.0, 60.0, 1), TimeSpan(60.0, 180.0, 0))
        assertEquals(Alignment.PLANNED, PlanVsActual.analyze(short3, Sport.RUN, s, backwards).summary.alignment)
        // Lap presses cut steps short on the watch: an indexed list is trusted on lengths.
        val lapped = listOf(TimeSpan(0.0, 20.0, 0), TimeSpan(20.0, 40.0, 1), TimeSpan(40.0, 240.0, 2))
        val a = PlanVsActual.analyze(short3, Sport.RUN, s, lapped)
        assertEquals(Alignment.BOUNDARIES, a.summary.alignment)
        assertEquals(StepStatus.PARTIAL, a.steps[1].status)
    }

    @Test
    fun emptyBoundariesMeanPlannedAlignment() {
        val a = PlanVsActual.analyze(intervals, Sport.RUN, intervalRun().build(), boundaries = emptyList())
        assertEquals(Alignment.PLANNED, a.summary.alignment)
        assertEquals(12, a.summary.stepsCompleted)
    }

    @Test
    fun intervalsThatDoNotMatchThePlanAreIgnored() {
        val s = intervalRun().build()
        // intervals.icu merged the warm-up with rep 1: everything after is one step off.
        val merged = spans(listOf(780, 120, 180, 120, 180, 120, 180, 120, 180, 120, 600))
        assertFalse(PlanVsActual.boundariesMatchPlan(intervals, s, merged))
        val a = PlanVsActual.analyze(intervals, Sport.RUN, s, merged)
        assertEquals(Alignment.PLANNED, a.summary.alignment)
        assertEquals(12, a.summary.stepsCompleted)
        assertEquals(listOf(Verdict.IN, Verdict.FAST, Verdict.IN, Verdict.SLOW, Verdict.IN), workSteps(a).map { it.verdict })

        // Far more intervals than steps (e.g. auto laps every minute).
        val perMinute = spans(List(45) { 60 })
        assertEquals(Alignment.PLANNED, PlanVsActual.analyze(intervals, Sport.RUN, s, perMinute).summary.alignment)

        // One interval over the whole run is not the warm-up.
        val whole = listOf(TimeSpan(0.0, 2700.0))
        assertEquals(Alignment.PLANNED, PlanVsActual.analyze(intervals, Sport.RUN, s, whole).summary.alignment)

        // Laps that match the plan are kept.
        assertTrue(PlanVsActual.boundariesMatchPlan(intervals, s, spans(intervals.map { it.durationSec!! })))
    }

    @Test
    fun partialRampIsJudgedAgainstThePartDone() {
        val ramp = Target(Metric.POWER, 100.0, 200.0, rampFrom = 100.0, rampTo = 200.0)
        val step = listOf(WorkoutStep("Ramp", StepKind.WARMUP, durationSec = 600, targets = listOf(ramp), primary = Metric.POWER))
        // Stopped halfway, having followed the ramp exactly: 100 → 150 W.
        val half = PlanVsActual.analyze(step, Sport.RIDE, Synth().go(300, 8.0) { i -> 100.0 + 100.0 * (i - 0.5) / 600 }.build(power = true)).steps[0]
        assertEquals(StepStatus.PARTIAL, half.status)
        assertEquals(100.0, half.timeInRange!!.pctIn, 1e-9)
        assertEquals(Verdict.IN, half.verdict)

        // A distance ramp moves with distance, not time: 500 m slow, then 500 m fast.
        val dStep = listOf(WorkoutStep("Ramp", StepKind.WARMUP, distanceM = 1000.0, targets = listOf(ramp), primary = Metric.POWER))
        val s = Synth().go(100, 5.0) { i -> 100.0 + 100.0 * (i - 0.5) * 5.0 / 1000 }
            .go(50, 10.0) { i -> 100.0 + 100.0 * (500 + (i - 0.5) * 10.0) / 1000 }
        val byDist = PlanVsActual.analyze(dStep, Sport.RIDE, s.build(power = true)).steps[0]
        assertEquals(100.0, byDist.timeInRange!!.pctIn, 1e-9)
    }

    @Test
    fun paceFromDistanceUsesMovingTime() {
        // 10 s samples, distance only; 5 s of the middle interval is paused (no distance gained then).
        val s = ActivityStreams(
            timeSec = doubleArrayOf(0.0, 10.0, 20.0, 30.0),
            distanceM = doubleArrayOf(0.0, 40.0, 60.0, 100.0),
            pauses = listOf(TimeSpan(12.0, 17.0)),
        )
        val step = listOf(WorkoutStep("Steady", StepKind.ACTIVE, durationSec = 25,
            targets = listOf(Target(Metric.PACE, 3.9, 4.1)), primary = Metric.PACE))
        val a = PlanVsActual.analyze(step, Sport.RUN, s).steps[0]
        assertEquals(25.0, a.actual!!.movingSec, 0.0)
        assertEquals(100.0, a.timeInRange!!.pctIn, 1e-9)
    }
}
