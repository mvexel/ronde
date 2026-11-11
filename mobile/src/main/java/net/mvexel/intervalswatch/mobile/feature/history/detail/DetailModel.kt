package net.mvexel.intervalswatch.mobile.feature.history.detail

import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetail
import net.mvexel.intervalswatch.mobile.data.activity.LatLon
import net.mvexel.intervalswatch.mobile.ringArcs
import net.mvexel.intervalswatch.shared.ActivityStreams
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.PlanAnalysis
import net.mvexel.intervalswatch.shared.PlanVsActual
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepAnalysis
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepStatus
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.Verdict
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Everything the Activity Detail draws, derived once (off the main thread) from an [ActivityDetail].
 * Pure functions only, so the view model, the screenshot tests and the unit tests share them.
 */
data class DetailContent(
    val detail: ActivityDetail,
    val analysis: PlanAnalysis?,
    val meta: String,
    val rounds: Rounds?,
    val ring: List<ResultArc>,
    val headline: List<Headline>,
    val rows: List<StepRow>,
    val shading: List<Shade>,
    val charts: List<ChartData>,
    val route: RouteDrawing?,
    /** Upper bpm bound of Z1..Z4 (Z5 above); null hides the ladder. Not in [ActivityDetail] yet. */
    val hrZones: List<Int>? = null,
) {
    val structured: Boolean get() = analysis != null
    val isGarmin: Boolean get() = detail.deviceName?.contains("garmin", ignoreCase = true) == true
}

data class Rounds(val inRange: Int, val total: Int) {
    val text: String get() = "$inRange of $total"
}

/** One arc of the result ring: a missed or unreached rep is drawn hollow. */
data class ResultArc(val start: Float, val sweep: Float, val kind: StepKind, val hollow: Boolean)

data class Headline(val label: String, val value: String, val unit: String?)

enum class Off { OVER, UNDER }

/** "▼ 5 s/km fast": [amount] stays ink; the chevron and [word] carry the hue. */
data class Correction(val off: Off, val chevron: String, val amount: String, val word: String) {
    val text: String get() = "$chevron $amount $word"
}

data class StepRow(
    val index: Int,
    val kind: StepKind,
    /** 1-based rep number for work steps. */
    val rep: Int?,
    val title: String,
    val subtitle: String?,
    val target: String,
    val avg: String?,
    val hr: String?,
    /** 0..1 of the step's moving time with the target metric in range; null without a target. */
    val inRangeFraction: Float?,
    val inRangeText: String?,
    val correction: Correction?,
    val status: StepStatus,
)

data class Shade(val startSec: Float, val endSec: Float, val kind: StepKind)

data class Band(val startSec: Float, val endSec: Float, val lo: Float, val hi: Float)

enum class ChartKind(val title: String, val caption: String) {
    PACE("Pace", "min/km · faster is up"),
    SPEED("Speed", "km/h"),
    HEART_RATE("Heart rate", "bpm"),
    POWER("Power", "W · only when recorded"),
}

/**
 * One chart, downsampled for drawing. [v] is in display units (s/km, km/h, bpm, W), NaN for gaps.
 * [flags] per point: 1 over the target (fast / high), -1 under (slow / low), 0 otherwise.
 * [invert] puts small values on top (pace: faster is up).
 */
class ChartData(
    val kind: ChartKind,
    val t: FloatArray,
    val v: FloatArray,
    val flags: ByteArray,
    val bands: List<Band>,
    val yMin: Float,
    val yMax: Float,
    val invert: Boolean,
    val yTicks: List<Pair<Float, String>>,
    val tMax: Float,
)

data class RouteSegment(val kind: StepKind?, val points: List<LatLon>)

data class RouteMarker(val rep: Int, val at: LatLon)

data class RouteDrawing(val segments: List<RouteSegment>, val markers: List<RouteMarker>, val start: LatLon, val end: LatLon) {
    val all: List<LatLon> get() = segments.flatMap { it.points }
}

object DetailModel {
    /** Off-target stretches shorter than this stay ink, like the watch's dwell rule (design.md weak spots). */
    const val DWELL_SEC = 10.0
    const val SMOOTH_SEC = 15.0
    const val MAX_POINTS = 360
    private val unit = PaceUnit.KM

    fun build(d: ActivityDetail, hrZones: List<Int>? = null): DetailContent {
        val sport = d.sport ?: Sport.OTHER
        val streams = d.streams?.takeIf { d.detailsAvailable && it.size >= 2 }
        val steps = d.plannedSteps?.takeIf { it.isNotEmpty() }
        val analysis = if (steps != null && streams != null) PlanVsActual.analyze(steps, sport, streams, d.boundaries) else null
        val shading = analysis?.steps?.mapNotNull { s -> s.actual?.let { Shade(it.startSec.toFloat(), it.endSec.toFloat(), s.kind) } }.orEmpty()
        return DetailContent(
            detail = d,
            analysis = analysis,
            meta = meta(d),
            rounds = analysis?.let(::rounds),
            ring = analysis?.let(::resultRing).orEmpty(),
            headline = headline(d),
            rows = analysis?.let { rows(it) }.orEmpty(),
            shading = shading,
            charts = streams?.let { charts(sport, it, analysis) }.orEmpty(),
            route = d.route?.takeIf { d.detailsAvailable && it.size >= 2 }?.let { route(it, streams, analysis) },
            hrZones = hrZones,
        )
    }

    // ---- hero ----

    fun sportLabel(s: Sport?): String? = when (s) {
        Sport.RUN -> "Run"
        Sport.RIDE -> "Ride"
        Sport.WALK -> "Walk"
        Sport.OTHER, null -> null
    }

    /** "Tue 10 Nov · 07:12 · Run". */
    fun meta(d: ActivityDetail, locale: Locale = Locale.getDefault()): String {
        val dt = runCatching { LocalDateTime.parse(d.startLocal) }.getOrNull()
        val day = dt?.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale)) ?: d.startLocal.take(10)
        return listOfNotNull(day, sportLabel(d.sport)).joinToString(" · ")
    }

    /** Work steps whose average was in range, of all work steps (unreached ones count as missed). */
    fun rounds(a: PlanAnalysis): Rounds? {
        val work = a.steps.filter { it.kind == StepKind.ACTIVE }
        if (work.isEmpty()) return null
        return Rounds(work.count { it.verdict == Verdict.IN }, work.size)
    }

    fun resultRing(a: PlanAnalysis): List<ResultArc> = ringArcs(a.steps.map { it.step }).mapIndexed { i, arc ->
        val s = a.steps[i]
        val missed = s.kind == StepKind.ACTIVE && (s.status == StepStatus.NOT_REACHED || (s.verdict != null && s.verdict != Verdict.IN))
        ResultArc(arc.start, arc.sweep, arc.kind, hollow = missed)
    }

    // ---- headline numbers ----

    fun headline(d: ActivityDetail): List<Headline> {
        val speed = d.distanceM?.let { m -> d.movingTimeSec?.takeIf { it > 0 }?.let { m / it } }
        return listOfNotNull(
            d.distanceM?.takeIf { it > 0 }?.let { Headline("Distance", "%.2f".format(Locale.ROOT, it / 1000), "km") },
            (d.movingTimeSec ?: d.elapsedTimeSec)?.let { Headline("Time", Format.duration(it.toDouble()), null) },
            speed?.takeIf { it > 0.3 }?.let {
                if (d.sport == Sport.RIDE) Headline("Avg speed", "%.1f".format(Locale.ROOT, it * 3.6), "km/h")
                else Headline("Avg pace", Format.pace(it, unit), "/km")
            },
            d.avgHr?.let { Headline("Avg HR", it.roundToInt().toString(), "bpm") },
            d.maxHr?.let { Headline("Max HR", it.roundToInt().toString(), "bpm") },
            d.avgPower?.let { Headline("Avg power", it.roundToInt().toString(), "W") },
            d.load?.let { Headline("Load", it.toString(), null) },
        )
    }

    // ---- steps table ----

    fun rows(a: PlanAnalysis): List<StepRow> {
        var rep = 0
        return a.steps.map { s ->
            val r = if (s.kind == StepKind.ACTIVE) ++rep else null
            row(s, r, a.sport)
        }
    }

    private fun row(s: StepAnalysis, rep: Int?, sport: Sport): StepRow {
        val planned = s.step.durationSec?.let { Format.duration(it.toDouble()) }
            ?: s.step.distanceM?.let { Format.distance(it, unit) }
        val title = when (s.kind) {
            StepKind.ACTIVE -> "Rep $rep"
            StepKind.WARMUP -> "Warm-up"
            StepKind.COOLDOWN -> "Cool-down"
            StepKind.REST -> listOfNotNull("Easy", planned).joinToString(" ")
        }
        val subtitle = when (s.kind) {
            StepKind.ACTIVE -> s.actual?.distanceM?.takeIf { it > 0 }?.let { "%.2f km".format(Locale.ROOT, it / 1000) } ?: planned
            StepKind.WARMUP, StepKind.COOLDOWN -> planned
            StepKind.REST -> null
        }
        val tir = s.timeInRange
        return StepRow(
            index = s.index,
            kind = s.kind,
            rep = rep,
            title = title,
            subtitle = subtitle,
            target = s.target?.let(::rangeText) ?: if (s.kind == StepKind.ACTIVE) "—" else "easy",
            avg = when (s.status) {
                StepStatus.NOT_REACHED -> null
                else -> avgText(s, sport)
            },
            hr = s.actual?.avgHr?.roundToInt()?.toString(),
            inRangeFraction = tir?.takeIf { s.kind == StepKind.ACTIVE && it.totalSec > 0 }?.let { (it.inSec / it.totalSec).toFloat() },
            inRangeText = tir?.takeIf { s.kind == StepKind.ACTIVE }?.let { Format.duration(it.inSec) },
            correction = correction(s),
            status = s.status,
        )
    }

    /** The target range without its unit, low number first: "4:10–4:20", "138–152", "98→154". */
    fun rangeText(t: Target): String {
        fun v(x: Double) = Format.value(t.metric, x, unit)
        if (t.isRamp) return "${v(t.rampFrom!!)}→${v(t.rampTo!!)}"
        if (t.low <= 0) return if (t.metric == Metric.PACE) "> ${v(t.high)}" else "≤ ${v(t.high)}"
        if (t.high.isInfinite()) return if (t.metric == Metric.PACE) "< ${v(t.low)}" else "≥ ${v(t.low)}"
        val (a, b) = if (t.metric == Metric.PACE) v(t.high) to v(t.low) else v(t.low) to v(t.high)
        return if (a == b) a else "$a–$b"
    }

    private fun avgText(s: StepAnalysis, sport: Sport): String? {
        val act = s.actual ?: return null
        return when (s.target?.metric) {
            Metric.PACE -> act.avgSpeedMps?.let { Format.pace(it, unit) }
            Metric.HEART_RATE -> act.avgHr?.roundToInt()?.toString()
            Metric.POWER -> act.avgPower?.roundToInt()?.toString()
            Metric.CADENCE -> act.avgCadence?.roundToInt()?.toString()
            null -> when {
                sport == Sport.RIDE && act.avgPower != null -> act.avgPower!!.roundToInt().toString()
                sport == Sport.RIDE -> act.avgSpeedMps?.let { "%.1f".format(Locale.ROOT, it * 3.6) }
                else -> act.avgSpeedMps?.let { Format.pace(it, unit) }
            }
        }
    }

    /** How far the average missed the nearest edge of the target, for off reps only. */
    fun correction(s: StepAnalysis): Correction? {
        val t = s.target ?: return null
        val avg = s.targetAverage ?: return null
        val v = s.verdict ?: return null
        if (v == Verdict.IN) return null
        val r = t.rangeAt(0.5)
        return when (t.metric) {
            Metric.PACE -> {
                fun secPerKm(mps: Double) = 1000.0 / mps
                if (v == Verdict.FAST) Correction(Off.OVER, "▼", "${(secPerKm(r.endInclusive) - secPerKm(avg)).roundToInt()} s/km", "fast")
                else Correction(Off.UNDER, "▲", "${(secPerKm(avg) - secPerKm(r.start)).roundToInt()} s/km", "slow")
            }
            else -> {
                val u = Format.unitLabel(t.metric, unit)
                if (v == Verdict.HIGH) Correction(Off.OVER, "▲", "${(avg - r.endInclusive).roundToInt()} $u", "high")
                else Correction(Off.UNDER, "▼", "${(r.start - avg).roundToInt()} $u", "low")
            }
        }
    }

    // ---- charts ----

    fun charts(sport: Sport, s: ActivityStreams, a: PlanAnalysis?): List<ChartData> {
        val t = s.timeSec
        val out = mutableListOf<ChartData>()
        s.velocityMps?.takeIf { it.hasData() }?.let { vel ->
            if (sport == Sport.RIDE) {
                val kmh = DoubleArray(vel.size) { if (vel[it].isNaN()) Double.NaN else vel[it] * 3.6 }
                out += chart(ChartKind.SPEED, t, kmh, a, Metric.PACE, { it * 3.6 }, { it / 3.6 }, invert = false)
            } else {
                val secKm = DoubleArray(vel.size) { if (vel[it].isNaN() || vel[it] < 0.5) Double.NaN else 1000.0 / vel[it] }
                out += chart(ChartKind.PACE, t, secKm, a, Metric.PACE, { 1000.0 / it }, { 1000.0 / it }, invert = true)
            }
        }
        s.heartrate?.takeIf { it.hasData() }?.let { out += chart(ChartKind.HEART_RATE, t, it, a, Metric.HEART_RATE, { it }, { it }, invert = false) }
        s.watts?.takeIf { it.hasData() }?.let { out += chart(ChartKind.POWER, t, it, a, Metric.POWER, { it }, { it }, invert = false) }
        return out
    }

    private fun DoubleArray.hasData() = any { !it.isNaN() && it > 0 }

    /**
     * [toDisplay] maps the target's native unit (m/s for pace) to the chart's, [toNative] back.
     */
    private fun chart(
        kind: ChartKind, t: DoubleArray, raw: DoubleArray, a: PlanAnalysis?, metric: Metric,
        toDisplay: (Double) -> Double, toNative: (Double) -> Double, invert: Boolean,
    ): ChartData {
        val sm = smooth(t, raw, SMOOTH_SEC)
        // Targets for this chart's metric, by step.
        val targeted = a?.steps.orEmpty().mapNotNull { s ->
            val act = s.actual ?: return@mapNotNull null
            val tg = s.step.target(metric) ?: return@mapNotNull null
            Triple(act.startSec, act.endSec, tg)
        }
        val rawFlags = ByteArray(t.size)
        for ((start, end, tg) in targeted) {
            for (i in t.indices) {
                if (t[i] <= start || t[i] > end || sm[i].isNaN()) continue
                val f = ((t[i] - start) / (end - start)).coerceIn(0.0, 1.0)
                rawFlags[i] = tg.compare(toNative(sm[i]), f).toByte()
            }
        }
        applyDwell(t, rawFlags, DWELL_SEC)
        val bands = targeted.mapNotNull { (start, end, tg) ->
            val r = tg.rangeAt(0.5)
            if (r.start <= 0 || r.endInclusive.isInfinite()) return@mapNotNull null
            val x = toDisplay(r.start); val y = toDisplay(r.endInclusive)
            Band(start.toFloat(), end.toFloat(), minOf(x, y).toFloat(), maxOf(x, y).toFloat())
        }
        val (dt, dv, df) = downsample(t, sm, rawFlags, MAX_POINTS)
        val (lo, hi) = yRange(sm, bands, kind)
        val step = tickStep(kind, hi - lo)
        val ticks = generateSequence(ceil(lo / step) * step) { it + step }.takeWhile { it <= hi + 1e-6 }
            .map { it.toFloat() to tickLabel(kind, it) }.toList()
        return ChartData(kind, dt, dv, df, bands, lo.toFloat(), hi.toFloat(), invert, ticks, (t.lastOrNull() ?: 0.0).toFloat())
    }

    /** Centred moving average over [windowSec] of wall time, skipping NaN. */
    fun smooth(t: DoubleArray, v: DoubleArray, windowSec: Double): DoubleArray {
        val out = DoubleArray(v.size)
        var lo = 0; var hi = -1; var sum = 0.0; var n = 0
        for (i in v.indices) {
            while (hi + 1 < v.size && t[hi + 1] <= t[i] + windowSec / 2) { hi++; if (!v[hi].isNaN()) { sum += v[hi]; n++ } }
            while (t[lo] < t[i] - windowSec / 2) { if (!v[lo].isNaN()) { sum -= v[lo]; n-- }; lo++ }
            out[i] = if (v[i].isNaN() || n == 0) Double.NaN else sum / n
        }
        return out
    }

    /** Clears off-target runs shorter than [minSec]. */
    fun applyDwell(t: DoubleArray, flags: ByteArray, minSec: Double) {
        var i = 0
        while (i < flags.size) {
            if (flags[i] == 0.toByte()) { i++; continue }
            var j = i
            while (j + 1 < flags.size && flags[j + 1] == flags[i]) j++
            if (t[j] - t[i] < minSec) for (k in i..j) flags[k] = 0
            i = j + 1
        }
    }

    private fun downsample(t: DoubleArray, v: DoubleArray, f: ByteArray, max: Int): Triple<FloatArray, FloatArray, ByteArray> {
        if (t.size <= max) return Triple(FloatArray(t.size) { t[it].toFloat() }, FloatArray(v.size) { v[it].toFloat() }, f.copyOf())
        val ot = FloatArray(max); val ov = FloatArray(max); val of = ByteArray(max)
        for (b in 0 until max) {
            val a = b * t.size / max
            val e = ((b + 1) * t.size / max).coerceAtLeast(a + 1)
            var sum = 0.0; var n = 0
            for (i in a until e) if (!v[i].isNaN()) { sum += v[i]; n++ }
            ot[b] = t[(a + e - 1) / 2].toFloat()
            ov[b] = if (n == 0) Float.NaN else (sum / n).toFloat()
            // An off-target sample anywhere in the bucket wins, so short stretches survive downsampling.
            of[b] = (a until e).map { f[it] }.firstOrNull { it != 0.toByte() } ?: 0
        }
        return Triple(ot, ov, of)
    }

    private fun yRange(v: DoubleArray, bands: List<Band>, kind: ChartKind): Pair<Double, Double> {
        val vals = v.filter { !it.isNaN() }.sorted()
        if (vals.isEmpty()) return 0.0 to 1.0
        var lo = vals[(vals.size * 0.02).toInt()]
        var hi = vals[((vals.size - 1) * 0.98).toInt()]
        bands.forEach { lo = minOf(lo, it.lo.toDouble()); hi = maxOf(hi, it.hi.toDouble()) }
        if (kind == ChartKind.PACE) { lo = lo.coerceAtLeast(120.0); hi = hi.coerceAtMost(900.0) }
        val pad = maxOf((hi - lo) * 0.08, 1.0)
        return (lo - pad) to (hi + pad)
    }

    private fun tickStep(kind: ChartKind, span: Double): Double = when (kind) {
        ChartKind.PACE -> if (span > 360) 120.0 else if (span > 150) 60.0 else 30.0
        ChartKind.SPEED -> if (span > 30) 10.0 else 5.0
        ChartKind.HEART_RATE -> if (span > 100) 40.0 else 20.0
        ChartKind.POWER -> if (span > 400) 200.0 else if (span > 160) 100.0 else 50.0
    }

    private fun tickLabel(kind: ChartKind, v: Double) = when (kind) {
        ChartKind.PACE -> Format.duration(v)
        else -> v.roundToInt().toString()
    }

    /** "0", "10′", "20′"... every 10 minutes (5 under 25 min, 30 over 3 h). */
    fun timeTicks(tMax: Float): List<Pair<Float, String>> {
        val min = tMax / 60f
        val step = when { min < 25 -> 5; min > 180 -> 30; min > 90 -> 20; else -> 10 }
        return (0..floor(min / step).toInt()).map { (it * step * 60f) to if (it == 0) "0" else "${it * step}′" }
    }

    // ---- route ----

    fun route(points: List<LatLon>, s: ActivityStreams?, a: PlanAnalysis?): RouteDrawing {
        val n = points.size
        fun timeOf(i: Int): Double? {
            val t = s?.timeSec ?: return null
            if (t.isEmpty()) return null
            return if (t.size == n) t[i] else t[(i.toLong() * (t.size - 1) / (n - 1).coerceAtLeast(1)).toInt()]
        }
        val spans = a?.steps.orEmpty().mapNotNull { st -> st.actual?.let { Triple(it.startSec, it.endSec, st) } }
        fun stepAt(i: Int): StepAnalysis? {
            val t = timeOf(i) ?: return null
            return spans.firstOrNull { t >= it.first && t <= it.second }?.third
        }
        val segments = mutableListOf<RouteSegment>()
        var cur = mutableListOf(points[0])
        var curKind = stepAt(0)?.kind
        for (i in 1 until n) {
            val k = stepAt(i)?.kind
            cur += points[i]
            if (k != curKind) {
                segments += RouteSegment(curKind, cur)
                cur = mutableListOf(points[i])
                curKind = k
            }
        }
        if (cur.size >= 2) segments += RouteSegment(curKind, cur)
        val markers = mutableListOf<RouteMarker>()
        var rep = 0
        a?.steps?.forEach { st ->
            if (st.kind != StepKind.ACTIVE) return@forEach
            rep++
            val start = st.actual?.startSec ?: return@forEach
            val idx = (0 until n).firstOrNull { (timeOf(it) ?: return@firstOrNull false) >= start } ?: return@forEach
            markers += RouteMarker(rep, points[idx])
        }
        return RouteDrawing(segments, markers, points.first(), points.last())
    }

    /** Bounding box as (south, west, north, east), padded by [pad] of its size. */
    fun bounds(points: List<LatLon>, pad: Double = 0.0): DoubleArray {
        var s = 90.0; var w = 180.0; var nn = -90.0; var e = -180.0
        points.forEach { s = minOf(s, it.lat); nn = maxOf(nn, it.lat); w = minOf(w, it.lon); e = maxOf(e, it.lon) }
        val dl = maxOf(abs(nn - s), 1e-4) * pad; val dw = maxOf(abs(e - w), 1e-4) * pad
        return doubleArrayOf(s - dl, w - dw, nn + dl, e + dw)
    }

    fun intervalsUrl(id: String) = "https://intervals.icu/activities/$id"
}
