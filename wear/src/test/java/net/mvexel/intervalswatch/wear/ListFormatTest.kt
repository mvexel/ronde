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
}
