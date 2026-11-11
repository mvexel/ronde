package net.mvexel.intervalswatch.mobile.feature.history.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.mvexel.intervalswatch.mobile.ui.RondeColors
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.shared.StepKind

/**
 * One chart card: step shading behind, target bands with dashed edges, the series in ink, and off-target
 * stretches (longer than the dwell) in the fast/slow colours. One y-axis per chart, all on the same time axis.
 */
@Composable
internal fun DetailChartCard(chart: ChartData, shading: List<Shade>, hrZones: List<Int>?, showLegend: Boolean) {
    DetailCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(chart.kind.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val caption = if (chart.kind == ChartKind.HEART_RATE && hrZones != null) "bpm · zones from intervals.icu" else chart.kind.caption
                Text(caption, style = MaterialTheme.typography.bodySmall, color = RondeTheme.colors.ink3)
            }
            Spacer(Modifier.height(10.dp))
            ChartCanvas(chart, shading, hrZones.takeIf { chart.kind == ChartKind.HEART_RATE })
            if (showLegend) {
                Spacer(Modifier.height(8.dp))
                ChartLegend(hasTargets = chart.bands.isNotEmpty(), shaded = shading.isNotEmpty(), overWord = if (chart.kind == ChartKind.PACE || chart.kind == ChartKind.SPEED) "fast" else "high", underWord = if (chart.kind == ChartKind.PACE || chart.kind == ChartKind.SPEED) "slow" else "low")
            }
        }
    }
}

@Composable
private fun ChartCanvas(c: ChartData, shading: List<Shade>, zones: List<Int>?) {
    val p = RondeTheme.colors
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.5.sp, color = p.ink3, fontFeatureSettings = "tnum")
    val desc = "${c.kind.title} chart"
    Canvas(Modifier.fillMaxWidth().height(if (c.kind == ChartKind.POWER) 100.dp else 130.dp).semantics { contentDescription = desc }) {
        val left = 34.dp.toPx()
        val right = size.width - if (zones != null) 24.dp.toPx() else 2.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val tMax = c.tMax.coerceAtLeast(1f)
        fun x(t: Float) = left + (right - left) * (t / tMax)
        fun y(v: Float): Float {
            val f = ((v - c.yMin) / (c.yMax - c.yMin)).coerceIn(-0.02f, 1.02f)
            return if (c.invert) top + (bottom - top) * f else bottom - (bottom - top) * f
        }
        // Ground and step shading.
        drawRect(p.tintWu, Offset(left, top), Size(right - left, bottom - top))
        shading.forEach { s ->
            if (s.kind == StepKind.WARMUP || s.kind == StepKind.COOLDOWN) return@forEach
            drawRect(p.tint(s.kind), Offset(x(s.startSec), top), Size(x(s.endSec) - x(s.startSec), bottom - top))
        }
        // Gridlines and y labels.
        c.yTicks.forEach { (v, text) ->
            val yy = y(v)
            if (yy < top - 1 || yy > bottom + 1) return@forEach
            drawLine(p.grid, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
            val m = measurer.measure(text, label)
            drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), yy - m.size.height / 2))
        }
        // HR zones: dotted bounds and the ladder at the right edge.
        if (zones != null) drawZones(zones, p, ::y, left, right, top, bottom, measurer, label)
        // Target bands.
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
        c.bands.forEach { b ->
            val y1 = y(if (c.invert) b.lo else b.hi)
            val y2 = y(if (c.invert) b.hi else b.lo)
            drawRect(p.band, Offset(x(b.startSec), y1), Size(x(b.endSec) - x(b.startSec), y2 - y1))
            drawLine(p.bandEdge, Offset(x(b.startSec), y1), Offset(x(b.endSec), y1), 1.dp.toPx(), pathEffect = dash)
            drawLine(p.bandEdge, Offset(x(b.startSec), y2), Offset(x(b.endSec), y2), 1.dp.toPx(), pathEffect = dash)
        }
        // The series, then the off-target stretches over it.
        drawSeries(c, ::x, ::y, ink, 1.75.dp.toPx()) { true }
        drawSeries(c, ::x, ::y, p.paceFast, 2.5.dp.toPx()) { c.flags[it] > 0 }
        drawSeries(c, ::x, ::y, p.paceSlow, 2.5.dp.toPx()) { c.flags[it] < 0 }
        // Time axis.
        DetailModel.timeTicks(c.tMax).forEach { (t, text) ->
            val m = measurer.measure(text, label)
            drawText(m, topLeft = Offset((x(t) - m.size.width / 2).coerceIn(left - m.size.width / 2, size.width - m.size.width.toFloat()), bottom + 4.dp.toPx()))
        }
    }
}

/** Draws runs of consecutive points where [include] holds (and the value is not NaN) as polylines. */
private fun DrawScope.drawSeries(c: ChartData, x: (Float) -> Float, y: (Float) -> Float, color: Color, width: Float, include: (Int) -> Boolean) {
    val path = Path()
    var pen = false
    for (i in c.t.indices) {
        val ok = !c.v[i].isNaN() && include(i)
        if (!ok) { pen = false; continue }
        val px = x(c.t[i]); val py = y(c.v[i])
        when {
            pen -> path.lineTo(px, py)
            // Start an off-target run at the previous point, so it joins the ink line without a gap.
            i > 0 && !c.v[i - 1].isNaN() -> { path.moveTo(x(c.t[i - 1]), y(c.v[i - 1])); path.lineTo(px, py) }
            else -> path.moveTo(px, py)
        }
        pen = true
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawZones(
    zones: List<Int>, p: RondeColors, y: (Float) -> Float, left: Float, right: Float, top: Float, bottom: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, label: TextStyle,
) {
    val bounds = listOf(Float.NEGATIVE_INFINITY) + zones.map { it.toFloat() } + Float.POSITIVE_INFINITY
    val dots = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
    zones.forEach { z ->
        val yy = y(z.toFloat())
        if (yy > top && yy < bottom) drawLine(p.grid, Offset(left, yy), Offset(right, yy), 1.dp.toPx(), pathEffect = dots)
    }
    val barX = right + 18.dp.toPx()
    for (k in 0 until (bounds.size - 1).coerceAtMost(5)) {
        val y1 = y(bounds[k + 1].coerceAtMost(Float.MAX_VALUE)).coerceIn(top, bottom)
        val y2 = y(bounds[k].coerceAtLeast(-Float.MAX_VALUE)).coerceIn(top, bottom)
        if (y2 - y1 < 1f) continue
        drawRect(p.hrZones[k], Offset(barX, y1), Size(5.dp.toPx(), y2 - y1 - 1.dp.toPx()))
        val m = measurer.measure("Z${k + 1}", label.copy(fontSize = 9.sp))
        if (y2 - y1 >= m.size.height * 0.8f) drawText(m, topLeft = Offset(barX - m.size.width - 3.dp.toPx(), (y1 + y2) / 2 - m.size.height / 2))
    }
}

@Composable
private fun ChartLegend(hasTargets: Boolean, shaded: Boolean, overWord: String, underWord: String) {
    val p = RondeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (shaded) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(p.tintWork, "work")
                Swatch(p.tintEasy, "easy")
                Swatch(p.tintWu, "warm-up / cool-down")
                if (hasTargets) Swatch(p.band, "target")
            }
        }
        if (hasTargets) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LineKey(p.paceFast, if (overWord == "fast") "▼ $overWord" else "▲ $overWord")
                LineKey(p.paceSlow, if (underWord == "slow") "▲ $underWord" else "▼ $underWord")
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(width = 14.dp, height = 10.dp).background(color))
    Spacer(Modifier.width(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LineKey(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(width = 14.dp, height = 2.5.dp).background(color))
    Spacer(Modifier.width(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
