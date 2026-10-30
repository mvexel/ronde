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

/** "Today", "Thu 5" and, when the plan has a start time, " 07:00" (ux L1). */
internal fun workoutWhen(w: Workout, today: LocalDate): String {
    val time = runCatching { LocalDateTime.parse(w.startLocal).toLocalTime() }.getOrNull()
    val at = if (time == null || time == java.time.LocalTime.MIDNIGHT) "" else " " + "%02d:%02d".format(time.hour, time.minute)
    return workoutDay(w, today) + at
}

/** "Synced 2 h ago" for the list footer (ux L3); [stale] when older than a day. */
internal fun syncedAgo(syncedAtMillis: Long, nowMillis: Long): String {
    val min = ((nowMillis - syncedAtMillis).coerceAtLeast(0) / 60_000)
    return when {
        min < 1 -> "Synced just now"
        min < 60 -> "Synced $min min ago"
        min < 24 * 60 -> "Synced ${min / 60} h ago"
        else -> "Synced ${min / (24 * 60)} d ago"
    }
}

internal fun syncStale(syncedAtMillis: Long, nowMillis: Long): Boolean = nowMillis - syncedAtMillis > 24 * 3_600_000L
