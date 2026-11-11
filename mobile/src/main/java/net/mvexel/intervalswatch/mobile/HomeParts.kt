package net.mvexel.intervalswatch.mobile

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.mobile.ui.RingDisc
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.StepLength
import net.mvexel.intervalswatch.shared.WorkoutStep
import java.time.LocalDate
import java.time.LocalDateTime

/** One arc of a workout ring, in degrees clockwise from 12 o'clock. */
data class RingArc(val start: Float, val sweep: Float, val kind: StepKind)

/** Each step an arc proportional to its estimated duration ([StepLength], same as the watch), with gaps (visual-design.md §5.1). */
fun ringArcs(steps: List<WorkoutStep>): List<RingArc> {
    if (steps.isEmpty()) return emptyList()
    val weights = steps.map { StepLength.estimatedSec(it).toFloat().coerceAtLeast(1f) }
    val gap = if (steps.size > 12) 1.6f else 2.4f
    val total = weights.sum()
    val usable = 360f - gap * steps.size
    var at = 0f
    return steps.mapIndexed { i, s ->
        val sweep = usable * weights[i] / total
        RingArc(at + gap / 2, sweep, s.kind).also { at += sweep + gap }
    }
}

/** The workout ring on its black disc. Drawing lives in [net.mvexel.intervalswatch.mobile.ui.RingDisc]. */
@Composable
fun WorkoutRingDisc(steps: List<WorkoutStep>, size: Dp = 44.dp) = RingDisc(steps, size = size)

/** ✓ / ⟳ / ! in an outlined circle, in the deep colours (never bare bright fills on paper). */
@Composable
fun StatusIcon(state: UploadState) {
    val (glyph, color) = when (state) {
        UploadState.UPLOADED -> "✓" to MaterialTheme.colorScheme.onSurface
        UploadState.PENDING -> "⟳" to MaterialTheme.colorScheme.onSurfaceVariant
        UploadState.FAILED -> "!" to MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(28.dp).border(1.5.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Today", "Tomorrow", else "Sun 2 Nov". */
fun dayLabel(startLocal: String, today: LocalDate = LocalDate.now()): String {
    val d = runCatching { LocalDateTime.parse(startLocal).toLocalDate() }.getOrNull() ?: return startLocal.take(10)
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}"
    }
}

/** [dayLabel] plus " · 07:00" when the plan has a start time (ux F3). */
fun dayTimeLabel(startLocal: String, today: LocalDate = LocalDate.now()): String {
    val time = runCatching { LocalDateTime.parse(startLocal).toLocalTime() }.getOrNull()
    val at = if (time == null || time == java.time.LocalTime.MIDNIGHT) "" else " · %02d:%02d".format(time.hour, time.minute)
    return dayLabel(startLocal, today) + at
}

fun sportIcon(sport: net.mvexel.intervalswatch.shared.Sport): String = when (sport) {
    net.mvexel.intervalswatch.shared.Sport.RUN -> "🏃"
    net.mvexel.intervalswatch.shared.Sport.RIDE -> "🚴"
    net.mvexel.intervalswatch.shared.Sport.WALK -> "🚶"
    else -> "•"
}

/** "Today · 07:00 · 🏃 Run · 55 min" */
fun workoutMeta(w: Workout, today: LocalDate = LocalDate.now()): String = listOfNotNull(
    dayTimeLabel(w.startLocal, today),
    sportIcon(w.sport) + " " + w.sport.name.lowercase().replaceFirstChar { it.uppercase() } + if (w.indoor) " (indoor)" else "",
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

