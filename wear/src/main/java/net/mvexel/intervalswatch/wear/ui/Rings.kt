package net.mvexel.intervalswatch.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.wear.ringNowDeg
import net.mvexel.intervalswatch.wear.ringSegments

/** What a [WorkoutRing] shows besides the steps themselves (visual-design.md §5.2 variants). */
sealed interface RingVariant {
    /** Every step in its kind colour; work at full stroke, the rest at 55%. */
    data object Plain : RingVariant
    /** One step in full colour, the rest at 35% (detail bezel, list icons). */
    data class Highlight(val index: Int) : RingVariant
    /** A white radial tick at the current position (workout ring page). */
    data class Now(val stepIndex: Int, val fraction: Double) : RingVariant
    /** Summary: reached steps filled, missed reps hollow. [onTarget] says which reps ([isRep]) made it. */
    data class Result(val stepIndex: Int, val onTarget: Map<Int, Boolean>) : RingVariant
    /** Ambient: a thin track with the current step in ink2. */
    data class Hairline(val current: Int) : RingVariant
}

private fun StepKind.strength() = if (this == StepKind.ACTIVE) 1f else 0.55f

/** A rep is a work step with a target: only those can be on or off target (an open run's single step is not one). */
fun WorkoutStep.isRep() = kind == StepKind.ACTIVE && primaryTarget != null

/** The workout as a ring: each step an arc proportional to its duration. Fills its box; [stroke] is the arc width. */
@Composable
fun WorkoutRing(steps: List<WorkoutStep>, variant: RingVariant, stroke: Dp, modifier: Modifier = Modifier.fillMaxSize()) {
    val segments = remember(steps) { ringSegments(steps) }
    Canvas(modifier) {
        val hairline = variant is RingVariant.Hairline
        val w = (if (hairline) 1.5.dp else stroke).toPx()
        val tl = Offset(w / 2, w / 2)
        val sz = Size(size.width - w, size.height - w)
        if (segments.isEmpty()) {
            drawArc(if (variant is RingVariant.Result) Rb.ink else Rb.track, 0f, 360f, false, tl, sz, style = Stroke(w))
            return@Canvas
        }
        segments.forEach { seg ->
            val step = steps[seg.index]
            val kind = kindColor(step.kind)
            val start = -90f + seg.startDeg
            when (variant) {
                RingVariant.Plain -> drawArc(kind.copy(alpha = step.kind.strength()), start, seg.sweepDeg, false, tl, sz, style = Stroke(w))
                is RingVariant.Highlight ->
                    drawArc(kind.copy(alpha = if (seg.index == variant.index) 1f else 0.35f), start, seg.sweepDeg, false, tl, sz, style = Stroke(w))
                is RingVariant.Now -> {
                    val now = ringNowDeg(segments, variant.stepIndex, variant.fraction)
                    val done = (now - seg.startDeg).coerceIn(0f, seg.sweepDeg)
                    drawArc(kind.copy(alpha = 0.3f), start, seg.sweepDeg, false, tl, sz, style = Stroke(w))
                    if (done > 0f) drawArc(kind, start, done, false, tl, sz, style = Stroke(w))
                }
                is RingVariant.Result -> {
                    val filled = if (step.isRep()) variant.onTarget[seg.index] == true else seg.index <= variant.stepIndex
                    if (filled) drawArc(kind.copy(alpha = step.kind.strength()), start, seg.sweepDeg, false, tl, sz, style = Stroke(w))
                    else drawArc(kind.copy(alpha = 0.6f), start, seg.sweepDeg, false, tl, sz, style = Stroke(w / 4))
                }
                is RingVariant.Hairline ->
                    drawArc(if (seg.index == variant.current) Rb.ink2 else Rb.track, start, seg.sweepDeg, false, tl, sz, style = Stroke(w))
            }
        }
        if (variant is RingVariant.Now) {
            val now = ringNowDeg(segments, variant.stepIndex, variant.fraction)
            val r = (size.width - w) / 2
            val a = Math.toRadians((now - 90f).toDouble())
            val c = Offset(size.width / 2, size.height / 2)
            fun at(rad: Float) = Offset(c.x + rad * Math.cos(a).toFloat(), c.y + rad * Math.sin(a).toFloat())
            drawLine(Rb.ink, at(r - w * 0.9f), at(r + w * 0.9f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** Heart drawn as a path (two lobes and a point), not a glyph, so it renders the same on every watch font. */
@Composable
fun HeartIcon(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.92f)
            cubicTo(w * 0.05f, h * 0.58f, w * 0.0f, h * 0.2f, w * 0.25f, h * 0.12f)
            cubicTo(w * 0.40f, h * 0.07f, w * 0.5f, h * 0.2f, w * 0.5f, h * 0.28f)
            cubicTo(w * 0.5f, h * 0.2f, w * 0.60f, h * 0.07f, w * 0.75f, h * 0.12f)
            cubicTo(w * 1.0f, h * 0.2f, w * 0.95f, h * 0.58f, w * 0.5f, h * 0.92f)
            close()
        }
        drawPath(path, color)
    }
}

/** Text where each "♥" token is the drawn [HeartIcon] in the text colour. */
@Composable
fun HeartText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    if (!text.contains(HEART)) {
        androidx.wear.compose.material.Text(text, modifier, color = color, style = style, maxLines = 1)
        return
    }
    val parts = text.split(HEART)
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            append(part)
            if (i < parts.lastIndex) appendInlineContent("heart", HEART)
        }
    }
    val inline = mapOf(
        "heart" to androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(
                androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
            ),
        ) { HeartIcon(color, 11.dp) },
    )
    androidx.wear.compose.material.Text(annotated, modifier, color = color, style = style, maxLines = 1, inlineContent = inline)
}

const val HEART = "♥"
