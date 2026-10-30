package net.mvexel.intervalswatch.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class Sport { RUN, RIDE, WALK, OTHER }

/** Units: POWER = watts, HEART_RATE = bpm, PACE = metres/second, CADENCE = rpm (or spm). */
@Serializable
enum class Metric { POWER, HEART_RATE, PACE, CADENCE }

@Serializable
enum class StepKind { WARMUP, ACTIVE, REST, COOLDOWN }

@Serializable
enum class PaceUnit { KM, MILE }

@Serializable
data class Target(
    val metric: Metric,
    val low: Double,
    val high: Double,
    /** For ramps: target value at start and end of the step (low/high are min/max of these). */
    val rampFrom: Double? = null,
    val rampTo: Double? = null,
) {
    val isRamp: Boolean get() = rampFrom != null && rampTo != null

    /** Target window at [fraction] (0..1) through the step. */
    fun rangeAt(fraction: Double): ClosedFloatingPointRange<Double> {
        if (!isRamp) return low..high
        val f = fraction.coerceIn(0.0, 1.0)
        val mid = rampFrom!! + (rampTo!! - rampFrom) * f
        val tol = mid * RAMP_TOLERANCE
        return (mid - tol)..(mid + tol)
    }

    /** -1 below target, 0 in range, 1 above target. For PACE, "above" means too fast. */
    fun compare(value: Double, fraction: Double): Int {
        val r = rangeAt(fraction)
        return when {
            value < r.start -> -1
            value > r.endInclusive -> 1
            else -> 0
        }
    }

    companion object {
        const val RAMP_TOLERANCE = 0.025
    }
}

@Serializable
data class WorkoutStep(
    val label: String,
    val kind: StepKind,
    /** Null together with [distanceM] means the step ends on lap press. */
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val targets: List<Target> = emptyList(),
    /** Which target drives alerts. */
    val primary: Metric? = null,
    /** e.g. "2/5" when inside a repeat block. */
    val repeat: String? = null,
) {
    val endsOnLap: Boolean get() = durationSec == null && distanceM == null
    val primaryTarget: Target? get() = targets.firstOrNull { it.metric == primary } ?: targets.firstOrNull()
    fun target(metric: Metric): Target? = targets.firstOrNull { it.metric == metric }
}

@Serializable
data class Workout(
    val id: Long,
    val name: String,
    val sport: Sport,
    val indoor: Boolean = false,
    /** Local start date-time as given by intervals.icu, e.g. 2026-10-30T07:00:00. */
    val startLocal: String,
    val description: String? = null,
    val steps: List<WorkoutStep>,
    val totalDurationSec: Int? = null,
    val totalDistanceM: Double? = null,
    val paceUnit: PaceUnit = PaceUnit.KM,
) {
    val usesPower: Boolean get() = steps.any { s -> s.targets.any { it.metric == Metric.POWER } }
}

/** What the phone puts in the Wearable Data Layer. */
@Serializable
data class WorkoutBundle(val syncedAtMillis: Long, val workouts: List<Workout>)

object Sync {
    const val DATA_PATH = "/workouts"
    const val KEY_JSON = "json"
    const val KEY_SYNCED_AT = "syncedAt"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(bundle: WorkoutBundle): String = json.encodeToString(bundle)
    fun decode(text: String): WorkoutBundle = json.decodeFromString(text)
}
