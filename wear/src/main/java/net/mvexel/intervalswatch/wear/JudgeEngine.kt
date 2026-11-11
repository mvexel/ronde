package net.mvexel.intervalswatch.wear

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure Kotlin judge for the paced-run screen: v5-design.md section 7 ("Judge spec"), no Android dependencies.
 * One call to [JudgeEngine.step] per second. Every number is a constant that the first outdoor log may retune;
 * none of them is field-tested (the noise assumptions behind them are from judge-sim.py, never measured).
 *
 * Pace is in s/km throughout (smaller = faster). "Fast" side = beyond the fast edge (the runner should EASE),
 * "slow" side = beyond the slow edge (the runner should PUSH).
 */

enum class JudgeState { GPS_WAIT, SETTLING, WEAK, IN, NEAR, OUT, PAUSED, SHORT }

/** Candidate / alert side: [NONE], too fast ([FAST], ease) or too slow ([SLOW], push). */
enum class Side(val code: Char) { NONE('0'), FAST('F'), SLOW('S') }

/**
 * Target range in pace. [fastSKm] is the fast (lower s/km) edge, [slowSKm] the slow edge; a null edge means the
 * target is one-sided there: fast edge only = "slower than" (a cap on speed), slow edge only = "faster than" (a floor).
 */
data class PaceRange(val fastSKm: Double?, val slowSKm: Double?) {
    val twoSided: Boolean get() = fastSKm != null && slowSKm != null

    /** Width in s/km, null when one-sided. */
    val widthSKm: Double? get() = if (fastSKm != null && slowSKm != null) slowSKm - fastSKm else null

    /** Width in m/s, null when one-sided. */
    val widthMps: Double? get() = if (fastSKm != null && slowSKm != null) 1000.0 / fastSKm - 1000.0 / slowSKm else null

    companion object {
        /** From a speed target ([lowMps] slow edge, [highMps] fast edge); a non-positive or huge edge is no edge. */
        fun fromSpeeds(lowMps: Double, highMps: Double): PaceRange = PaceRange(
            fastSKm = if (highMps in 0.01..49.0) 1000.0 / highMps else null,
            slowSKm = if (lowMps >= 0.01) 1000.0 / lowMps else null,
        )
    }
}

/** The range snapped to the grid [g] (s/km); state, drawing and delta all use these edges, the printed text stays true. */
data class SnappedRange(val fastSKm: Double?, val slowSKm: Double?, val gridSKm: Double) {
    val twoSided: Boolean get() = fastSKm != null && slowSKm != null
}

object Grid {
    const val MIN = 2.5
    const val MAX = 5.0

    /** `g = clamp(width / 4, 2.5, 5)` s/km; one-sided targets use 5. */
    fun forRange(r: PaceRange): Double = r.widthSKm?.let { (it / 4.0).coerceIn(MIN, MAX) } ?: MAX

    fun snap(v: Double, g: Double): Double = Math.round(v / g) * g

    /** Edges snapped to the grid; a snapped width under one grid step is widened to one step (slow edge moves). */
    fun snapRange(r: PaceRange): SnappedRange {
        val g = forRange(r)
        var f = r.fastSKm?.let { snap(it, g) }
        var s = r.slowSKm?.let { snap(it, g) }
        if (f != null && s != null && s - f < g) s = f + g
        return SnappedRange(f, s, g)
    }
}

data class JudgeInput(
    /** Wall seconds, one call per second, increasing. */
    val tSec: Double,
    /** Raw 1 Hz speed in m/s, null when there is no sample this second. */
    val rawSpeed: Double?,
    val gpsFix: Boolean,
    val stepIdx: Int,
    /** Start of the current step on the [tSec] clock. */
    val stepStartSec: Double,
    /** Step length in seconds; null = unknown (the caller should estimate a distance or lap step). */
    val stepLenSec: Double?,
    /** Seconds left in the step, null when unknown. */
    val stepRemainingSec: Double?,
    val range: PaceRange?,
    val paused: Boolean,
    /** Display window setting W_d: 15, 20 or 30 s. */
    val displayWindowSec: Int = 30,
    /** False for back-filled seconds: the judge still judges but a buzz neither fires nor spends the budget. */
    val allowBuzz: Boolean = true,
)

data class JudgeOut(
    val state: JudgeState,
    /** Continuous display mean (s/km); never drawn directly. */
    val displaySKm: Double?,
    /** The grid value the digit and marker hold (3 s hold rule), unclamped. */
    val shownSKm: Double?,
    /** [shownSKm] clamped to the edge when judged IN and at most one grid step past it. */
    val drawnSKm: Double?,
    val judgeMeanSKm: Double?,
    val judgeWinSec: Int?,
    val sigmaHat: Double?,
    val marginSKm: Double,
    val candidate: Side,
    val fastPath: Boolean,
    val dwellSec: Int,
    /** Side of the OUT state, or of NEAR; NONE otherwise. */
    val side: Side,
    /** 1..3 while OUT (up at once, down after 5 s), else 0. */
    val severity: Int,
    /** Seconds past the nearest snapped edge, 0 inside. */
    val beyondSKm: Double,
    val range: SnappedRange?,
    val alertAgeSec: Int,
    /** Non-NONE on the second a buzz fires: the side to buzz for (FAST = ease, SLOW = push). */
    val buzz: Side,
    val buzzCount5Min: Int,
    val stepAgeSec: Double,
    /** The step this output belongs to: a page showing another step must ignore it. */
    val stepIdx: Int = 0,
) {
    val out: Boolean get() = state == JudgeState.OUT
    val near: Boolean get() = state == JudgeState.NEAR
}

/**
 * When a buzz may fire (v5 section 7): repeats at least 30 s apart, at most 2 per 5 min and then silent until OUT has ended for
 * 30 s, and armed only after 5 s of IN since OUT last ended (so a flicker never buzzes). Pure, one [tick] per second.
 */
internal class BuzzGovernor {
    private val times = ArrayList<Double>()
    private var last = -1e9
    private var armed = true
    private var locked = false
    private var inRun = 0
    private var wasAlert = false

    val countLast5Min: Int get() = times.size
    val isArmed: Boolean get() = armed

    fun tick(t: Double, alert: Boolean) {
        if (wasAlert && !alert) armed = false
        wasAlert = alert
        if (alert) inRun = 0 else inRun++
        if (inRun >= JudgeEngine.REARM_S) armed = true
        if (inRun >= JudgeEngine.BUDGET_RESET_S) locked = false
        times.removeAll { t - it >= 300.0 }
    }

    /** The conjunction of the other buzz conditions holds this second: true when the buzz fires (and is counted). */
    fun tryFire(t: Double): Boolean {
        if (!armed || t - last < JudgeEngine.BUZZ_GAP_S) return false
        if (locked || times.size >= JudgeEngine.MAX_BUZZ_5MIN) { locked = true; return false }
        times += t; last = t
        return true
    }

    /** New step or resume: armed again, the budget is kept. */
    fun disarmReset() { armed = true; inRun = 0; wasAlert = false }
}

class JudgeEngine {
    private class Sample(val t: Double, val v: Double?)

    private val samples = ArrayDeque<Sample>()
    private val sigmaHist = ArrayDeque<Double>()

    private var stepIdx = Int.MIN_VALUE
    private var anchor = 0.0
    private var wasPaused = false

    // display grid
    private var shown: Double? = null
    private var gridCand: Double? = null
    private var gridRun = 0

    // weak GPS
    private var weak = false
    private var weakRun = 0
    private var strongRun = 0

    // dwell / alert
    private var candRun = 0
    private var offRun = 0
    private var alert = false
    private var alertSince = 0.0
    private var alertSide = Side.NONE
    private var severity = 0
    private var sevBelowRun = 0

    // buzz
    private val governor = BuzzGovernor()

    private var last: JudgeOut? = null

    fun step(i: JudgeInput): JudgeOut {
        val t = i.tSec
        if (i.stepIdx != stepIdx) { stepIdx = i.stepIdx; anchor = i.stepStartSec; resetEpisode(); last = null }
        if (i.paused) {
            wasPaused = true
            val base = last
            return (base ?: blank(i)).copy(state = JudgeState.PAUSED, buzz = Side.NONE, stepAgeSec = t - anchor, stepIdx = i.stepIdx).also { last = it }
        }
        if (wasPaused) { wasPaused = false; anchor = t; resetEpisode() }

        samples.addLast(Sample(t, i.rawSpeed))
        while (samples.size > KEEP) samples.removeFirst()
        val age = t - anchor
        val len = i.stepLenSec
        val range = i.range
        val snapped = range?.let { Grid.snapRange(it) }
        val g = snapped?.gridSKm ?: Grid.MAX

        // Display value
        val nDisp = min(i.displayWindowSec.toDouble(), max(6.0, age - 3.0)).toInt()
        val dMean = meanSpeed(t, nDisp)
        val dPace = dMean?.let { 1000.0 / max(it, 0.1) }
        // Noise estimate
        val sg = sigmaRaw(t)
        if (sg != null) { sigmaHist.addLast(sg); while (sigmaHist.size > 15) sigmaHist.removeFirst() }
        val sigma = if (sigmaHist.isEmpty()) null else median(sigmaHist)
        if (sigma != null) {
            if (sigma >= WEAK_HI) { weakRun++; strongRun = 0 }
            else if (sigma < WEAK_LO) { strongRun++; weakRun = 0 }
            else weakRun = 0
            if (!weak && weakRun >= WEAK_ENTER_S) weak = true
            if (weak && strongRun >= WEAK_EXIT_S) weak = false
        }
        // While WEAK the marker stays on its last slot; after it ends the usual 3 s hold applies.
        if (!weak || shown == null) updateShown(dPace, g)
        val m = if (sigma != null && sigma >= SIGMA_MARGIN) MARGIN_HI else MARGIN_LO

        // Candidate
        val short = len != null && len < MIN_JUDGED_LEN
        val wj = len?.let { judgeWindow(it) } ?: judgeWindow(LONG_UNKNOWN_LEN)
        val minAge = if (len != null && len <= 150.0) 15.0 else 20.0
        val sh = shown
        var candidate = Side.NONE
        var fast = false
        var lMean: Double? = null
        var lWin: Int? = null
        if (wj != null && !short && age >= minAge && !weak && i.gpsFix && snapped != null && sh != null) {
            lWin = min(wj.toDouble(), max(6.0, age - 3.0)).toInt()
            val lm = meanSpeed(t, lWin)?.let { 1000.0 / max(it, 0.1) }
            lMean = lm
            val shSide = sideOf(sh, snapped)
            if (lm != null) {
                val lmSide = when {
                    snapped.fastSKm != null && lm <= snapped.fastSKm - m -> Side.FAST
                    snapped.slowSKm != null && lm >= snapped.slowSKm + m -> Side.SLOW
                    else -> Side.NONE
                }
                if (lmSide != Side.NONE && lmSide == shSide) candidate = lmSide
            }
            if (shSide != Side.NONE) {
                val edge = if (shSide == Side.FAST) snapped.fastSKm!! else snapped.slowSKm!!
                if (abs(sh - edge) >= fastPathThreshold(len ?: LONG_UNKNOWN_LEN, m, range?.widthSKm)) { candidate = shSide; fast = true }
            }
        }

        // Dwell and OUT
        if (candidate != Side.NONE) {
            candRun++; offRun = 0
            if (!alert && candRun >= DWELL_S) { alert = true; alertSince = t; severity = 0; sevBelowRun = 0 }
            if (alert) alertSide = candidate
        } else {
            candRun = 0
            if (alert) {
                offRun++
                if (offRun >= OUT_END_S) {
                    alert = false; alertSide = Side.NONE; severity = 0; sevBelowRun = 0
                }
            }
        }
        governor.tick(t, alert)

        // Beyond / near / severity
        var beyond = 0.0
        var side = Side.NONE
        if (sh != null && snapped != null) {
            side = sideOf(sh, snapped)
            if (side != Side.NONE) beyond = abs(sh - (if (side == Side.FAST) snapped.fastSKm!! else snapped.slowSKm!!))
        }
        if (alert) {
            val tier = max(1, tierOf(beyond))
            if (severity == 0 || tier > severity) { severity = tier; sevBelowRun = 0 }
            else if (tier < severity) { sevBelowRun++; if (sevBelowRun >= SEV_DOWN_S) { severity = tier; sevBelowRun = 0 } }
            else sevBelowRun = 0
        }

        // State, precedence PAUSED > GPS_WAIT > (SHORT) > WEAK > SETTLING > OUT > NEAR > IN
        val state = when {
            !i.gpsFix -> JudgeState.GPS_WAIT
            short -> JudgeState.SHORT
            weak -> JudgeState.WEAK
            age < SETTLING_S -> JudgeState.SETTLING
            alert && !weak -> JudgeState.OUT
            side != Side.NONE && beyond > g -> JudgeState.NEAR
            else -> JudgeState.IN
        }
        val outSide = if (state == JudgeState.OUT) alertSide else if (state == JudgeState.NEAR) side else Side.NONE
        val clamp = (state == JudgeState.IN) && side != Side.NONE && beyond > 0.0 && beyond <= g
        val drawn = if (clamp && sh != null && snapped != null) (if (side == Side.FAST) snapped.fastSKm else snapped.slowSKm) else sh

        // Buzz
        var buzz = Side.NONE
        if (i.allowBuzz && state == JudgeState.OUT && candidate != Side.NONE && !weak && age >= SETTLING_S &&
            (len == null || len >= MIN_JUDGED_LEN) && t - alertSince >= BUZZ_AFTER_S &&
            (i.stepRemainingSec == null || i.stepRemainingSec > 5.0) &&
            (range?.widthMps.let { it == null || it >= MIN_BUZZ_WIDTH_MPS }) &&
            governor.tryFire(t)
        ) buzz = candidate

        val out = JudgeOut(
            state = state, displaySKm = dPace, shownSKm = sh, drawnSKm = drawn,
            judgeMeanSKm = lMean, judgeWinSec = lWin, sigmaHat = sigma, marginSKm = m,
            candidate = candidate, fastPath = fast, dwellSec = candRun, side = outSide,
            severity = if (state == JudgeState.OUT) severity else 0,
            beyondSKm = beyond, range = snapped,
            alertAgeSec = if (alert) (t - alertSince).toInt() else 0,
            buzz = buzz, buzzCount5Min = governor.countLast5Min, stepAgeSec = age, stepIdx = i.stepIdx,
        )
        last = out
        return out
    }

    /** Step start or resume: SETTLING again; the raw series is kept (wake from ambient never calls this). */
    private fun resetEpisode() {
        candRun = 0; offRun = 0; alert = false; alertSide = Side.NONE; severity = 0; sevBelowRun = 0
        governor.disarmReset()
        // The noise estimate, WEAK and the marker slot belong to one step: a new step starts them afresh (SETTLING again).
        sigmaHist.clear(); weak = false; weakRun = 0; strongRun = 0
        shown = null; gridCand = null; gridRun = 0
    }

    private fun blank(i: JudgeInput) = JudgeOut(
        JudgeState.PAUSED, null, null, null, null, null, null, MARGIN_LO, Side.NONE, false, 0, Side.NONE, 0, 0.0,
        i.range?.let { Grid.snapRange(it) }, 0, Side.NONE, 0, 0.0,
    )

    /** Grid rule: moves only after 3 s on the same new grid value, or at once on a 15 s/km jump. */
    private fun updateShown(dPace: Double?, g: Double) {
        if (dPace == null) return
        val cur = shown
        if (cur == null) { shown = Grid.snap(dPace, g); return }
        val c = Grid.snap(dPace, g)
        if (abs(dPace - cur) >= g && c != cur) {
            if (c == gridCand) gridRun++ else { gridCand = c; gridRun = 1 }
            if (gridRun >= HOLD_S || abs(dPace - cur) >= SKIP_SKM) { shown = c; gridRun = 0; gridCand = null }
        } else { gridCand = null; gridRun = 0 }
    }

    private fun meanSpeed(t: Double, n: Int): Double? {
        var sum = 0.0
        var c = 0
        for (k in samples.indices.reversed()) {
            val s = samples[k]
            if (s.t <= t - n) break
            s.v?.let { sum += it; c++ }
        }
        return if (c > 0) sum / c else null
    }

    /**
     * Detrended sd of raw speed over the last 30 s, but only over seconds inside the current step (since [anchor]),
     * in s/km at the mean speed of those same samples (floored at 1 m/s); null with fewer than 10 samples.
     * Reading across a step boundary would see the speed change as noise (a walking rest before a run read as WEAK).
     */
    private fun sigmaRaw(t: Double): Double? {
        val xs = ArrayList<Double>()
        for (k in samples.indices.reversed()) {
            val s = samples[k]
            if (s.t <= t - 30.0 || s.t < anchor) break
            s.v?.let { xs += it }
        }
        if (xs.size < 10) return null
        xs.reverse()
        val n = xs.size
        val km = (n - 1) / 2.0
        val xm = xs.average()
        var sxy = 0.0
        var sxx = 0.0
        for (k in 0 until n) { sxy += (k - km) * (xs[k] - xm); sxx += (k - km) * (k - km) }
        val slope = sxy / sxx
        var ss = 0.0
        for (k in 0 until n) { val r = xs[k] - (xm + slope * (k - km)); ss += r * r }
        val vref = max(xm, 1.0)
        return 1000.0 / (vref * vref) * sqrt(ss / n)
    }

    companion object {
        private const val KEEP = 120
        const val HOLD_S = 3
        const val SKIP_SKM = 15.0
        const val DWELL_S = 5
        const val OUT_END_S = 2
        const val SETTLING_S = 15.0
        const val MIN_JUDGED_LEN = 60.0
        const val LONG_UNKNOWN_LEN = 1200.0
        const val WEAK_HI = 36.0
        const val WEAK_LO = 32.0
        const val WEAK_ENTER_S = 6
        const val WEAK_EXIT_S = 10
        const val SIGMA_MARGIN = 18.0
        const val MARGIN_LO = 5.0
        const val MARGIN_HI = 10.0
        const val BUZZ_AFTER_S = 10.0
        const val BUZZ_GAP_S = 30.0
        const val MAX_BUZZ_5MIN = 2
        const val BUDGET_RESET_S = 30
        const val REARM_S = 5
        const val SEV_DOWN_S = 5
        const val MIN_BUZZ_WIDTH_MPS = 0.10

        /** Judge window by step length: none under 60 s, 20 / 30 / 45 / 60 s. */
        fun judgeWindow(stepLenSec: Double): Int? = when {
            stepLenSec < 60.0 -> null
            stepLenSec < 150.0 -> 20
            stepLenSec < 300.0 -> 30
            stepLenSec <= 900.0 -> 45
            else -> 60
        }

        /** Display-only distance past an edge that counts without the judge mean (spec: fast path). */
        fun fastPathThreshold(stepLenSec: Double, margin: Double, widthSKm: Double?): Double {
            if (widthSKm != null && widthSKm < 10.0) return 10.0
            return if (margin >= MARGIN_HI || stepLenSec > 300.0) 15.0 else 10.0
        }

        /** Severity tier from seconds past the edge: s1 5-9, s2 10-14, s3 15+, 0 below 5. */
        fun tierOf(beyondSKm: Double): Int = when {
            beyondSKm >= 15.0 -> 3
            beyondSKm >= 10.0 -> 2
            beyondSKm >= 5.0 -> 1
            else -> 0
        }

        fun sideOf(pace: Double, r: SnappedRange): Side = when {
            r.fastSKm != null && pace < r.fastSKm -> Side.FAST
            r.slowSKm != null && pace > r.slowSKm -> Side.SLOW
            else -> Side.NONE
        }

        private fun median(xs: Collection<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
