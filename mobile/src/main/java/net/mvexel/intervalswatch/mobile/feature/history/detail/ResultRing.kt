package net.mvexel.intervalswatch.mobile.feature.history.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.shared.StepKind

/**
 * The result ring (design.md, "Rules all three keep"): the workout ring on a black disc, work arcs thick amber,
 * easy thin cyan, warm-up/cool-down thin grey, a white tick at 12. A rep in range is a filled arc, a missed rep
 * a hollow one.
 */
@Composable
internal fun ResultRing(arcs: List<ResultArc>, description: String, size: Dp = 176.dp) {
    val p = detailPalette
    Canvas(Modifier.size(size).semantics { contentDescription = description }) {
        val d = this.size.minDimension
        val c = Offset(d / 2, d / 2)
        drawCircle(Color.Black, radius = d / 2, center = c)
        val full = d * 0.085f
        val r = d / 2 - full * 1.2f
        val hairline = 1.5.dp.toPx()
        arcs.forEach { a ->
            val color = p.kind(a.kind)
            val w = if (a.kind == StepKind.ACTIVE) full else full * 0.45f
            if (a.hollow) {
                val half = (w - hairline) / 2
                val outer = Rect(c, r + half)
                val inner = Rect(c, r - half)
                val path = Path().apply {
                    arcTo(outer, a.start - 90f, a.sweep, forceMoveTo = true)
                    arcTo(inner, a.start - 90f + a.sweep, -a.sweep, forceMoveTo = false)
                    close()
                }
                drawPath(path, color, style = Stroke(hairline))
            } else {
                drawArc(
                    color, startAngle = a.start - 90f, sweepAngle = a.sweep, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                    style = Stroke(w, cap = StrokeCap.Butt),
                )
            }
        }
        // 12 o'clock tick, from just outside the ring towards the centre.
        drawLine(Color.White, Offset(c.x, c.y - r - full * 0.7f), Offset(c.x, c.y - r + full * 2.2f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}
