package net.mvexel.intervalswatch.shared

/**
 * How long a step will take, for drawing it in proportion (the workout ring on watch and phone).
 * Timed steps use their duration. Distance steps use distance ÷ the middle of their pace target, or a default speed
 * for the sport when there is no pace target. Lap-press steps count as [LAP_STEP_SEC].
 */
object StepLength {
    const val LAP_STEP_SEC = 120.0

    /** 6:00 /km, the default when neither a pace target nor a sport says otherwise. */
    private const val RUN_MPS = 1000.0 / 360.0
    private const val WALK_MPS = 1000.0 / 750.0
    private const val RIDE_MPS = 25.0 / 3.6

    fun estimatedSec(step: WorkoutStep, sport: Sport? = null): Double {
        step.durationSec?.takeIf { it > 0 }?.let { return it.toDouble() }
        val dist = step.distanceM?.takeIf { it > 0 } ?: return LAP_STEP_SEC
        return dist / (paceSpeed(step) ?: defaultSpeed(sport))
    }

    /** Middle of the pace target in m/s; a one-sided target uses its finite bound. */
    private fun paceSpeed(step: WorkoutStep): Double? {
        val t = step.target(Metric.PACE) ?: return null
        val bounds = listOf(t.low, t.high).filter { it.isFinite() && it > 0 }
        return bounds.takeIf { it.isNotEmpty() }?.average()
    }

    private fun defaultSpeed(sport: Sport?) = when (sport) {
        Sport.RIDE -> RIDE_MPS
        Sport.WALK -> WALK_MPS
        else -> RUN_MPS
    }
}
