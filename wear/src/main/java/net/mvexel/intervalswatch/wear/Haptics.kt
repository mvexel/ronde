package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.StepKind

/**
 * The haptic vocabulary, as waveform patterns (off, on, off, on, ... in ms). Long pulses mean a step
 * change, short pulses mean "look at your target". Listed in the README; keep both in step.
 */
object Haptics {
    /** Into a work step: three strong short pulses, "go". */
    val GO = longArrayOf(0, 150, 100, 150, 100, 150)

    /** Into recovery, warm-up or cool-down: one long pulse, "ease". */
    val EASE = longArrayOf(0, 600)

    /** Countdown: three 130 ms pulses, one per second. */
    val COUNTDOWN = longArrayOf(0, 130, 870, 130, 870, 130)

    /** Distance step, 100 m to go: one pulse. */
    val DISTANCE_100 = longArrayOf(0, 130)

    /** Too low or too slow: two quick. */
    val TOO_LOW = longArrayOf(0, 100, 100, 100)

    /** Too high or too fast: three quick. */
    val TOO_HIGH = longArrayOf(0, 100, 100, 100, 100, 100)

    /** Sensor lost: two medium pulses; regained: one short. */
    val SENSOR_LOST = longArrayOf(0, 250, 150, 250)
    val SENSOR_BACK = longArrayOf(0, 120)

    /** Crown page change: one light tick. */
    val PAGE_TICK = longArrayOf(0, 25)

    val DONE = longArrayOf(0, 300, 150, 300, 150, 300)

    fun stepChange(into: StepKind?) = if (into == StepKind.ACTIVE) GO else EASE

    fun rangeAlert(cmp: Int) = if (cmp < 0) TOO_LOW else TOO_HIGH

    /** Metres from the end of a distance step at which the 3-pulse countdown starts, from current speed. */
    fun countdownMeters(speedMs: Double?): Double? = speedMs?.takeIf { it > 0.0 }?.let { it * 3.0 }
}
