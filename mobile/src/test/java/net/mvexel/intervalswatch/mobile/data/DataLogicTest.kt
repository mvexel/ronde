package net.mvexel.intervalswatch.mobile.data

import net.mvexel.intervalswatch.mobile.app.Routes
import net.mvexel.intervalswatch.mobile.app.Tab
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsEntity
import net.mvexel.intervalswatch.mobile.data.db.StreamCodec
import net.mvexel.intervalswatch.mobile.data.db.decode
import net.mvexel.intervalswatch.mobile.data.db.encode
import net.mvexel.intervalswatch.mobile.data.db.isRondeExternalId
import net.mvexel.intervalswatch.mobile.data.plan.arrange
import net.mvexel.intervalswatch.mobile.data.plan.visibleForWatch
import net.mvexel.intervalswatch.mobile.feature.history.activityMeta
import net.mvexel.intervalswatch.mobile.feature.history.chartsPlaceholder
import net.mvexel.intervalswatch.mobile.feature.history.summaryRows
import net.mvexel.intervalswatch.shared.RecordingProtocol
import net.mvexel.intervalswatch.shared.Samples
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DataLogicTest {
    private val ws = Samples.workouts
    private val ids = ws.map { it.id }

    @Test fun watchGetsEverythingButHidden() {
        assertSame(ws, visibleForWatch(ws, emptyList()))
        assertEquals(ids.drop(1), visibleForWatch(ws, listOf(ids.first())).map { it.id })
        assertEquals(ids, visibleForWatch(ws, listOf(999_999L)).map { it.id }) // stale prefs are harmless
        assertTrue(visibleForWatch(ws, ids).isEmpty())
    }

    @Test fun arrangePinsFirstBySortOrderAndSplitsHidden() {
        val (a, b, c) = ids
        val prefs = listOf(
            PlannedWorkoutPrefsEntity(c, pinned = true, sortOrder = 1),
            PlannedWorkoutPrefsEntity(b, pinned = true, sortOrder = 2),
            PlannedWorkoutPrefsEntity(a, hidden = true, pinned = true),
            PlannedWorkoutPrefsEntity(424242, pinned = true),
        )
        val r = arrange(ws, prefs)
        assertEquals(listOf(c, b) + ids.drop(3), r.visible.map { it.workout.id })
        assertEquals(listOf(a), r.hidden.map { it.workout.id })
        assertTrue(r.hidden.single().hidden)
    }

    @Test fun streamCodecRoundTrips() {
        val v = doubleArrayOf(0.0, -1.5, 3.25, Double.MAX_VALUE, 40.123456789)
        assertArrayEquals(v, StreamCodec.decode(StreamCodec.encode(v)), 0.0)
        assertEquals(0, StreamCodec.decode(ByteArray(7)).size)
        val s = ActivityStreams("i1", time = doubleArrayOf(0.0, 1.0), latlng = doubleArrayOf(1.0, 2.0)).encode().decode()
        assertArrayEquals(doubleArrayOf(0.0, 1.0), s.time, 0.0)
        assertNull(s.watts)
        assertTrue(s.hasMap)
    }

    @Test fun rondeExternalIdMatchesWhatTheUploaderSends() {
        assertTrue(isRondeExternalId(RecordingProtocol.externalId("1761800000_tempo.fit")))
        assertFalse(isRondeExternalId("garmin-123"))
        assertFalse(isRondeExternalId(null))
    }

    @Test fun activityTextHelpers() {
        val a = ActivityEntity("i1", "Tempo", "Run", "2026-11-09T07:00:00", distanceM = 10_000.0, movingTimeSec = 3_000, avgHr = 150, fetchedAt = 0)
        val meta = activityMeta(a, LocalDate.of(2026, 11, 9))
        assertTrue(meta, meta.startsWith("Today · 07:00 · Run · "))
        assertEquals(listOf("Distance", "Moving time", "Avg heart rate"), summaryRows(a).map { it.first })
        assertEquals("No streams on the phone yet.", chartsPlaceholder(null))
        assertEquals("2 samples. Charts: coming soon.", chartsPlaceholder(ActivityStreams("i1", time = doubleArrayOf(0.0, 1.0))))
    }

    @Test fun detailRoutesBelongToTheirTab() {
        assertEquals(Tab.PLAN, Routes.tabOf(Routes.PLANNED_WORKOUT))
        assertEquals(Tab.PLAN, Routes.tabOf(Routes.plannedWorkout(5)))
        assertEquals(Tab.HISTORY, Routes.tabOf(Routes.ACTIVITY))
        assertEquals(Tab.WATCH, Routes.tabOf(Routes.WATCH_SCREENS))
        assertEquals(Tab.SETTINGS, Routes.tabOf(Routes.SETTINGS))
        assertNull(Routes.tabOf(null))
    }
}
