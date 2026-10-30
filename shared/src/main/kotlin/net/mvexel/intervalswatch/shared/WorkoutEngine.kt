package net.mvexel.intervalswatch.shared

/**
 * Pure step sequencer. Feed it active time and total distance; it tells you when steps change.
 * Step boundaries carry over exactly (a step that overshoots by 1.5 s starts the next one
 * 1.5 s in), so time-based workouts don't drift when updates arrive late.
 */
class WorkoutEngine(val workout: Workout) {

    var index: Int = 0
        private set
    private var stepStartSec = 0.0
    private var stepStartM = 0.0

    val finished: Boolean get() = index >= workout.steps.size
    val current: WorkoutStep? get() = workout.steps.getOrNull(index)
    val next: WorkoutStep? get() = workout.steps.getOrNull(index + 1)

    fun start(activeSec: Double = 0.0, distanceM: Double = 0.0) {
        index = 0
        stepStartSec = activeSec
        stepStartM = distanceM
    }

    /** @return number of steps advanced (usually 0 or 1). */
    fun update(activeSec: Double, distanceM: Double): Int {
        var advanced = 0
        while (true) {
            val s = current ?: break
            val dur = s.durationSec
            val dist = s.distanceM
            when {
                dur != null && activeSec - stepStartSec >= dur -> {
                    index++; stepStartSec += dur; stepStartM = distanceM
                }
                dist != null && distanceM - stepStartM >= dist -> {
                    index++; stepStartM += dist; stepStartSec = activeSec
                }
                else -> break
            }
            advanced++
        }
        return advanced
    }

    /** Lap press: end the current step now. */
    fun lap(activeSec: Double, distanceM: Double) {
        if (finished) return
        index++
        stepStartSec = activeSec
        stepStartM = distanceM
    }

    fun progress(activeSec: Double, distanceM: Double): StepProgress {
        val s = current ?: return StepProgress(0.0, null, 0.0, null, 1.0)
        val elapsed = (activeSec - stepStartSec).coerceAtLeast(0.0)
        val covered = (distanceM - stepStartM).coerceAtLeast(0.0)
        val remSec = s.durationSec?.let { (it - elapsed).coerceAtLeast(0.0) }
        val remM = s.distanceM?.let { (it - covered).coerceAtLeast(0.0) }
        val fraction = when {
            s.durationSec != null -> elapsed / s.durationSec
            s.distanceM != null -> covered / s.distanceM
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        return StepProgress(elapsed, remSec, covered, remM, fraction)
    }
}

data class StepProgress(
    val elapsedSec: Double,
    val remainingSec: Double?,
    val coveredM: Double,
    val remainingM: Double?,
    val fraction: Double,
)
