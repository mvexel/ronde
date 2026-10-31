package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import java.time.LocalDate
import kotlin.math.roundToInt
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

/** One row of the detail step list: a single step, or a whole repeat block folded into one line (ux D2). */
internal data class StepRowData(val label: String, val detail: String, val kind: StepKind)

private fun stepLength(w: Workout, s: WorkoutStep): String = when {
    s.durationSec != null -> Format.duration(s.durationSec!!.toDouble())
    s.distanceM != null -> Format.distance(s.distanceM!!, w.paceUnit)
    else -> "lap"
}

/**
 * Folds each repeat block ("1/4".."4/4" on consecutive steps) into "4×" with the first round's steps:
 * "4× 0:20 @ 5:30–5:00 / 1:00 easy". Steps outside a block stay one row each.
 */
private fun roundOf(s: WorkoutStep): Pair<Int, Int>? =
    s.repeat?.let { Regex("(\\d+)/(\\d+)$").find(it) }?.destructured?.let { (r, n) -> r.toInt() to n.toInt() }

internal fun collapsedSteps(w: Workout): List<StepRowData> {
    val rows = mutableListOf<StepRowData>()
    var i = 0
    while (i < w.steps.size) {
        val s = w.steps[i]
        val (_, total) = roundOf(s) ?: (0 to 0)
        if (total < 2) {
            val target = s.primaryTarget?.let { Format.target(it, 0.0, w.paceUnit, w.sport) } ?: ""
            rows += StepRowData(s.label, "${stepLength(w, s)}  $target".trim(), s.kind)
            i++
            continue
        }
        // The block runs while the round count stays "/total" and the round number never drops,
        // so two back-to-back blocks of the same size ("1/2 2/2 1/2 2/2") stay two rows.
        var prev = 0
        val block = w.steps.drop(i).takeWhile { st ->
            val r = roundOf(st)
            (r != null && r.second == total && r.first >= prev).also { if (it) prev = r!!.first }
        }
        val firstRound = roundOf(s)!!.first
        val round = block.takeWhile { roundOf(it)!!.first == firstRound }
        val parts = round.map { r ->
            val target = r.primaryTarget?.let { " @ " + Format.target(it, 0.0, w.paceUnit, w.sport) } ?: ""
            val name = if (r.kind == StepKind.REST) " ${r.label.lowercase()}" else ""
            stepLength(w, r) + target + name
        }
        rows += StepRowData("${total}×", parts.joinToString(" / "), round.firstOrNull { it.kind == StepKind.ACTIVE }?.kind ?: round.first().kind)
        i += block.size
    }
    return rows
}

/** The coach's notes, trimmed to [maxChars] with a trailing "…" (ux D3); null when there are none. */
internal fun notesPreview(description: String?, maxChars: Int = 120): String? {
    val t = description?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
    return if (t.length > maxChars) t.take(maxChars).trimEnd() + "…" else t
}

/** In-range samples over all samples of the work steps, as a whole percent; null with no samples (ux S3). Each array is [in range, total]. */
internal fun inTargetPercent(counts: Collection<IntArray>): Int? {
    val total = counts.sumOf { it[1] }
    if (total == 0) return null
    return (counts.sumOf { it[0] } * 100.0 / total).roundToInt()
}
