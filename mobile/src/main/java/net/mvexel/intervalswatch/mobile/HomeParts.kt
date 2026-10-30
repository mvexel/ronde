package net.mvexel.intervalswatch.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Tokens
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import java.time.LocalDate
import java.time.LocalDateTime

/** One arc of a workout ring, in degrees clockwise from 12 o'clock. */
data class RingArc(val start: Float, val sweep: Float, val kind: StepKind)

/** Each step an arc proportional to its duration, with gaps (visual-design.md §5.1). */
fun ringArcs(steps: List<WorkoutStep>): List<RingArc> {
    if (steps.isEmpty()) return emptyList()
    val weights = steps.map { (it.durationSec ?: (it.distanceM?.let { m -> m / 3.0 }?.toInt()) ?: 300).coerceAtLeast(1).toFloat() }
    val gap = if (steps.size > 12) 1.6f else 2.4f
    val total = weights.sum()
    val usable = 360f - gap * steps.size
    var at = 0f
    return steps.mapIndexed { i, s ->
        val sweep = usable * weights[i] / total
        RingArc(at + gap / 2, sweep, s.kind).also { at += sweep + gap }
    }
}

private fun kindColor(k: StepKind) = Color(
    when (k) {
        StepKind.ACTIVE -> Tokens.WORK
        StepKind.REST -> Tokens.RECOVERY
        StepKind.WARMUP, StepKind.COOLDOWN -> Tokens.WARMUP
    },
)

/** The workout ring on the black watch disc; work at full stroke, the rest thinner. */
@Composable
fun WorkoutRingDisc(steps: List<WorkoutStep>, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val full = this.size.minDimension * 0.11f
            val inset = full * 1.4f
            val arcs = ringArcs(steps).ifEmpty { listOf(RingArc(0f, 357f, StepKind.WARMUP)) }
            arcs.forEach { a ->
                val w = if (a.kind == StepKind.ACTIVE) full else full * 0.55f
                drawArc(
                    kindColor(a.kind), startAngle = a.start - 90f, sweepAngle = a.sweep, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
                    style = Stroke(w, cap = StrokeCap.Butt),
                )
            }
        }
    }
}

/** ✓ / ⟳ / ! in an outlined circle, in the deep colours (never bare bright fills on paper). */
@Composable
fun StatusIcon(state: UploadState) {
    val (glyph, color) = when (state) {
        UploadState.UPLOADED -> "✓" to MaterialTheme.colorScheme.onSurface
        UploadState.PENDING -> "⟳" to MaterialTheme.colorScheme.tertiary
        UploadState.FAILED -> "!" to MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(28.dp).border(1.5.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Today", "Tomorrow", else "Thu 5". */
fun dayLabel(startLocal: String, today: LocalDate = LocalDate.now()): String {
    val d = runCatching { LocalDateTime.parse(startLocal).toLocalDate() }.getOrNull() ?: return startLocal.take(10)
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${d.dayOfMonth}"
    }
}

/** "Today · Run · 55 min" */
fun workoutMeta(w: Workout, today: LocalDate = LocalDate.now()): String = listOfNotNull(
    dayLabel(w.startLocal, today),
    w.sport.name.lowercase().replaceFirstChar { it.uppercase() } + if (w.indoor) " (indoor)" else "",
    w.totalDurationSec?.let { "${(it + 30) / 60} min" },
).joinToString(" · ")

/** Condensed step list, e.g. "10:00 · 4× 5:00 · 5:00"; at most [max] pieces. */
fun planLine(w: Workout, max: Int = 4): String {
    val parts = mutableListOf<String>()
    var i = 0
    while (i < w.steps.size) {
        val s = w.steps[i]
        var n = 1
        while (i + n < w.steps.size && w.steps[i + n].kind == s.kind && w.steps[i + n].durationSec == s.durationSec && w.steps[i + n].distanceM == s.distanceM) n++
        val len = s.durationSec?.let { Format.duration(it.toDouble()) } ?: s.distanceM?.let { "${it.toInt()} m" } ?: "lap"
        parts += if (n > 1) "$n× $len" else len
        i += n
    }
    return if (parts.size > max) parts.take(max).joinToString(" · ") + " …" else parts.joinToString(" · ")
}

/** Open recordings are titled "Open run/ride/walk"; the label makes that explicit (T10 I1). */
fun isOpenRecording(r: RecordingItem) = r.title.startsWith("Open", ignoreCase = true)

