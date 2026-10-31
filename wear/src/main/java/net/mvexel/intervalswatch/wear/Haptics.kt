package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.BuzzStrength
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

    /** Too low or too slow ("speed up"): a flutter of five very short pulses. */
    val TOO_LOW = longArrayOf(0, 60, 60, 60, 60, 60, 60, 60, 60, 60)

    /** Too high or too fast ("ease off"): one medium-long pulse. */
    val TOO_HIGH = longArrayOf(0, 350)

    /** Sensor lost: two medium pulses; regained: one short. */
    val SENSOR_LOST = longArrayOf(0, 250, 150, 250)
    val SENSOR_BACK = longArrayOf(0, 120)

    /** Crown page change: one light tick. */
    val PAGE_TICK = longArrayOf(0, 25)

    val DONE = longArrayOf(0, 300, 150, 300, 150, 300)

    /** Strong = pulses 1.5x as long (gaps unchanged), at full amplitude; Normal is as listed. */
    fun scaled(pattern: LongArray, strength: BuzzStrength): LongArray =
        if (strength == BuzzStrength.STRONG) LongArray(pattern.size) { if (it % 2 == 1) (pattern[it] * 1.5).toLong() else pattern[it] } else pattern

    /** Per-entry amplitudes for [pattern]: 0 in the gaps, 255 (Strong) or 160 (Normal) in pulses. */
    fun amplitudes(pattern: LongArray, strength: BuzzStrength): IntArray =
        IntArray(pattern.size) { if (it % 2 == 1) (if (strength == BuzzStrength.STRONG) 255 else 160) else 0 }

    fun stepChange(into: StepKind?) = if (into == StepKind.ACTIVE) GO else EASE

    fun rangeAlert(cmp: Int) = if (cmp < 0) TOO_LOW else TOO_HIGH

    /** Metres from the end of a distance step at which the 3-pulse countdown starts, from current speed. */
    fun countdownMeters(speedMs: Double?): Double? = speedMs?.takeIf { it > 0.0 }?.let { it * 3.0 }
}
