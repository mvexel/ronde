package net.mvexel.intervalswatch.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Where [value] sits on the gauge, 0..1 left to right; clamps to the ends. [lo]..[hi] is the bar's span. */
internal fun gaugeFraction(value: Double, lo: Double, hi: Double): Float =
    if (hi <= lo) 0.5f else ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()

/** The gauge's span: the target range plus half its width on each side (fr10-design.md (c)). */
internal fun gaugeSpan(range: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
    val margin = (range.endInclusive - range.start) / 2
    return (range.start - margin)..(range.endInclusive + margin)
}

/**
 * Range gauge under the hero: a bar with the target range as a lighter band, a filled marker for the
 * smoothed value (in [color], signal when out of range) and a hollow tick for the step average.
 * Faster / higher is to the right. A value beyond the bar pins to the end.
 */
@Composable
internal fun RangeGauge(
    range: ClosedFloatingPointRange<Double>,
    value: Double?,
    stepAvg: Double?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val span = gaugeSpan(range)
    Canvas(modifier.width(120.dp).height(12.dp)) {
        val barH = 10.dp.toPx()
        val top = (size.height - barH) / 2
        val r = CornerRadius(barH / 2)
        drawRoundRect(Rb.track, Offset(0f, top), Size(size.width, barH), r)
        val a = gaugeFraction(range.start, span.start, span.endInclusive) * size.width
        val b = gaugeFraction(range.endInclusive, span.start, span.endInclusive) * size.width
        drawRoundRect(Rb.ink3, Offset(a, top), Size(b - a, barH), CornerRadius(2.dp.toPx()))
        stepAvg?.let {
            val x = gaugeFraction(it, span.start, span.endInclusive) * size.width
            drawRoundRect(Rb.ink, Offset(x - 1.5.dp.toPx(), top - 1.dp.toPx()), Size(3.dp.toPx(), barH + 2.dp.toPx()), CornerRadius(1.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        value?.let {
            val x = gaugeFraction(it, span.start, span.endInclusive) * size.width
            drawCircle(color, radius = barH / 2 + 1.dp.toPx(), center = Offset(x.coerceIn(barH / 2, size.width - barH / 2), size.height / 2))
        }
    }
}
