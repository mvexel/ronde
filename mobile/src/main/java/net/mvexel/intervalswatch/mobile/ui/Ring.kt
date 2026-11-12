package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.mobile.RingArc
import net.mvexel.intervalswatch.mobile.ringArcs
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Verdict
import net.mvexel.intervalswatch.shared.WorkoutStep
import kotlin.math.max

/**
 * The result ring: the workout ring with the outcome drawn in. [verdicts] runs parallel to [steps]
 * (`PlanAnalysis.steps.map { it.verdict }`), and so does [reached] (false for a step the run never got to). A
 * missed rep (see [missedReps]) is drawn hollow; everything else is filled. With [verdicts] null and nothing
 * unreached it is the plain plan ring. Used by History rows (48 dp) and the Activity Detail hero (164 dp).
 */
@Composable
fun ResultRing(
    steps: List<WorkoutStep>,
    verdicts: List<Verdict?>?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    reached: List<Boolean>? = null,
    contentDescription: String? = null,
) = RingDisc(steps, modifier, size = size, missed = missedReps(steps, verdicts, reached), contentDescription = contentDescription)

/**
 * The workout ring on its black disc (the launcher icon): work thick amber, easy thin cyan, warm-up and
 * cool-down thin grey, a white tick at 12. [center] draws on top (e.g. the watch-order number); with a center
 * the tick is left out so it does not cross the number. No steps: one grey ring.
 */
@Composable
fun RingDisc(
    steps: List<WorkoutStep>,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    missed: List<Boolean>? = null,
    disc: Color = RondeTheme.colors.ringDisc,
    tick: Boolean = true,
    contentDescription: String? = null,
    center: (@Composable () -> Unit)? = null,
) {
    val c = RondeTheme.colors
    val kinds = RingKindColors(c.work, c.recovery, c.warmup)
    val described = contentDescription?.let { d -> Modifier.semantics { this.contentDescription = d } } ?: Modifier
    Box(modifier.size(size).then(described), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawRing(steps, missed, kinds, disc, c.tick, tick && center == null) }
        center?.invoke()
    }
}

/** Step-kind colours for [drawRing]; the brand values on every theme. */
data class RingKindColors(val work: Color, val recovery: Color, val warmup: Color) {
    fun of(k: StepKind) = when (k) {
        StepKind.ACTIVE -> work
        StepKind.REST -> recovery
        StepKind.WARMUP, StepKind.COOLDOWN -> warmup
    }
}

/**
 * "3 of 5": work steps in range, of work steps judged or never reached (an unreached rep counts as missed). Null
 * when nothing was judged.
 */
fun roundsInRange(steps: List<WorkoutStep>, verdicts: List<Verdict?>?, reached: List<Boolean>? = null): Pair<Int, Int>? {
    if (verdicts == null && reached == null) return null
    val judged = steps.indices.filter { i ->
        steps[i].kind == StepKind.ACTIVE && (verdicts?.getOrNull(i) != null || reached?.getOrNull(i) == false)
    }
    if (judged.isEmpty() || judged.none { verdicts?.getOrNull(it) != null }) return null
    return judged.count { verdicts?.getOrNull(it) == Verdict.IN } to judged.size
}

/**
 * Per step: true for a missed work rep, drawn hollow. A rep is missed when its verdict is set and not
 * [Verdict.IN], or when the run never reached it.
 */
fun missedReps(steps: List<WorkoutStep>, verdicts: List<Verdict?>?, reached: List<Boolean>? = null): List<Boolean> =
    steps.indices.map { i ->
        val v = verdicts?.getOrNull(i)
        steps[i].kind == StepKind.ACTIVE && ((v != null && v != Verdict.IN) || reached?.getOrNull(i) == false)
    }

/**
 * Geometry from docs/phone/design/tools/gen.py `ring()`: stroke 11 % of the size for work, 55 % of that for
 * the rest, radius inset by 1.4 strokes; a hollow arc keeps an outline of max(1.2 dp, 1.2 % of the size).
 */
fun DrawScope.drawRing(
    steps: List<WorkoutStep>,
    missed: List<Boolean>?,
    kinds: RingKindColors,
    disc: Color,
    tickColor: Color,
    tick: Boolean = true,
) {
    val d = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    if (disc.alpha > 0f) drawCircle(disc, radius = d / 2, center = Offset(cx, cy))
    val full = d * 0.11f
    val r = d / 2 - full * 1.4f
    val box = Size(2 * r, 2 * r)
    val topLeft = Offset(cx - r, cy - r)
    val arcs = ringArcs(steps).ifEmpty { listOf(RingArc(0f, 357f, StepKind.WARMUP)) }
    val outline = max(1.2.dp.toPx(), d * 0.012f)
    arcs.forEachIndexed { i, a ->
        val w = if (a.kind == StepKind.ACTIVE) full else full * 0.55f
        val color = kinds.of(a.kind)
        drawArc(color, a.start - 90f, a.sweep, useCenter = false, topLeft = topLeft, size = box, style = Stroke(w, cap = StrokeCap.Butt))
        if (steps.isNotEmpty() && missed?.getOrNull(i) == true && a.sweep > 2.2f) {
            drawArc(
                disc.takeIf { it.alpha > 0f } ?: Color.Black, a.start - 90f + 0.9f, a.sweep - 1.8f, useCenter = false,
                topLeft = topLeft, size = box, style = Stroke(w - 2 * outline, cap = StrokeCap.Butt),
            )
        }
    }
    if (tick) {
        // At 12 o'clock, across the ring.
        drawLine(tickColor, Offset(cx, cy - r + full * 0.9f), Offset(cx, cy - r - full * 0.9f), strokeWidth = max(1.5.dp.toPx(), d * 0.03f), cap = StrokeCap.Round)
    }
}
