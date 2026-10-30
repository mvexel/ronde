package net.mvexel.intervalswatch.shared

import kotlin.math.roundToInt

object Format {
    fun duration(seconds: Double): String {
        val t = seconds.roundToInt().coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun distance(meters: Double, unit: PaceUnit): String = when (unit) {
        PaceUnit.KM -> if (meters < 1000) "${meters.roundToInt()} m" else "%.2f km".format(meters / 1000)
        PaceUnit.MILE -> "%.2f mi".format(meters / 1609.344)
    }

    /** m/s -> "4:35" (per km or mile). */
    fun pace(mps: Double, unit: PaceUnit): String {
        if (mps <= 0.1) return "--:--"
        val per = if (unit == PaceUnit.KM) 1000.0 else 1609.344
        return duration(per / mps)
    }

    fun paceSuffix(unit: PaceUnit) = if (unit == PaceUnit.KM) "/km" else "/mi"

    fun value(metric: Metric, v: Double, unit: PaceUnit): String = when (metric) {
        Metric.POWER -> "${v.roundToInt()}"
        Metric.HEART_RATE -> "${v.roundToInt()}"
        Metric.CADENCE -> "${v.roundToInt()}"
        Metric.PACE -> pace(v, unit)
    }

    fun unitLabel(metric: Metric, unit: PaceUnit): String = when (metric) {
        Metric.POWER -> "W"
        Metric.HEART_RATE -> "bpm"
        Metric.CADENCE -> "rpm"
        Metric.PACE -> paceSuffix(unit)
    }

    /** Pace ranges are shown slow–fast, i.e. high m/s last. */
    fun target(t: Target, fraction: Double, unit: PaceUnit): String {
        val r = t.rangeAt(fraction)
        val a = value(t.metric, r.start, unit)
        val b = value(t.metric, r.endInclusive, unit)
        val range = if (a == b) a else "$a–$b"
        return "$range ${unitLabel(t.metric, unit)}"
    }
}
