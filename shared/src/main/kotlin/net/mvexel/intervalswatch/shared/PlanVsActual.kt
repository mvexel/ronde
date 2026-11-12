package net.mvexel.intervalswatch.shared

/**
 * A span of wall-clock seconds since the activity started. As a step boundary it may say which plan step it is
 * ([stepIndex], 0-based) and what kind of step the recorder saw ([kind]); both are null when unknown (pauses,
 * intervals.icu intervals, boundaries stored without them).
 */
data class TimeSpan(val startSec: Double, val endSec: Double, val stepIndex: Int? = null, val kind: StepKind? = null)

/**
 * An activity's streams as parallel arrays, one entry per sample. [timeSec] is wall-clock seconds since start
 * and should not decrease. Any other stream may be null (not recorded); a missing value inside a stream is NaN.
 *
 * Sample i stands for the interval (timeSec[i-1], timeSec[i]]; the first sample only marks the start.
 * [moving] false at i (e.g. intervals.icu's `moving` stream) and any overlap with [pauses] take that interval
 * out of moving time.
 */
class ActivityStreams(
    val timeSec: DoubleArray,
    val velocityMps: DoubleArray? = null,
    val heartrate: DoubleArray? = null,
    val watts: DoubleArray? = null,
    val cadence: DoubleArray? = null,
    val distanceM: DoubleArray? = null,
    val moving: BooleanArray? = null,
    val pauses: List<TimeSpan> = emptyList(),
) {
    val size: Int get() = timeSec.size

    init {
        for (s in listOf(velocityMps, heartrate, watts, cadence, distanceM)) {
            require(s == null || s.size == size) { "streams must have the same length as timeSec" }
        }
        require(moving == null || moving.size == size) { "moving must have the same length as timeSec" }
    }

    companion object {
        /** The watch's own recording: samples, pauses. Use [stepBoundaries] for its laps. */
        fun from(rec: Recording): ActivityStreams {
            val s = rec.samples
            fun col(f: (RecSample) -> Number?) = DoubleArray(s.size) { f(s[it])?.toDouble() ?: Double.NaN }
            return ActivityStreams(
                timeSec = DoubleArray(s.size) { s[it].t },
                velocityMps = col { it.speed }.takeIf { a -> a.any { !it.isNaN() } },
                heartrate = col { it.hr }.takeIf { a -> a.any { !it.isNaN() } },
                watts = col { it.power }.takeIf { a -> a.any { !it.isNaN() } },
                cadence = col { it.cadence }.takeIf { a -> a.any { !it.isNaN() } },
                distanceM = col { it.distanceM },
                pauses = rec.pauses.map { TimeSpan(it.startSec, it.endSec) },
            )
        }

        /**
         * The watch's laps as step boundaries. The watch writes one lap per step, in step order, and (since laps
         * carry it) the step index: then entry k is step k, and a step with no lap of its own (skipped within one
         * tick, or a dropped zero-length lap) is an empty span where the next lap starts, so the list still lines
         * up with the steps when only start and end are kept. Laps without a step index are passed in file order.
         */
        fun stepBoundaries(rec: Recording): List<TimeSpan> {
            if (rec.laps.any { it.stepIndex == null }) return rec.laps.map { TimeSpan(it.startSec, it.endSec, kind = it.kind) }
            val out = ArrayList<TimeSpan>()
            for (l in rec.laps) {
                val k = l.stepIndex!!
                while (out.size < k) out += TimeSpan(l.startSec, l.startSec, out.size)
                out += TimeSpan(l.startSec, l.endSec, k, l.kind)
            }
            return out
        }
    }
}

/**
 * How far off an average may be and still count as [Verdict.IN]: the target range is widened by this much on
 * each side. Pace and power are relative to the bound (1.5 % of 4:15/km is about 4 s/km), HR and cadence absolute.
 */
data class Tolerance(
    val paceFraction: Double = 0.015,
    val powerFraction: Double = 0.03,
    val hrBpm: Double = 2.0,
    val cadence: Double = 2.0,
) {
    fun of(metric: Metric, bound: Double): Double = when (metric) {
        Metric.PACE -> bound * paceFraction
        Metric.POWER -> bound * powerFraction
        Metric.HEART_RATE -> hrBpm
        Metric.CADENCE -> cadence
    }
}

enum class StepStatus { COMPLETE, PARTIAL, NOT_REACHED }

/** FAST/SLOW for pace targets, HIGH/LOW for HR, power and cadence. */
enum class Verdict { IN, FAST, SLOW, HIGH, LOW }

enum class Alignment { BOUNDARIES, PLANNED }

/** What happened over a stretch of the activity. Averages are moving-time weighted; null when not recorded. */
data class Actual(
    val startSec: Double,
    val endSec: Double,
    val elapsedSec: Double,
    val movingSec: Double,
    val distanceM: Double?,
    val avgSpeedMps: Double?,
    val avgHr: Double?,
    val avgPower: Double?,
    val avgCadence: Double?,
) {
    /** km/h (mph) for Ride, pace for everything else. */
    fun speedText(sport: Sport, unit: PaceUnit): String? = avgSpeedMps?.let { PlanVsActual.speedText(it, sport, unit) }
}

/** Moving seconds with the target metric recorded, split by where the value was. For PACE "above" means faster. */
data class TimeInRange(val metric: Metric, val inSec: Double, val belowSec: Double, val aboveSec: Double) {
    val totalSec: Double get() = inSec + belowSec + aboveSec
    val pctIn: Double get() = pct(inSec)
    val pctBelow: Double get() = pct(belowSec)
    val pctAbove: Double get() = pct(aboveSec)
    private fun pct(v: Double) = if (totalSec > 0) 100.0 * v / totalSec else 0.0
}

data class StepAnalysis(
    val index: Int,
    val step: WorkoutStep,
    val status: StepStatus,
    /** Null when the step was not reached. */
    val actual: Actual?,
    /** The step's primary target, as parsed. */
    val target: Target?,
    /** Average of the target metric (m/s for PACE). */
    val targetAverage: Double?,
    val timeInRange: TimeInRange?,
    /** Null without a target, or without data for its metric. */
    val verdict: Verdict?,
) {
    val kind: StepKind get() = step.kind
}

data class PlanSummary(
    val stepsPlanned: Int,
    val stepsCompleted: Int,
    val stepsPartial: Int,
    val stepsNotReached: Int,
    /** The activity stopped before the plan did: a step was not reached, or the last one is partial. */
    val endedEarly: Boolean,
    /** Over ACTIVE steps with a target and data for it. */
    val workTimeInRangeSec: Double,
    val workTimeWithTargetSec: Double,
    /** Null when no ACTIVE step has a target with data. */
    val workPctInRange: Double?,
    /** Activity after the last planned step (at least [PlanVsActual.EXTRA_MIN_SEC] moving), else null. */
    val extra: Actual?,
    val total: Actual?,
    val alignment: Alignment,
)

data class PlanAnalysis(val sport: Sport, val steps: List<StepAnalysis>, val summary: PlanSummary)

/**
 * Plan vs actual for a completed structured workout.
 *
 * Alignment:
 * - With [analyze]'s `boundaries` (laps/intervals, one per step, in step order) that pass [boundariesMatchPlan],
 *   boundary k is step k, or the step its [TimeSpan.stepIndex] names when every boundary has one. Steps without a
 *   boundary are NOT_REACHED. When the last step has a boundary, it is cut at its planned duration or distance
 *   (when it has one) and everything after that is the "extra" stretch: older watch recordings left the last lap
 *   open to the end, and laps from other devices may run on.
 * - Boundaries that fail [boundariesMatchPlan] (or an empty list) are ignored: alignment is then as without.
 * - Without, steps are laid out from the start like [WorkoutEngine] runs them: a duration step lasts its planned
 *   moving time (pauses do not count), a distance step its planned distance. A distance step with no distance or
 *   velocity stream is treated like a lap-press step.
 * - Lap-press steps have no planned length. Steps before the first one are laid out from the start, steps after
 *   the last one backwards from the end of the activity; a single lap-press step takes what is in between. With
 *   several, the steps between them keep their planned length and the lap-press steps share the remaining moving
 *   time equally. If the activity is too short for that (the fixed steps would overlap), everything is laid out
 *   from the start and the first lap-press step takes the rest of the activity. Extra time after the plan cannot
 *   be told apart from a lap-press step, so it goes to that step.
 *
 * A step is COMPLETE when it got at least [COMPLETE_FRACTION] of its planned time or distance (any time at all for
 * a lap-press step), PARTIAL when it got less, NOT_REACHED when it got none.
 *
 * Verdict: the step's average of its target metric against the target range widened by [Tolerance] on each side.
 * A one-sided target (low <= 0, or high infinite) has no bound on that side. Time in range uses the exact range.
 *
 * Ramps follow [WorkoutEngine.progress]: the position in the step is moving time over the planned duration, or
 * distance over the planned distance, clamped to 0..1 (0 throughout a lap-press step). Time in range uses the
 * range at each sample's position; the verdict uses the range halfway through the part done (a ramp stopped at
 * half way is judged at a quarter).
 */
object PlanVsActual {
    const val COMPLETE_FRACTION = 0.97
    /** A longer gap between samples counts as paused. */
    const val MAX_SAMPLE_GAP_SEC = 30.0
    const val EXTRA_MIN_SEC = 5.0
    /** Boundary plausibility, see [boundariesMatchPlan]. */
    const val MATCH_TOL_FRACTION = 0.10
    const val MATCH_TOL_SEC = 10.0
    const val MATCH_TOL_M = 50.0
    const val MATCH_MIN_SHARE = 0.75
    private const val EPS = 1e-6

    fun analyze(
        steps: List<WorkoutStep>,
        sport: Sport,
        streams: ActivityStreams,
        boundaries: List<TimeSpan>? = null,
        tolerance: Tolerance = Tolerance(),
    ): PlanAnalysis {
        val tl = Timeline(streams)
        var segs = arrayOfNulls<Seg>(steps.size)
        var extra: Seg? = null
        val mapped = mapBoundaries(tl, steps, boundaries)
        val alignment = if (mapped != null) Alignment.BOUNDARIES else Alignment.PLANNED
        if (tl.n >= 2) {
            if (mapped != null) {
                segs = mapped
                val k = steps.lastIndex
                segs[k]?.let { last ->
                    val cut = clip(tl, steps[k], last)
                    segs[k] = cut
                    extra = Seg(cut.b, tl.n - 1)
                }
            } else {
                alignPlanned(tl, steps, segs)
                val last = segs.lastOrNull()
                if (last != null && fraction(tl, steps.last(), last) >= 1 - EPS) extra = Seg(last.b, tl.n - 1)
            }
        }
        extra = extra?.takeIf { tl.moving(it) >= EXTRA_MIN_SEC }

        val results = steps.mapIndexed { i, step -> stepAnalysis(tl, i, step, segs[i], tolerance) }
        val work = results.filter { it.kind == StepKind.ACTIVE }.mapNotNull { it.timeInRange }
        val inSec = work.sumOf { it.inSec }
        val withTarget = work.sumOf { it.totalSec }
        val summary = PlanSummary(
            stepsPlanned = steps.size,
            stepsCompleted = results.count { it.status == StepStatus.COMPLETE },
            stepsPartial = results.count { it.status == StepStatus.PARTIAL },
            stepsNotReached = results.count { it.status == StepStatus.NOT_REACHED },
            endedEarly = results.any { it.status == StepStatus.NOT_REACHED } ||
                results.lastOrNull()?.status == StepStatus.PARTIAL,
            workTimeInRangeSec = inSec,
            workTimeWithTargetSec = withTarget,
            workPctInRange = if (withTarget > 0) 100.0 * inSec / withTarget else null,
            extra = extra?.let { actual(tl, it) },
            total = if (tl.n >= 2) actual(tl, Seg(0, tl.n - 1)) else null,
            alignment = alignment,
        )
        return PlanAnalysis(sport, results, summary)
    }

    /**
     * Whether [boundaries] can stand for the plan's steps (else [analyze] lays the steps out itself):
     * - not null or empty, each span ending at or after its start, starts in time order, and any [TimeSpan.kind]
     *   given equal to its step's kind;
     * - with a [TimeSpan.stepIndex] on every span (the watch's own laps): indexes strictly increasing and within the
     *   plan. Lengths are not checked: a lap press may end any step early.
     * - without (intervals.icu intervals, or stored laps that lost the index): at most one more span than steps
     *   (time after the plan), and the spans must look like the steps. Pair span k with step k; for each step with a
     *   planned duration (or distance, with a distance stream), except the plan's last step and empty spans (a step
     *   skipped within one tick), the span's moving time (distance) must be within [MATCH_TOL_FRACTION] of the plan
     *   or [MATCH_TOL_SEC] ([MATCH_TOL_M]), whichever is more. The last span may be shorter (the activity ended in
     *   that step). At least [MATCH_MIN_SHARE] of the checked spans must match. This lets a few lap-pressed steps
     *   through but rejects intervals.icu splits that are not the plan's laps: merged or detected intervals shift
     *   every later step, and one span over everything is far longer than a first step.
     */
    fun boundariesMatchPlan(steps: List<WorkoutStep>, streams: ActivityStreams, boundaries: List<TimeSpan>?): Boolean =
        mapBoundaries(Timeline(streams), steps, boundaries) != null

    /** "4:10–4:20 /km", "98 → 154 W" for a ramp, "≤ 140 bpm" / "≥ 250 W" one-sided; pace one-sided in words. */
    fun targetText(t: Target, unit: PaceUnit, sport: Sport? = null): String {
        val u = Format.unitLabel(t.metric, unit, sport)
        fun v(x: Double) = Format.value(t.metric, x, unit)
        return when {
            t.isRamp -> "${v(t.rampFrom!!)} → ${v(t.rampTo!!)} $u"
            t.low <= 0 && t.metric == Metric.PACE -> "slower than ${v(t.high)} $u"
            t.high.isInfinite() && t.metric == Metric.PACE -> "faster than ${v(t.low)} $u"
            t.low <= 0 -> "≤ ${v(t.high)} $u"
            t.high.isInfinite() -> "≥ ${v(t.low)} $u"
            else -> Format.target(t, 0.0, unit, sport)
        }
    }

    /** km/h (mph) for Ride, pace for everything else. */
    fun speedText(mps: Double, sport: Sport, unit: PaceUnit): String = when {
        sport == Sport.RIDE && unit == PaceUnit.MILE -> "%.1f mph".format(mps * 3600 / 1609.344)
        sport == Sport.RIDE -> "%.1f km/h".format(mps * 3.6)
        else -> "${Format.pace(mps, unit)} ${Format.paceSuffix(unit)}"
    }

    // ---- alignment ----

    /** Samples a+1..b, i.e. wall time t[a]..t[b]. Empty when a == b. */
    private data class Seg(val a: Int, val b: Int)

    /** Boundaries per step, or null when they fail [boundariesMatchPlan]. */
    private fun mapBoundaries(tl: Timeline, steps: List<WorkoutStep>, boundaries: List<TimeSpan>?): Array<Seg?>? {
        if (boundaries.isNullOrEmpty() || steps.isEmpty()) return null
        if (boundaries.any { it.endSec < it.startSec - EPS }) return null
        if (boundaries.zipWithNext().any { (a, b) -> b.startSec < a.startSec - EPS }) return null
        val byStep = arrayOfNulls<TimeSpan>(steps.size)
        val indexed = boundaries.all { it.stepIndex != null }
        if (indexed) {
            var prev = -1
            for (b in boundaries) {
                val k = b.stepIndex!!
                if (k <= prev || k >= steps.size) return null
                byStep[k] = b
                prev = k
            }
        } else {
            if (boundaries.size > steps.size + 1) return null
            for (k in 0 until minOf(steps.size, boundaries.size)) byStep[k] = boundaries[k]
        }
        if (steps.indices.any { k -> byStep[k]?.kind?.let { it != steps[k].kind } == true }) return null
        if (tl.n < 2) return arrayOfNulls(steps.size)
        val segs = Array(steps.size) { k ->
            byStep[k]?.let { b -> tl.indexAt(b.startSec).let { a -> Seg(a, tl.indexAt(b.endSec).coerceAtLeast(a)) } }
        }
        if (!indexed && !lengthsMatch(tl, steps, segs)) return null
        return segs
    }

    private fun lengthsMatch(tl: Timeline, steps: List<WorkoutStep>, segs: Array<Seg?>): Boolean {
        val lastPaired = segs.indexOfLast { it != null }
        var checked = 0
        var ok = 0
        for (k in 0..lastPaired) {
            val seg = segs[k] ?: continue
            if (k == steps.lastIndex || seg.a == seg.b) continue
            val s = steps[k]
            val (got, planned, abs) = when {
                s.durationSec != null -> Triple(tl.moving(seg), s.durationSec.toDouble(), MATCH_TOL_SEC)
                s.distanceM != null && tl.d != null -> Triple(tl.d[seg.b] - tl.d[seg.a], s.distanceM, MATCH_TOL_M)
                else -> continue
            }
            val tol = maxOf(abs, planned * MATCH_TOL_FRACTION)
            checked++
            if (got <= planned + tol && (k == lastPaired || got >= planned - tol)) ok++
        }
        return checked == 0 || ok >= checked * MATCH_MIN_SHARE - EPS
    }

    /** [seg] cut where the step's planned duration or distance is reached; as is without one or when not reached. */
    private fun clip(tl: Timeline, s: WorkoutStep, seg: Seg): Seg {
        val j = when {
            s.durationSec != null -> (seg.a + 1..seg.b).firstOrNull { tl.m[it] - tl.m[seg.a] >= s.durationSec - EPS }
            s.distanceM != null && tl.d != null -> (seg.a + 1..seg.b).firstOrNull { tl.d[it] - tl.d[seg.a] >= s.distanceM - EPS }
            else -> null
        }
        return if (j != null) Seg(seg.a, j) else seg
    }

    private fun lapLike(tl: Timeline, s: WorkoutStep) = s.endsOnLap || (s.durationSec == null && tl.d == null)

    private fun alignPlanned(tl: Timeline, steps: List<WorkoutStep>, segs: Array<Seg?>) {
        val laps = steps.indices.filter { lapLike(tl, steps[it]) }
        if (laps.isEmpty()) {
            walk(tl, steps, steps.indices, 0, null, tl.n - 1, segs)
            return
        }
        val first = laps.first()
        val last = laps.last()
        val cF = walk(tl, steps, 0 until first, 0, null, tl.n - 1, segs) ?: return
        if (fits(tl, steps, segs, cF, first, last)) return
        segs.fill(null)
        walk(tl, steps, steps.indices, 0, null, tl.n - 1, segs)
    }

    /** Places steps first..end around lap-press steps; false (segs past first left dirty) when they don't fit. */
    private fun fits(tl: Timeline, steps: List<WorkoutStep>, segs: Array<Seg?>, cF: Int, first: Int, last: Int): Boolean {
        var cB = tl.n - 1
        for (k in steps.indices.reversed()) {
            if (k <= last) break
            val s = steps[k]
            val j = if (s.durationSec != null) {
                (cB - 1 downTo cF).firstOrNull { tl.m[cB] - tl.m[it] >= s.durationSec - EPS }
            } else {
                val d = tl.d!!
                (cB - 1 downTo cF).firstOrNull { d[cB] - d[it] >= s.distanceM!! - EPS }
            } ?: return false
            segs[k] = Seg(j, cB)
            cB = j
        }
        if (first == last) {
            segs[first] = Seg(cF, cB)
            return true
        }
        val nLaps = (first..last).count { lapLike(tl, steps[it]) }
        val e0 = walk(tl, steps, first..last, cF, 0.0, cB, segs) ?: return false
        val lapLen = (tl.m[cB] - tl.m[e0]) / nLaps
        val c = walk(tl, steps, first until last, cF, lapLen, cB, segs) ?: return false
        if (c > cB) return false
        segs[last] = Seg(c, cB)
        return true
    }

    /**
     * Lays [range] out forward from sample [start], no further than [limit]. A lap-like step lasts [lapLen] moving
     * seconds, or to [limit] when null. Returns the end cursor, or null when the data ran out (the step it ran out in
     * is placed partial, the rest stay null).
     */
    private fun walk(
        tl: Timeline, steps: List<WorkoutStep>, range: IntRange, start: Int, lapLen: Double?, limit: Int,
        segs: Array<Seg?>,
    ): Int? {
        var c = start
        for (k in range) {
            val s = steps[k]
            if (c >= limit && !(lapLike(tl, s) && lapLen == 0.0)) return null
            val j: Int? = when {
                lapLike(tl, s) && lapLen == null -> { segs[k] = Seg(c, limit); return null }
                lapLike(tl, s) -> (c..limit).firstOrNull { tl.m[it] - tl.m[c] >= lapLen!! - EPS }
                s.durationSec != null -> (c + 1..limit).firstOrNull { tl.m[it] - tl.m[c] >= s.durationSec - EPS }
                else -> (c + 1..limit).firstOrNull { tl.d!![it] - tl.d[c] >= s.distanceM!! - EPS }
            }
            if (j == null) { segs[k] = Seg(c, limit); return null }
            segs[k] = Seg(c, j)
            c = j
        }
        return c
    }

    // ---- statistics ----

    /** Share of the planned length done; 1 for a lap-like step with any time. */
    private fun fraction(tl: Timeline, s: WorkoutStep, seg: Seg): Double {
        if (seg.a >= seg.b) return 0.0
        return when {
            s.durationSec != null -> tl.moving(seg) / s.durationSec
            s.distanceM != null && tl.d != null -> (tl.d[seg.b] - tl.d[seg.a]) / s.distanceM
            else -> 1.0
        }
    }

    private fun stepAnalysis(tl: Timeline, i: Int, step: WorkoutStep, seg: Seg?, tol: Tolerance): StepAnalysis {
        val target = step.primaryTarget
        val f = seg?.let { fraction(tl, step, it) } ?: 0.0
        val status = when {
            seg == null || f <= 0 -> StepStatus.NOT_REACHED
            f >= COMPLETE_FRACTION -> StepStatus.COMPLETE
            else -> StepStatus.PARTIAL
        }
        if (status == StepStatus.NOT_REACHED) return StepAnalysis(i, step, status, null, target, null, null, null)
        val act = actual(tl, seg!!)
        if (target == null) return StepAnalysis(i, step, status, act, null, null, null, null)
        val avg = when (target.metric) {
            Metric.PACE -> act.avgSpeedMps
            Metric.HEART_RATE -> act.avgHr
            Metric.POWER -> act.avgPower
            Metric.CADENCE -> act.avgCadence
        }
        // Halfway through the part done, by the engine's measure (0 for a lap-press step).
        val mid = if (lapLike(tl, step)) 0.0 else minOf(1.0, f) / 2
        return StepAnalysis(i, step, status, act, target, avg, timeInRange(tl, seg, step, target), avg?.let { verdict(target, it, mid, tol) })
    }

    private fun verdict(t: Target, avg: Double, at: Double, tol: Tolerance): Verdict {
        val r = t.rangeAt(at)
        val lo = r.start
        val hi = r.endInclusive
        val pace = t.metric == Metric.PACE
        return when {
            !hi.isInfinite() && avg > hi + tol.of(t.metric, hi) -> if (pace) Verdict.FAST else Verdict.HIGH
            lo > 0 && avg < lo - tol.of(t.metric, lo) -> if (pace) Verdict.SLOW else Verdict.LOW
            else -> Verdict.IN
        }
    }

    private fun timeInRange(tl: Timeline, seg: Seg, step: WorkoutStep, t: Target): TimeInRange? {
        if (tl.moving(seg) <= 0) return null
        var inS = 0.0; var below = 0.0; var above = 0.0
        for (i in seg.a + 1..seg.b) {
            val w = tl.w[i]
            if (w <= 0) continue
            val v = tl.value(t.metric, i) ?: continue
            // Position in the step like WorkoutEngine.progress (rangeAt clamps it to 0..1).
            val f = when {
                lapLike(tl, step) -> 0.0
                step.durationSec != null -> (tl.m[i] - w / 2 - tl.m[seg.a]) / step.durationSec
                else -> ((tl.d!![i - 1] + tl.d[i]) / 2 - tl.d[seg.a]) / step.distanceM!!
            }
            when (t.compare(v, f)) {
                -1 -> below += w
                1 -> above += w
                else -> inS += w
            }
        }
        return if (inS + below + above > 0) TimeInRange(t.metric, inS, below, above) else null
    }

    private fun actual(tl: Timeline, seg: Seg): Actual {
        val moving = tl.moving(seg)
        val dist = tl.d?.let { it[seg.b] - it[seg.a] }
        val speed = tl.s.velocityMps?.let { tl.avg(it, seg) }
            ?: if (dist != null && moving > 0) dist / moving else null
        return Actual(
            startSec = tl.t[seg.a],
            endSec = tl.t[seg.b],
            elapsedSec = tl.t[seg.b] - tl.t[seg.a],
            movingSec = moving,
            distanceM = dist,
            avgSpeedMps = speed,
            avgHr = tl.s.heartrate?.let { tl.avg(it, seg) },
            avgPower = tl.s.watts?.let { tl.avg(it, seg) },
            avgCadence = tl.s.cadence?.let { tl.avg(it, seg) },
        )
    }

    private class Timeline(val s: ActivityStreams) {
        val n = s.size
        val t = s.timeSec
        /** Moving seconds of sample i's interval. */
        val w = DoubleArray(n)
        /** Cumulative moving seconds. */
        val m = DoubleArray(n)
        /** Cumulative distance, from the distance stream or integrated velocity; null without either. */
        val d: DoubleArray?

        init {
            for (i in 1 until n) {
                val dt = t[i] - t[i - 1]
                w[i] = if (dt <= 0 || dt > MAX_SAMPLE_GAP_SEC || s.moving?.get(i) == false) 0.0
                else (dt - s.pauses.sumOf { (minOf(it.endSec, t[i]) - maxOf(it.startSec, t[i - 1])).coerceAtLeast(0.0) })
                    .coerceAtLeast(0.0)
                m[i] = m[i - 1] + w[i]
            }
            d = when {
                s.distanceM != null -> DoubleArray(n).also { out ->
                    var last = 0.0
                    for (i in 0 until n) { val v = s.distanceM[i]; if (!v.isNaN()) last = v; out[i] = last }
                }
                s.velocityMps != null -> DoubleArray(n).also { out ->
                    for (i in 1 until n) {
                        val v = s.velocityMps[i]
                        out[i] = out[i - 1] + if (v.isNaN()) 0.0 else v * w[i]
                    }
                }
                else -> null
            }
        }

        fun moving(seg: Seg) = m[seg.b] - m[seg.a]

        /** Largest sample index at or before [sec]. */
        fun indexAt(sec: Double): Int {
            var lo = 0; var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (t[mid] <= sec + EPS) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun avg(a: DoubleArray, seg: Seg): Double? {
            var sum = 0.0; var weight = 0.0
            for (i in seg.a + 1..seg.b) {
                val v = a[i]
                if (w[i] <= 0 || v.isNaN()) continue
                sum += v * w[i]; weight += w[i]
            }
            return if (weight > 0) sum / weight else null
        }

        fun value(metric: Metric, i: Int): Double? = when (metric) {
            Metric.PACE -> s.velocityMps?.get(i)?.takeUnless { it.isNaN() }
                ?: if (s.velocityMps == null && d != null && w[i] > 0) (d[i] - d[i - 1]) / w[i] else null
            Metric.HEART_RATE -> s.heartrate?.get(i)
            Metric.POWER -> s.watts?.get(i)
            Metric.CADENCE -> s.cadence?.get(i)
        }?.takeUnless { it.isNaN() }
    }
}
