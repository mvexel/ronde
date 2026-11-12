package net.mvexel.intervalswatch.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Tokens
import net.mvexel.intervalswatch.wear.ui.Rb

/** Next burn-in offset: a random walk of at most [step] dp per update, kept within +/- [limit] dp (v5 section 8, +/-3 dp). */
internal fun nextBurnInOffset(current: Int, rnd: Int, step: Int = 2, limit: Int = 3): Int =
    (current + (rnd.mod(2 * step + 1) - step)).coerceIn(-limit, limit)

private fun ambientKindColor(k: StepKind) = Color(
    when (k) { StepKind.ACTIVE -> Tokens.AMBIENT_WORK; StepKind.REST -> Tokens.AMBIENT_RECOVERY; else -> Tokens.AMBIENT_WARMUP },
)

private fun at(size: Float, weight: Int) = TextStyle(fontSize = size.sp, fontWeight = FontWeight(weight), fontFeatureSettings = "tnum")

/**
 * Ambient paced page (v5 section 8): outlines only, at most 15 % of pixels lit. Ring arc only, step label, time left,
 * range text as the hero, a ruler with outlines and a hollow average tick, then the step average. No HR, marker, stretch,
 * cue row, wash or hue; no `settling` / `weak GPS` words. Everything is translated by ([dx], [dy]) dp against burn-in.
 */
@Composable
internal fun PacedAmbient(s: Session, dx: Int = 0, dy: Int = 0) {
    val step = s.step ?: return
    val outer = LocalDensity.current
    val unit = s.workout.paceUnit
    val range = stepRange(step, s.progress.fraction) ?: PaceRange(null, null)
    val snapped = Grid.snapRange(range)
    val geo = RulerMath.geometry(snapped)
    val avgSKm = s.stepAvgSpeed?.takeIf { it > 0.1 }?.let { 1000.0 / it }
    BoxWithConstraints(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        val k = minOf(constraints.maxWidth, constraints.maxHeight) / (192f * outer.density)
        CompositionLocalProvider(LocalDensity provides Density(outer.density * k, 1f)) {
            Box(Modifier.size(192.dp).clip(androidx.compose.foundation.shape.CircleShape).offset(dx.dp, dy.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val d = { v: Float -> v.dp.toPx() }
                    // progress arc only, 2 dp, no track
                    val inset = d(5f)
                    val f = s.progress.fraction.toFloat().coerceIn(0f, 1f)
                    if (f > 0f) drawArc(ambientKindColor(step.kind), -90f, 360f * f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(d(2f), cap = StrokeCap.Round))
                    val cy = d(120f)
                    val line = Color(Tokens.AMBIENT_LINE)
                    val l = d(geo.bandLeft.toFloat())
                    val r = d(geo.bandRight.toFloat())
                    // lane hairlines from the lane ends to 9 dp outside the band
                    drawLine(line, Offset(d(18f), cy), Offset(maxOf(d(18f), l - d(9f)), cy), d(2f))
                    drawLine(line, Offset(minOf(d(174f), r + d(9f)), cy), Offset(d(174f), cy), d(2f))
                    // band outline, no fill
                    drawRoundRect(line, Offset(l, cy - d(8f)), Size(r - l, d(16f)), CornerRadius(d(3f)), style = Stroke(d(2f)))
                    fun bracket(x: Float, feetRight: Boolean) {
                        val foot = d(7f) * if (feetRight) 1 else -1
                        val h = d(14f)
                        drawPath(Path().apply { moveTo(x + foot, cy - h); lineTo(x, cy - h); lineTo(x, cy + h); lineTo(x + foot, cy + h) }, Rb.ink3, style = Stroke(d(2f)))
                    }
                    if (geo.twoSided) { bracket(l - d(3.5f), true); bracket(r + d(3.5f), false) }
                    else if (geo.openToRight) bracket(l, true) else bracket(r, false)
                    // hollow average tick, 8 x 28 dp
                    avgSKm?.let {
                        val x = d(geo.xOf(Grid.snap(it, snapped.gridSKm)).toFloat())
                        drawRoundRect(Rb.ink2, Offset(x - d(4f), cy - d(14f)), Size(d(8f), d(28f)), CornerRadius(d(3f)), style = Stroke(d(2f)))
                    }
                }
                CurvedLayout(Modifier.padding(7.dp), anchor = 270f) {
                    curvedText(stepLabelText(step), color = Rb.ink2, fontSize = 14.sp, fontWeight = FontWeight.Normal)
                }
                val left = if (s.paused) "paused" else ambientLeft(s)
                AmbientText(left, at(22f, 300), Rb.ink2, 64f)
                val rt = rangeText(range, unit)
                if (rt != null) AmbientText(rt, at(38f, 300), Rb.ink, 97f)
                else oneSidedText(range, unit)?.let { AmbientText(it.first + " " + it.second, at(26f, 300), Rb.ink, 97f) }
                AmbientText(ambientAvg(s, unit), at(22f, 300), Rb.ink2, 159f)
            }
        }
    }
}

private fun ambientLeft(s: Session): String {
    val p = s.progress
    return when {
        p.remainingSec != null -> net.mvexel.intervalswatch.shared.Format.duration(p.remainingSec!!) + " left"
        p.remainingM != null -> net.mvexel.intervalswatch.shared.Format.distance(p.remainingM!!, s.workout.paceUnit) + " left"
        else -> net.mvexel.intervalswatch.shared.Format.duration(p.elapsedSec)
    }
}

private fun ambientAvg(s: Session, unit: PaceUnit): String =
    "avg " + (s.stepAvgSpeed?.let { net.mvexel.intervalswatch.shared.Format.pace(it, unit) } ?: "--")

@Composable
private fun AmbientText(text: String, style: TextStyle, color: Color, baseline: Float) {
    Text(text, Modifier.atBaseline(96f, baseline), color = color, style = style, maxLines = 1, softWrap = false)
}
