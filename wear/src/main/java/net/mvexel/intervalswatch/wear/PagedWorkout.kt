package net.mvexel.intervalswatch.wear

import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import net.mvexel.intervalswatch.shared.CrownPager
import net.mvexel.intervalswatch.shared.Field
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.WatchPage
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.shared.movePage
import net.mvexel.intervalswatch.shared.pagesFor
import net.mvexel.intervalswatch.wear.ui.Rb
import net.mvexel.intervalswatch.wear.ui.HeartText
import net.mvexel.intervalswatch.wear.ui.RbType
import net.mvexel.intervalswatch.wear.ui.RingVariant
import net.mvexel.intervalswatch.wear.ui.WorkoutRing
import net.mvexel.intervalswatch.wear.ui.kindColor

/** Rotary scroll pixels that make one crown detent; two detents is a deliberate turn (settings.md §3.1). Tune on the device. */
private const val DETENT_PX = 40f

/** What a step with neither time nor a known length weighs on the ring, in seconds. */
private const val NOMINAL_STEP_SEC = 120.0

/** Gap between ring arcs: 2.4° up to 12 steps, 1.6° above (visual-design.md §5.2). */
private fun gapDeg(steps: Int) = if (steps > 12) 1.6f else 2.4f

/** One arc of the Workout ring: where it starts and how long it is, in degrees from 12 o'clock. */
internal data class RingSegment(val index: Int, val startDeg: Float, val sweepDeg: Float)

/** Splits the circle between [steps] by duration (distance and lap steps count as [NOMINAL_STEP_SEC]), leaving a small gap between arcs. */
internal fun ringSegments(steps: List<WorkoutStep>): List<RingSegment> {
    if (steps.isEmpty()) return emptyList()
    val weights = steps.map { (it.durationSec ?: 0).toDouble().takeIf { d -> d > 0 } ?: NOMINAL_STEP_SEC }
    val total = weights.sum()
    val gap = gapDeg(steps.size)
    val usable = 360f - gap * steps.size
    var at = 0f
    return steps.indices.map { i ->
        val sweep = (weights[i] / total * usable).toFloat()
        RingSegment(i, at, sweep).also { at += sweep + gap }
    }
}

/** Degrees from 12 o'clock of "now": inside the current step's arc, by its progress. Past the last step: the full circle. */
internal fun ringNowDeg(segments: List<RingSegment>, stepIndex: Int, fraction: Double): Float {
    val seg = segments.getOrNull(stepIndex) ?: return 360f
    return seg.startDeg + seg.sweepDeg * fraction.coerceIn(0.0, 1.0).toFloat()
}

/** The pages of an active workout, turned with the crown; Step snaps back at each step change and when "Next" starts. */
@Composable
internal fun PagedWorkout(s: Session, settings: ScreenSettings) {
    val ctx = LocalContext.current
    val pages = pagesFor(settings.forSport(s.workout.sport))
    var page by remember { mutableStateOf(WatchPage.STEP) }
    val pager = remember { CrownPager() }
    val focus = remember { FocusRequester() }
    val nextNear = !s.paused && s.next != null && isNearEnd(s.progress)

    // Snap back to Step when the step changes or the Next warning starts; no timer otherwise.
    LaunchedEffect(s.stepIndex, nextNear) {
        if (page != WatchPage.STEP) { page = WatchPage.STEP; pager.reset() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val shown = if (page in pages) page else WatchPage.STEP
    Box(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e ->
                val dir = pager.turn(e.verticalScrollPixels / DETENT_PX, System.currentTimeMillis())
                if (dir != 0) {
                    val target = movePage(pages, shown, dir)
                    if (target != shown) { page = target; tick(ctx) }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        when (shown) {
            WatchPage.STEP -> ActiveWorkout(s, settings)
            WatchPage.TOTALS -> TotalsPage(s, settings)
            WatchPage.RING -> WorkoutRingPage(s)
        }
        if (shown != WatchPage.STEP) PageDots(pages, shown, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

private fun tick(ctx: android.content.Context) {
    val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    v.vibrate(VibrationEffect.createWaveform(Haptics.PAGE_TICK, -1))
}

/** Page dots on the bottom edge of the extra pages (the Step page's bottom edge belongs to its user slot). */
@Composable
internal fun PageDots(pages: List<WatchPage>, current: WatchPage, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        pages.forEach {
            Box(Modifier.size(5.dp).background(if (it == current) Rb.ink else Rb.ink3, CircleShape))
        }
    }
}

/** Totals: the whole session in four quadrants, clock and "TOTALS" on the top edge (settings.md §3). */
@Composable
internal fun TotalsPage(s: Session, settings: ScreenSettings, clock: String = clockNow()) {
    val fields = settings.forSport(s.workout.sport).totalsFields.take(4)
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        CurvedLayout(anchor = 270f) {
            curvedText("$clock · TOTALS", color = Rb.ink2, fontSize = RbType.edge, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().padding(top = 34.dp, bottom = 30.dp, start = 28.dp, end = 28.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            fields.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { Quadrant(s, it, clock) }
                }
            }
        }
    }
}

@Composable
private fun Quadrant(s: Session, field: Field, clock: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val value = fieldValue(s, field, clock)
        Text(value ?: fieldWaiting(s, field), style = RbType.partner, color = if (value == null) Rb.ink3 else Rb.ink, maxLines = 1, textAlign = TextAlign.Center)
        HeartText(quadrantLabel(s, field).ifEmpty { " " }, RbType.label, Rb.ink2)
    }
}

/** A quadrant's label: the flank label, but "total" spelled out for Total time (there is room). */
internal fun quadrantLabel(s: Session, field: Field): String =
    if (field == Field.TOTAL_TIME) "total" else fieldLabel(s, field)

/**
 * Workout ring: every step an arc on the bezel in its kind colour, done full and to-come dim, a white marker at now.
 * The middle names the current step, the next two, and the time left (settings.md §3).
 */
@Composable
internal fun WorkoutRingPage(s: Session) {
    val steps = s.workout.steps
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        WorkoutRing(steps, RingVariant.Now(s.stepIndex, s.progress.fraction), 9.dp)
        Column(Modifier.padding(horizontal = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            s.step?.let { StepRow(it, Rb.ink, RbType.body.copy(fontWeight = FontWeight.Bold)) }
            val upcoming = steps.drop(s.stepIndex + 1).take(2)
            upcoming.forEach { StepRow(it, Rb.ink2, RbType.label.copy(fontSize = 12.sp)) }
            Text(
                workoutLeftSec(s)?.let { (if (steps.any { st -> st.durationSec == null }) "~" else "") + totalTimeText(it) + " to go" } ?: "Last step",
                style = RbType.label, color = Rb.ink3, modifier = Modifier.padding(top = 4.dp), maxLines = 1,
            )
        }
    }
}

@Composable
private fun StepRow(step: WorkoutStep, color: Color, style: androidx.compose.ui.text.TextStyle) {
    val length = step.durationSec?.let { Format.duration(it.toDouble()) } ?: step.distanceM?.let { "%.0f m".format(it) } ?: "lap"
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(kindColor(step.kind), CircleShape))
        Text("${step.label} $length", style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
