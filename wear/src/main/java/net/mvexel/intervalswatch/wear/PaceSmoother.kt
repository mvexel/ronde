package net.mvexel.intervalswatch.wear

/**
 * Rolling mean of raw speed over the last W seconds (RF01 FR10 a). Samples are held until the next one
 * arrives (at most 3 s), so batches delivered late land at their true times and zero speeds count. With less than W of
 * history it is the mean of what there is; with none, or nothing newer than [staleSec], null.
 */
internal class PaceSmoother(private val staleSec: Double = 15.0) {
    private val times = ArrayList<Double>()
    private val values = ArrayList<Double>()

    @Synchronized fun add(t: Double, v: Double) {
        if (times.isNotEmpty() && t < times.last()) return
        times += t; values += v
        var n = 0
        while (n < times.size && times[n] < t - KEEP_SEC) n++
        if (n > 1) { times.subList(0, n - 1).clear(); values.subList(0, n - 1).clear() }
    }

    @Synchronized fun clear() { times.clear(); values.clear() }

    /** Time-weighted mean over [now - windowSec, now], or null without fresh data. */
    @Synchronized fun at(now: Double, windowSec: Double): Double? {
        var last = times.size - 1
        while (last >= 0 && times[last] > now) last--
        if (last < 0 || now - times[last] > staleSec) return null
        val from = now - windowSec
        var weighted = 0.0
        var covered = 0.0
        for (i in last downTo 0) {
            // A sample covers at most HOLD_SEC: Health Services sends nothing while paused, so an old sample must not
            // stand in for the gap (it would drag the pace after a resume).
            val end = minOf(if (i == last) now else times[i + 1], times[i] + HOLD_SEC)
            val start = maxOf(times[i], from)
            if (end > start) { weighted += values[i] * (end - start); covered += end - start }
            if (times[i] <= from) break
        }
        return if (covered > 0.0) weighted / covered else values[last]
    }

    private companion object { const val KEEP_SEC = 120.0
        const val HOLD_SEC = 3.0 }
}
