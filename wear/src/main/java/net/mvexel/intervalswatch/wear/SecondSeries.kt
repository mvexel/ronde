package net.mvexel.intervalswatch.wear

import kotlin.math.floor

/**
 * Raw speed per wall second, keyed by each Health Services point's own timestamp, so a batch delivered late (screen off) lands in
 * its true seconds. Several points in one second are averaged; a second with none has no value (it is not carried forward, so the
 * log shows the truth). Pure and thread-safe.
 */
internal class SecondSeries(private val keepSec: Int = 180) {
    private val sums = HashMap<Long, DoubleArray>()
    private var newest = Long.MIN_VALUE

    @Synchronized fun add(wallSec: Double, v: Double) {
        val s = floor(wallSec).toLong()
        val e = sums.getOrPut(s) { DoubleArray(2) }
        e[0] += v; e[1] += 1.0
        if (s > newest) {
            newest = s
            if (sums.size > keepSec * 2) sums.keys.removeAll { it < newest - keepSec }
        }
    }

    /** Mean of the points that fall in [sec], or null. */
    @Synchronized fun at(sec: Long): Double? = sums[sec]?.let { it[0] / it[1] }

    @Synchronized fun clear() { sums.clear(); newest = Long.MIN_VALUE }
}
