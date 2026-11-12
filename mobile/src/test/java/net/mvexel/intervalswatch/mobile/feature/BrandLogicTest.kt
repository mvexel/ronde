package net.mvexel.intervalswatch.mobile.feature

import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.feature.history.HistoryFilter
import net.mvexel.intervalswatch.mobile.feature.history.HistoryRow
import net.mvexel.intervalswatch.mobile.feature.history.statLine
import net.mvexel.intervalswatch.mobile.feature.history.weekGroups
import net.mvexel.intervalswatch.mobile.ui.missedReps
import net.mvexel.intervalswatch.mobile.ui.roundsInRange
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Verdict
import net.mvexel.intervalswatch.shared.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BrandLogicTest {
    private fun step(kind: StepKind) = WorkoutStep("s", kind, durationSec = 60)
    private val steps = listOf(step(StepKind.WARMUP), step(StepKind.ACTIVE), step(StepKind.REST), step(StepKind.ACTIVE), step(StepKind.COOLDOWN))

    @Test fun resultRingCountsAndHollowsOnlyMissedWork() {
        val v = listOf(null, Verdict.IN, Verdict.SLOW, Verdict.FAST, null)
        assertEquals(1 to 2, roundsInRange(steps, v))
        // A rest step is never hollow, even with a verdict.
        assertEquals(listOf(false, false, false, true, false), missedReps(steps, v))
        assertNull(roundsInRange(steps, null))
        assertEquals(List(5) { false }, missedReps(steps, null))
    }

    @Test fun unreachedRepIsHollowAndCountsAsMissed() {
        val v = listOf(null, Verdict.IN, null, null, null)
        val reached = listOf(true, true, true, false, false)
        assertEquals(listOf(false, false, false, true, false), missedReps(steps, v, reached))
        assertEquals(1 to 2, roundsInRange(steps, v, reached))
        // Nothing judged at all: no "rounds in range" line.
        assertNull(roundsInRange(steps, List(5) { null }, reached))
    }

    private fun a(id: String, type: String, start: String, ronde: Boolean = false) =
        ActivityEntity(id, id, type, start, 10_000.0, 3_600, fetchedAt = 0, isRonde = ronde, avgSpeed = 3.0, avgHr = 140, avgPower = 200)

    @Test fun historyFiltersAndWeeks() {
        val rows = listOf(
            a("i4", "Run", "2026-11-10T07:00:00", ronde = true), a("i3", "VirtualRide", "2026-11-09T07:00:00"),
            a("i2", "Hike", "2026-11-04T07:00:00"), a("i1", "TrailRun", "2026-10-20T07:00:00"),
        ).map { HistoryRow(it) }
        assertEquals(listOf("i4", "i1"), rows.filter { HistoryFilter.RUN.matches(it.activity) }.map { it.activity.id })
        assertEquals(listOf("i3"), rows.filter { HistoryFilter.RIDE.matches(it.activity) }.map { it.activity.id })
        assertEquals(listOf("i2"), rows.filter { HistoryFilter.WALK.matches(it.activity) }.map { it.activity.id })
        assertEquals(listOf("i4"), rows.filter { HistoryFilter.RONDE.matches(it.activity) }.map { it.activity.id })

        val g = weekGroups(rows, LocalDate.of(2026, 11, 11))
        assertEquals(listOf("This week", "Last week", "Week of 19 Oct"), g.map { it.title })
        assertEquals("2 activities · 20.0 km · 2:00", g[0].caption)
    }

    @Test fun ridesShowPowerRunsShowPace() {
        assertEquals(listOf("10.00 km", "1:00:00", "200 W", "140 bpm"), statLine(a("r", "Ride", "2026-11-10T07:00:00")))
        assertEquals(listOf("10.00 km", "1:00:00", "5:33 /km", "140 bpm"), statLine(a("x", "Run", "2026-11-10T07:00:00")))
    }
}
