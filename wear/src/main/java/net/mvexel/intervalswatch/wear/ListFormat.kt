package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Workout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** "Today", "Tomorrow", else "Thu 5" (ux L1). */
internal fun workoutDay(w: Workout, today: LocalDate): String {
    val date = runCatching { LocalDateTime.parse(w.startLocal).toLocalDate() }.getOrNull() ?: return w.startLocal.take(10)
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${date.dayOfMonth}"
    }
}

/** What the detail screen says under Start for step [i]: "WORK 2/3 · 10:00 @ 4:39–4:25". */
internal fun stepDescription(w: Workout, i: Int): String {
    val s = w.steps.getOrNull(i) ?: return ""
    val length = when {
        s.durationSec != null -> Format.duration(s.durationSec!!.toDouble())
        s.distanceM != null -> Format.distance(s.distanceM!!, w.paceUnit)
        else -> "lap"
    }
    val target = s.primaryTarget?.let { " @ " + Format.target(it, 0.0, w.paceUnit, w.sport) } ?: ""
    return listOfNotNull(s.label.uppercase(), s.repeat).joinToString(" ") + " · $length$target"
}
