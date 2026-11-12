package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class StepLengthTest {
    private fun mps(pace: String): Double { val (m, s) = pace.split(":").map { it.toInt() }; return 1000.0 / (m * 60 + s) }

    @Test fun timedStepUsesItsDuration() =
        assertEquals(300.0, StepLength.estimatedSec(WorkoutStep("Warm up", StepKind.WARMUP, durationSec = 300)), 1e-9)

    @Test fun distanceStepUsesTheMiddleOfItsPaceTarget() {
        val easy = WorkoutStep("Easy", StepKind.ACTIVE, distanceM = 3000.0,
            targets = listOf(Target(Metric.PACE, mps("6:00"), mps("5:00"))), primary = Metric.PACE)
        // mean speed of 6:00 and 5:00 /km
        assertEquals(3000.0 / ((mps("6:00") + mps("5:00")) / 2), StepLength.estimatedSec(easy), 1e-6)
    }

    /** The owner's "Easy 3 km + strides": a 3 km easy step must outweigh a 5 min warm-up, not be 2 min. */
    @Test fun distanceStepWithoutPaceTargetUsesTheSportsDefaultSpeed() {
        val step = WorkoutStep("Easy", StepKind.ACTIVE, distanceM = 3000.0)
        assertEquals(1080.0, StepLength.estimatedSec(step), 1e-6) // 6:00 /km
        assertEquals(3000.0 / (25 / 3.6), StepLength.estimatedSec(step, Sport.RIDE), 1e-6)
        assertEquals(2250.0, StepLength.estimatedSec(step, Sport.WALK), 1e-6) // 12:30 /km
    }

    @Test fun oneSidedPaceTargetUsesItsFiniteBound() {
        val step = WorkoutStep("Easy", StepKind.ACTIVE, distanceM = 1000.0,
            targets = listOf(Target(Metric.PACE, mps("5:00"), Double.POSITIVE_INFINITY)))
        assertEquals(300.0, StepLength.estimatedSec(step), 1e-6)
    }

    @Test fun lapStepIsNominal() =
        assertEquals(StepLength.LAP_STEP_SEC, StepLength.estimatedSec(WorkoutStep("Lap", StepKind.REST)), 1e-9)
}
