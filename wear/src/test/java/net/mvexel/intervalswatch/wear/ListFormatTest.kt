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
}
