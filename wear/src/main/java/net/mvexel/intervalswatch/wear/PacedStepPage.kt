package net.mvexel.intervalswatch.wear

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import net.mvexel.intervalswatch.shared.CueRow
import net.mvexel.intervalswatch.shared.ColourBand
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Tokens
import net.mvexel.intervalswatch.wear.ui.HeartIcon
import net.mvexel.intervalswatch.wear.ui.Rb
import net.mvexel.intervalswatch.wear.ui.kindColor
import kotlin.math.roundToInt

/*
 * The paced step page (v5-design.md): author on a 192 dp disc, scaled to the screen. Layout numbers are the spec's.
 * Colour: hue only on the stretch, the chevron, the Words word and the wash.
 */

private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

private fun ts(size: Float, weight: Int, em: Float = 0f) = TextStyle(
    fontSize = size.sp, fontWeight = FontWeight(weight), letterSpacing = em.em, fontFeatureSettings = "tnum",
)

private object PT {
    val hero = ts(49f, 700, -0.02f)
    val heroUnit = ts(12f, 500)
    val gps = ts(44f, 700)
    fun cueDigits(large: Boolean) = ts(if (large) 30f else 26f, 700)
    val cueUnit = ts(11f, 600)
    val wordsWord = ts(17f, 800, 0.04f)
    val wordsDigits = ts(22f, 700)
    val range = ts(24f, 600)
    val row1Value = ts(22f, 700)
    val row1Label = ts(10f, 600, 0.04f)
    val settling = ts(15f, 600)
    val hr = ts(20f, 700)
    val onPace = ts(22f, 800, 0.03f)
    val weak = ts(11f, 700)
    val oneSided = ts(17f, 800)
    val next = ts(17f, 800)
    val edge = ts(14f, 600, 0.03f)
}

/** Disc geometry that changes in LARGE mode (font scale at least 1.15): v5 section 8. */
private data class Rows(
    val flanks: Boolean, val hr: Boolean, val timeLeftY: Float, val heroY: Float, val laneY: Float, val row4Y: Float,
)

private val NORMAL_ROWS = Rows(true, true, 62f, 101f, 120f, 159f)
private val LARGE_ROWS = Rows(false, false, 50f, 99f, 118f, 158f)

/** Places the child so its first baseline sits at [y] dp from the top and its [anchor] (0 start, 0.5 centre) at [x] dp. */
internal fun Modifier.atBaseline(x: Float, y: Float, anchor: Float = 0.5f) = layout { m, c ->
    val p = m.measure(Constraints())
    val b = p[FirstBaseline].let { if (it == AlignmentLine.Unspecified) p.height else it }
    layout(c.maxWidth, c.maxHeight) { p.place((x.dp.roundToPx() - p.width * anchor).roundToInt(), y.dp.roundToPx() - b) }
}

/**
 * Children side by side on one baseline (a child with no baseline sits on it). [gaps] is the space after each child.
 * [centreFirst]: the row reports a width symmetric around the first child, so centring it centres that child.
 */
@Composable
private fun BaselineRow(gaps: List<Dp>, centreFirst: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { ms, _ ->
        val ps = ms.map { it.measure(Constraints()) }
        val bl = ps.map { p -> p[FirstBaseline].let { if (it == AlignmentLine.Unspecified) p.height else it } }
        val above = bl.maxOrNull() ?: 0
        val below = ps.indices.maxOfOrNull { ps[it].height - bl[it] } ?: 0
        val gapPx = gaps.map { it.roundToPx() }
        val w0 = ps.firstOrNull()?.width ?: 0
        val rest = ps.drop(1).sumOf { it.width } + gapPx.take(ps.size - 1).sum()
        val total = ps.sumOf { it.width } + gapPx.take((ps.size - 1).coerceAtLeast(0)).sum()
        val width = if (centreFirst) w0 + 2 * rest else total
        layout(width, above + below, mapOf(FirstBaseline to above)) {
            var x = if (centreFirst) rest else 0
            ps.forEachIndexed { i, p ->
                p.place(x, above - bl[i])
                x += p.width + gapPx.getOrElse(i) { 0 }
            }
        }
    }
}

@Composable
private fun T(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) =
    Text(text, modifier, color = color, style = style, maxLines = 1, softWrap = false)

/** A chevron whose outer height is [outer] dp, pointing left (toward a band on the left) or right. */
@Composable
private fun Chevron(left: Boolean, outer: Float, stroke: Float, run: Float, color: Color) {
    Canvas(Modifier.size((run + stroke).dp, outer.dp)) {
        val s = stroke.dp.toPx()
        val r = run.dp.toPx()
        val half = (outer.dp.toPx() - s) / 2
        val cy = size.height / 2
        val p = Path().apply {
            if (left) { moveTo(s / 2 + r, cy - half); lineTo(s / 2, cy); lineTo(s / 2 + r, cy + half) }
            else { moveTo(s / 2, cy - half); lineTo(s / 2 + r, cy); lineTo(s / 2, cy + half) }
        }
        drawPath(p, color, style = Stroke(s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Entry point: scales the 192 dp disc to the screen and draws [PacedDisc]. */
@Composable
internal fun PacedStepPage(s: Session, settings: ScreenSettings, clock: String = clockNow()) {
    val outer = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        val k = minOf(constraints.maxWidth, constraints.maxHeight) / (192f * outer.density)
        CompositionLocalProvider(LocalDensity provides Density(outer.density * k, 1f)) {
            PacedDisc(s, settings, outer.fontScale >= 1.15f)
        }
    }
}

private fun systemAnimationScale(ctx: android.content.Context): Float = runCatching {
    android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

@Composable
private fun PacedDisc(s: Session, settings: ScreenSettings, large: Boolean) {
    val step = s.step ?: return
    val unit = s.workout.paceUnit
    val rows = if (large) LARGE_ROWS else NORMAL_ROWS
    val mode = pacedMode(s)
    val j = s.judge
    val rep = if (mode == PacedMode.REST) s.lastRep else null
    val range = rep?.range ?: stepRange(step, s.progress.fraction) ?: PaceRange(null, null)
    val snapped = (if (rep == null) j?.range else null) ?: Grid.snapRange(range)
    val geo = remember(snapped) { RulerMath.geometry(snapped) }
    val inspect = LocalInspectionMode.current

    val state: JudgeState = when {
        s.paused -> JudgeState.PAUSED
        mode == PacedMode.REST -> JudgeState.IN
        j == null -> if (!s.workout.indoor && !s.gpsReady) JudgeState.GPS_WAIT else JudgeState.SETTLING
        else -> j.state
    }
    val pausedLook = state == JudgeState.PAUSED

    // Whole-rep verdict on the rest screen, the judge's OUT otherwise.
    val repSKm = rep?.avgSpeed?.takeIf { it > 0.1 }?.let { 1000.0 / it }
    val restV = if (rep != null && repSKm != null) restVerdict(rep.range, repSKm) else null
    val verdictSide: Side = when {
        restV != null -> restV.first
        state == JudgeState.OUT -> j?.side ?: Side.NONE
        else -> Side.NONE
    }
    val verdictBeyond = restV?.second ?: j?.beyondSKm ?: 0.0
    val severity = if (restV != null) maxOf(1, JudgeEngine.tierOf(restV.second)) else j?.severity ?: 1

    val hidePace = (mode == PacedMode.SHORT && (j?.stepAgeSec ?: 0.0) < 15.0) || state == JudgeState.GPS_WAIT
    val drawnSKm: Double? = when {
        pausedLook -> null
        rep != null -> repSKm?.let { Grid.snap(it, snapped.gridSKm) }
        hidePace -> null
        else -> j?.drawnSKm
    }
    val hollow = state == JudgeState.SETTLING || state == JudgeState.WEAK
    val showMarker = !pausedLook && state != JudgeState.GPS_WAIT
    val markerTarget = (drawnSKm?.let { geo.xOf(it) } ?: RulerMath.CENTRE).toFloat()
    val markerX = remember { Animatable(markerTarget) }
    LaunchedEffect(markerTarget) { if (!inspect) markerX.animateTo(markerTarget, tween(250, easing = EaseOutCubic)) else markerX.snapTo(markerTarget) }

    val band = settings.colourBand == ColourBand.ON
    val numberCue = settings.cueRow == CueRow.NUMBER

    // Wash
    val ws = if (pausedLook || mode == PacedMode.REST) WashState(false) else s.wash ?: WashState(false)
    val ctx = LocalContext.current
    val animator = remember { WashAnimator(if (inspect) 0f else systemAnimationScale(ctx)) }
    var frame by remember { mutableStateOf(WashFrame(0f, 0xFF000000L)) }
    val first = remember { booleanArrayOf(true) }
    if (!inspect) LaunchedEffect(ws) {
        // The first fade after the page appears (wake from ambient, back from Controls) takes 150 ms (v5 section 8); later onsets 300 ms.
        val t0 = androidx.compose.runtime.withFrameMillis { it }
        animator.target(t0, ws, if (first[0]) 150L else WashAnimator.IN_MS)
        first[0] = false
        var t = t0
        while (t - t0 <= 700) { frame = animator.sample(t); t = androidx.compose.runtime.withFrameMillis { it } }
        frame = animator.sample(t)
    }
    val washFrame = if (inspect) WashAnimator().also { it.snap(ws) }.sample(0) else frame

    // Cue row and range text swap sequentially: out 150 then in 150 (recovery), range 75 then cue 75 (onset).
    val showCue = verdictSide != Side.NONE
    val cueA = remember { Animatable(if (showCue) 1f else 0f) }
    val rangeA = remember { Animatable(if (showCue) 0f else 1f) }
    LaunchedEffect(showCue) {
        if (inspect) { cueA.snapTo(if (showCue) 1f else 0f); rangeA.snapTo(if (showCue) 0f else 1f); return@LaunchedEffect }
        if (showCue) { rangeA.animateTo(0f, tween(75)); cueA.animateTo(1f, tween(75)) }
        else { cueA.animateTo(0f, tween(150)); rangeA.animateTo(1f, tween(150)) }
    }
    var lastVerdict by remember { mutableStateOf(verdictSide to verdictBeyond) }
    if (showCue) lastVerdict = verdictSide to verdictBeyond

    val speech = listOfNotNull(
        stepLabelText(step),
        pacedSpeech(if (restV != null) (if (verdictSide == Side.NONE) JudgeState.IN else JudgeState.OUT) else state, verdictSide, verdictBeyond, unit),
        drawnSKm?.let { "Pace ${paceText(it, unit)} per ${if (unit == PaceUnit.MILE) "mile" else "kilometre"}" },
        rangeText(range, unit)?.let { "Target $it" },
    ).joinToString(". ")

    Box(
        Modifier.size(192.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = speech }
            .drawBehind {
                if (washFrame.alpha > 0f) drawRect(
                    Color(washFrame.argb).copy(alpha = washFrame.alpha), Offset(0f, 138.dp.toPx()), Size(size.width, size.height - 138.dp.toPx()),
                )
            },
    ) {
        val content = if (pausedLook) 0.5f else 1f
        Box(Modifier.fillMaxSize().alpha(content)) {
            Ring(s, step.kind, pausedLook)
            Ruler(
                geo = geo, snapped = snapped, laneY = rows.laneY, markerX = markerX.value, showMarker = showMarker, hollow = hollow,
                weak = state == JudgeState.WEAK, stretch = stretchOf(state, mode, verdictSide, severity, geo, markerX.value, restV != null, j),
            )

            // Row 1
            if (rows.flanks) Row1(s, mode, state, drawnSKm, repSKm, unit)
            else T(timeLeftText(s), ts(28f, 700), Rb.ink2, Modifier.atBaseline(96f, rows.timeLeftY))
            if (large) { /* LARGE: no labels, flanks or HR */ }

            // Hero
            Hero(s, mode, state, drawnSKm, rep != null, rows.heroY, unit)

            // Row 4
            val row4 = rows.row4Y
            Box(Modifier.fillMaxSize().alpha(rangeA.value)) { Row4Base(s, mode, range, unit, verdictSide == Side.NONE && restV != null, row4) }
            Box(Modifier.fillMaxSize().alpha(cueA.value)) {
                val (side, beyond) = lastVerdict
                if (side != Side.NONE) Cue(side, beyond, unit, numberCue, band, large, row4)
            }

            // HR
            if (rows.hr) s.heartRate?.let {
                BaselineRow(listOf(3.dp), modifier = Modifier.atBaseline(96f, 178f)) {
                    HeartIcon(Rb.ink2, 11.dp)
                    T("${it.toInt()}", PT.hr, Rb.ink)
                }
            }
        }

        // Step label (curved, top edge) and the PAUSED pill, which stays at full strength
        CurvedLayout(Modifier.padding(7.dp), anchor = 270f) {
            if (pausedLook) curvedText("PAUSED", color = Rb.ground, fontSize = 14.sp, fontWeight = FontWeight.Bold, background = Rb.ink)
            else curvedText(stepLabelText(step), color = kindColor(step.kind), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Stretch(val x0: Float, val x1: Float, val color: Color, val heightDp: Float)

/** The stretch from the nearer band edge to the marker: grey when near (and on short steps), hue while OUT, none otherwise. */
private fun stretchOf(
    state: JudgeState, mode: PacedMode, verdict: Side, severity: Int, geo: RulerGeometry, mx: Float, rest: Boolean, j: JudgeOut?,
): Stretch? {
    val left = geo.bandLeft.toFloat()
    val right = geo.bandRight.toFloat()
    val (a, b) = when {
        mx > right + 0.5f -> right to mx
        mx < left - 0.5f -> mx to left
        else -> return null
    }
    val hued = (state == JudgeState.OUT || (rest && verdict != Side.NONE)) && verdict != Side.NONE
    return when {
        hued -> Stretch(a, b, if (verdict == Side.FAST) Color(Tokens.PACE_FAST) else Color(Tokens.PACE_SLOW), when (severity.coerceIn(1, 3)) { 1 -> 10f; 2 -> 13f; else -> 16f })
        state == JudgeState.NEAR || state == JudgeState.SHORT -> Stretch(a, b, Rb.ink2, 10f)
        else -> null
    }
}

@Composable
private fun Ring(s: Session, kind: StepKind, paused: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val w = 4.dp.toPx()
        val inset = 96.dp.toPx() - 91.dp.toPx() + 0f
        val tl = Offset(inset, inset)
        val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
        if (paused) {
            val d = floatArrayOf(6.dp.toPx(), 5.dp.toPx())
            drawArc(Rb.ink3, 0f, 360f, false, tl, sz, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(d)))
        } else {
            drawArc(Rb.track, 0f, 360f, false, tl, sz, style = Stroke(w))
            val f = s.progress.fraction.toFloat().coerceIn(0f, 1f)
            if (f > 0f) drawArc(kindColor(kind), -90f, 360f * f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Ruler(
    geo: RulerGeometry, snapped: SnappedRange, laneY: Float, markerX: Float, showMarker: Boolean, hollow: Boolean, weak: Boolean, stretch: Stretch?,
) {
    Canvas(Modifier.fillMaxSize()) {
        fun d(v: Float) = v.dp.toPx()
        val cy = d(laneY)
        // lane
        drawRoundRect(Rb.track, Offset(d(18f), cy - d(3f)), Size(d(156f), d(6f)), CornerRadius(d(3f)))
        // band
        val l = d(geo.bandLeft.toFloat())
        val r = d(geo.bandRight.toFloat())
        val bandColor = Color(Tokens.BAND)
        if (geo.twoSided) {
            drawRoundRect(bandColor, Offset(l, cy - d(8f)), Size(r - l, d(16f)), CornerRadius(d(3f)))
            bracket(l - d(3.5f), cy, feetRight = true)
            bracket(r + d(3.5f), cy, feetRight = false)
        } else {
            // open band: 100 % at the bracket, 15 % at the lane end
            val brush = if (geo.openToRight)
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bandColor, bandColor.copy(alpha = 0.15f)), startX = l, endX = r)
            else androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bandColor.copy(alpha = 0.15f), bandColor), startX = l, endX = r)
            drawRoundRect(brush, Offset(l, cy - d(8f)), Size(r - l, d(16f)), CornerRadius(d(3f)))
            if (geo.openToRight) bracket(l, cy, feetRight = true) else bracket(r, cy, feetRight = false)
        }
        // stretch
        stretch?.let { st ->
            drawRoundRect(st.color, Offset(d(st.x0), cy - d(st.heightDp) / 2), Size(d(st.x1 - st.x0), d(st.heightDp)), CornerRadius(d(2f)))
        }
        // marker: 12 x 32, black halo 3 dp under it
        if (showMarker) {
            val mx = d(markerX)
            val tl = Offset(mx - d(6f), cy - d(16f))
            val sz = Size(d(12f), d(32f))
            val cr = CornerRadius(d(6f))
            drawRoundRect(Color.Black, tl, sz, cr, style = Stroke(d(6f)))
            if (!hollow) drawRoundRect(Rb.ink, tl, sz, cr)
            else {
                drawRoundRect(Rb.ground, tl, sz, cr)
                val inset = d(1.5f)
                drawRoundRect(
                    if (weak) Rb.ink2 else Rb.ink, Offset(tl.x + inset, tl.y + inset), Size(sz.width - 2 * inset, sz.height - 2 * inset), CornerRadius(d(4.5f)),
                    style = Stroke(d(3f), pathEffect = if (weak) PathEffect.dashPathEffect(floatArrayOf(d(4f), d(3.5f))) else null),
                )
            }
        }
    }
}

/** A bracket: 3 dp stroke, 28 dp tall, 7 dp feet pointing to the band. */
private fun DrawScope.bracket(x: Float, cy: Float, feetRight: Boolean) {
    val foot = 7.dp.toPx() * if (feetRight) 1 else -1
    val h = 14.dp.toPx()
    val p = Path().apply { moveTo(x + foot, cy - h); lineTo(x, cy - h); lineTo(x, cy + h); lineTo(x + foot, cy + h) }
    drawPath(p, Rb.ink2, style = Stroke(3.dp.toPx(), join = StrokeJoin.Miter))
}

@Composable
private fun Row1(s: Session, mode: PacedMode, state: JudgeState, drawnSKm: Double?, repSKm: Double?, unit: PaceUnit) {
    val (leftLabel, leftValue, leftColor) = when (mode) {
        PacedMode.SHORT -> Triple("pace", drawnSKm?.let { paceText(it, unit) } ?: "--:--", Rb.ink)
        PacedMode.REST -> Triple("rep", repSKm?.let { paceText(it, unit) } ?: "--:--", Rb.ink2)
        else -> Triple("left", timeLeftText(s), Rb.ink2)
    }
    Cell(96f - 37f, leftLabel, leftValue, leftColor)
    when {
        mode == PacedMode.REST -> {
            val next = s.next?.let { stepRange(it, 0.0) }?.let { r -> listOfNotNull(r.fastSKm, r.slowSKm).average() }
            Cell(96f + 37f, "next target", next?.let { paceText(it, unit) } ?: "--", Rb.ink)
        }
        state == JudgeState.SETTLING && mode == PacedMode.PACED -> T("settling", PT.settling, Rb.ink3, Modifier.atBaseline(96f + 37f, 62f))
        else -> Cell(96f + 37f, "avg", s.stepAvgSpeed?.let { Format.pace(it, unit) } ?: "--", Rb.ink)
    }
}

@Composable
private fun Cell(cx: Float, label: String, value: String, color: Color) {
    T(label, PT.row1Label, Rb.ink3, Modifier.atBaseline(cx, 43f))
    T(value, PT.row1Value, color, Modifier.atBaseline(cx, 62f))
}

@Composable
private fun Hero(s: Session, mode: PacedMode, state: JudgeState, drawnSKm: Double?, rest: Boolean, y: Float, unit: PaceUnit) {
    if (state == JudgeState.GPS_WAIT && mode == PacedMode.PACED) {
        T("GPS...", PT.gps, Rb.ink3, Modifier.atBaseline(96f, y))
        return
    }
    val timeHero = mode == PacedMode.SHORT || mode == PacedMode.REST
    val text = if (timeHero) timeLeftText(s) else drawnSKm?.let { paceText(it, unit) } ?: s.judge?.displaySKm?.let { paceText(it, unit) } ?: "--:--"
    val dim = !timeHero && (state == JudgeState.SETTLING || state == JudgeState.WEAK || drawnSKm == null)
    BaselineRow(listOf(3.dp), centreFirst = true, modifier = Modifier.atBaseline(96f, y)) {
        T(text, PT.hero, if (dim) Rb.ink3 else Rb.ink)
        when {
            timeHero -> T("left", PT.heroUnit, Rb.ink3)
            state == JudgeState.WEAK -> Column { T("weak", PT.weak, Rb.ink2); T("GPS", PT.weak, Rb.ink2) }
            else -> T(Format.paceSuffix(unit), PT.heroUnit, Rb.ink3)
        }
    }
}

/** Range text, `ON PACE` on a good rest result, one-sided words, or `NEXT` in the last seconds. */
@Composable
private fun Row4Base(s: Session, mode: PacedMode, range: PaceRange, unit: PaceUnit, onPace: Boolean, y: Float) {
    val next = s.next
    val nearEnd = mode != PacedMode.REST && next != null && isNearEnd(s.progress)
    when {
        onPace -> T("ON PACE", PT.onPace, Rb.ink, Modifier.atBaseline(96f, y))
        nearEnd && next != null -> BaselineRow(listOf(2.dp, 4.dp), modifier = Modifier.atBaseline(96f, y)) {
            T("»", PT.row1Value, kindColor(next.kind))
            T(next.label.uppercase().take(7), PT.next, kindColor(next.kind))
            T(next.durationSec?.let { Format.duration(it.toDouble()) } ?: next.distanceM?.let { Format.distance(it, unit) } ?: "", PT.row1Value, Rb.ink2)
        }
        else -> {
            val one = oneSidedText(range, unit)
            if (one != null) BaselineRow(listOf(5.dp), modifier = Modifier.atBaseline(96f, y)) {
                T(one.first, PT.oneSided, Rb.ink2); T(one.second, PT.range, Rb.ink2)
            } else T(rangeText(range, unit) ?: "", PT.range, Rb.ink2, Modifier.atBaseline(96f, y))
        }
    }
}

/**
 * The correction row: unsigned digits with a chevron that points toward the band (fast side `[chev] 25 s/km`, slow side
 * `35 s/km [chev]`); Words adds EASE / PUSH. Chevron and word carry the hue with the wash off, ink with the wash on.
 */
@Composable
private fun Cue(side: Side, beyond: Double, unit: PaceUnit, number: Boolean, wash: Boolean, large: Boolean, y: Float) {
    val hue = if (wash) Rb.ink else if (side == Side.FAST) Color(Tokens.PACE_FAST) else Color(Tokens.PACE_SLOW)
    val unitColor = if (wash) Rb.ink2 else Rb.ink3
    val fast = side == Side.FAST
    val digits = deltaText(beyond, unit)
    if (number) {
        val chev: @Composable () -> Unit = { Chevron(left = fast, outer = 18.6f, stroke = 3.4f, run = 8.4f, color = hue) }
        if (fast) BaselineRow(listOf(5.dp, 2.dp), modifier = Modifier.atBaseline(96f, y)) {
            chev(); T(digits, PT.cueDigits(large), Rb.ink); T(deltaUnit(unit), PT.cueUnit, unitColor)
        } else BaselineRow(listOf(2.dp, 5.dp), modifier = Modifier.atBaseline(96f, y)) {
            T(digits, PT.cueDigits(large), Rb.ink); T(deltaUnit(unit), PT.cueUnit, unitColor); chev()
        }
    } else {
        val word = if (fast) "EASE" else "PUSH"
        val chev: @Composable () -> Unit = { Chevron(left = fast, outer = 12.1f, stroke = 2.8f, run = 5.1f, color = hue) }
        if (fast) BaselineRow(listOf(4.dp, 4.dp, 2.dp), modifier = Modifier.atBaseline(96f, y)) {
            chev(); T(word, PT.wordsWord, hue); T(digits, PT.wordsDigits, Rb.ink); T(deltaUnit(unit), PT.cueUnit, unitColor)
        } else BaselineRow(listOf(4.dp, 2.dp, 4.dp), modifier = Modifier.atBaseline(96f, y)) {
            T(word, PT.wordsWord, hue); T(digits, PT.wordsDigits, Rb.ink); T(deltaUnit(unit), PT.cueUnit, unitColor); chev()
        }
    }
}
