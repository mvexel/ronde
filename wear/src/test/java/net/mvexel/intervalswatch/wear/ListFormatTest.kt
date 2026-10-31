package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.shared.StepKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ListFormatTest {
    private fun at(start: String) = Workout(1, "t", Sport.RUN, startLocal = start, steps = emptyList())

    @Test fun daysReadAsWords() {
        val today = LocalDate.of(2026, 10, 30)
        assertEquals("Today", workoutDay(at("2026-10-30T07:00:00"), today))
        assertEquals("Tomorrow", workoutDay(at("2026-10-31T07:00:00"), today))
        assertEquals("Thu 5", workoutDay(at("2026-11-05T07:00:00"), today))
    }

    @Test fun stepDescriptionWithoutTarget() {
        val w = Workout(1, "t", Sport.RUN, startLocal = "2026-10-30T07:00:00", steps = listOf(WorkoutStep("Work", StepKind.ACTIVE, durationSec = 600, repeat = "2/3")))
        assertEquals("WORK 2/3 · 10:00", stepDescription(w, 0))
    }

    @Test fun whenShowsTimeOnlyIfNotMidnight() {
        val today = LocalDate.parse("2026-10-30")
        assertEquals("Today 07:05", workoutWhen(at("2026-10-30T07:05:00"), today))
        assertEquals("Tomorrow", workoutWhen(at("2026-10-31T00:00:00"), today))
    }

    @Test fun syncedAgoWording() {
        val now = 100L * 24 * 3_600_000
        assertEquals("Synced just now", syncedAgo(now - 30_000, now))
        assertEquals("Synced 5 min ago", syncedAgo(now - 5 * 60_000, now))
        assertEquals("Synced 2 h ago", syncedAgo(now - 2 * 3_600_000, now))
        assertEquals("Synced 2 d ago", syncedAgo(now - 49 * 3_600_000, now))
        assertEquals(false, syncStale(now - 23 * 3_600_000, now))
        assertEquals(true, syncStale(now - 25 * 3_600_000, now))
    }

    @Test fun repeatBlockCollapsesToOneRow() {
        val t = net.mvexel.intervalswatch.shared.Target(net.mvexel.intervalswatch.shared.Metric.PACE, 3.0, 3.3)
        fun st(label: String, kind: StepKind, sec: Int, r: String?) = WorkoutStep(label, kind, durationSec = sec, repeat = r, targets = if (kind == StepKind.ACTIVE) listOf(t) else emptyList())
        val w = Workout(1, "t", Sport.RUN, startLocal = "2026-10-30T07:00:00", steps = listOf(
            st("Warm up", StepKind.WARMUP, 600, null),
            st("Work", StepKind.ACTIVE, 20, "1/2"), st("Easy", StepKind.REST, 60, "1/2"),
            st("Work", StepKind.ACTIVE, 20, "2/2"), st("Easy", StepKind.REST, 60, "2/2"),
        ))
        val rows = collapsedSteps(w)
        assertEquals(2, rows.size)
        assertEquals("2×", rows[1].label)
        assertEquals(true, rows[1].detail.startsWith("0:20 @ ") && rows[1].detail.endsWith(" / 1:00 easy"))
    }

    @Test fun notesAreTrimmed() {
        assertEquals(null, notesPreview("  "))
        assertEquals("keep it  relaxed".replace("  ", " "), notesPreview("keep it\n relaxed"))
        assertEquals(11, notesPreview("x".repeat(50), 10)!!.length)
    }

    @Test fun inTargetShare() {
        assertEquals(null, inTargetPercent(emptyList()))
        assertEquals(75, inTargetPercent(listOf(intArrayOf(45, 60), intArrayOf(15, 20))))
    }
}
