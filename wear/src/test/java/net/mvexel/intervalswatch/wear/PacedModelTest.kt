package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.WorkoutStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacedModelTest {
    private val range = PaceRange.fromSpeeds(1000.0 / 300.0, 1000.0 / 280.0)

    @Test fun cueDeltaIsUnsignedWholeSecondsCappedAt99() {
        assertEquals("25", deltaText(25.0, PaceUnit.KM))
        assertEquals("99", deltaText(99.0, PaceUnit.KM))
        assertEquals("99+", deltaText(99.6, PaceUnit.KM))
        assertEquals("99+", deltaText(240.0, PaceUnit.KM))
        assertEquals("s/km", deltaUnit(PaceUnit.KM)); assertEquals("s/mi", deltaUnit(PaceUnit.MILE))
        assertEquals("40", deltaText(25.0, PaceUnit.MILE)) // 25 s/km = 40.2 s/mi
    }

    @Test fun rangeTextIsSlowToFastWithAnEnDashAndNoUnit() {
        assertEquals("5:00–4:40", rangeText(range, PaceUnit.KM))
        assertNull(rangeText(PaceRange(330.0, null), PaceUnit.KM))
        assertEquals("SLOWER" to "5:30", oneSidedText(PaceRange(330.0, null), PaceUnit.KM))
        assertEquals("FASTER" to "6:00", oneSidedText(PaceRange(null, 360.0), PaceUnit.KM))
        assertNull(oneSidedText(range, PaceUnit.KM))
    }

    @Test fun stepLabelDropsTheCounterThenTruncates() {
        fun step(label: String, rep: String?) = WorkoutStep(label, StepKind.ACTIVE, durationSec = 60, repeat = rep)
        assertEquals("2/3 TEMPO", stepLabelText(step("Tempo", "2/3")))
        assertEquals("THRESHOLD", stepLabelText(step("Threshold", "2/3")))
        assertEquals("EASY", stepLabelText(step("Easy", null)))
        assertEquals("LONG PROGR…", stepLabelText(step("Long progression", null)))
        assertTrue(stepLabelText(step("Long progression run", "10/12")).length <= 11)
    }

    @Test fun talkBackAlwaysSpeaksTheVerdictInWords() {
        assertEquals("Too fast by 25 seconds per kilometre", verdictSpeech(Side.FAST, 25.0, PaceUnit.KM))
        assertEquals("Too slow by 16 seconds per mile", verdictSpeech(Side.SLOW, 10.0, PaceUnit.MILE))
        assertEquals("Too fast by 99 or more seconds per kilometre", verdictSpeech(Side.FAST, 150.0, PaceUnit.KM))
        assertNull(verdictSpeech(Side.NONE, 0.0, PaceUnit.KM))
        assertEquals("Weak GPS", pacedSpeech(JudgeState.WEAK, Side.NONE, 0.0, PaceUnit.KM))
        assertEquals("Settling", pacedSpeech(JudgeState.SETTLING, Side.NONE, 0.0, PaceUnit.KM))
    }

    @Test fun restVerdictIsOneValueAgainstTheSnappedBandWithNoDwell() {
        assertEquals(Side.NONE, restVerdict(range, 286.0).first)
        assertEquals(Side.FAST to 5.0, restVerdict(range, 274.0))
        assertEquals(Side.SLOW to 10.0, restVerdict(range, 311.0))
    }

    @Test fun easeAndPushBuzzesDifferInKindAndFollowTheJudgedSide() {
        val ease = judgeBuzzPattern(Side.FAST)!!
        val push = judgeBuzzPattern(Side.SLOW)!!
        assertNull(judgeBuzzPattern(Side.NONE))
        fun pulses(p: LongArray) = p.drop(1).filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals("EASE is one long pulse", 1, pulses(ease).size)
        assertTrue(pulses(ease).single() >= 300)
        assertTrue("PUSH is a flutter of short pulses", pulses(push).size >= 3 && pulses(push).all { it <= 100 })
        assertNotEquals(ease.toList(), push.toList())
        // neither is the step-change pattern
        assertNotEquals(Haptics.GO.toList(), push.toList()); assertNotEquals(Haptics.EASE.toList(), ease.toList())
    }

    @Test fun burnInIsARandomWalkWithinThreeDp() {
        var o = 0
        val seen = HashSet<Int>()
        val rng = java.util.Random(5)
        for (r in 0 until 500) { val n = nextBurnInOffset(o, rng.nextInt(1000)); assertTrue(kotlin.math.abs(n - o) <= 2); assertTrue(n in -3..3); seen += n; o = n }
        assertTrue(seen.size > 3)
    }

    @Test fun pacedStepNeedsAPaceRangeAndNoLapEnd() {
        val run = Samples.run
        fun s(step: WorkoutStep?, w: net.mvexel.intervalswatch.shared.Workout = run) = Session(
            w, 0, step, null, StepProgress(10.0, 100.0, 0.0, null, 0.1), 0.0, 0.0, null, null, null, null, null, true, false, false, false,
        )
        assertTrue(isPacedStep(s(run.steps[1])))
        assertFalse(isPacedStep(s(run.steps[2]))) // lap-ended step: old page with the Lap button
        assertFalse(isPacedStep(s(WorkoutStep("HR", StepKind.ACTIVE, durationSec = 60, targets = listOf(Target(Metric.HEART_RATE, 150.0, 160.0)), primary = Metric.HEART_RATE))))
        assertFalse(isPacedStep(s(Samples.ride.steps[1], Samples.ride)))
    }
}
