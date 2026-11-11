package net.mvexel.intervalswatch.mobile.feature.history.detail

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import net.mvexel.intervalswatch.mobile.data.activity.ActivityDetailLoader
import net.mvexel.intervalswatch.mobile.data.activity.DetailLoad
import net.mvexel.intervalswatch.mobile.data.activity.InMemoryActivityRepository
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams
import net.mvexel.intervalswatch.mobile.fakes.MainDispatcherRule
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepStatus
import net.mvexel.intervalswatch.shared.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActivityDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeLoader : ActivityDetailLoader {
        val flow = MutableSharedFlow<DetailLoad>(replay = 8)
        val requested = mutableListOf<String>()
        override fun load(id: String): Flow<DetailLoad> { requested += id; return flow }
    }

    private fun vm(loader: ActivityDetailLoader, id: String = "i1001") = ActivityDetailViewModel(loader, id, main.dispatcher)

    @Test fun startsLoadingThenShowsAnalysedContent() {
        val loader = FakeLoader()
        val vm = vm(loader)
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.content)
        assertEquals(listOf("i1001"), loader.requested)

        loader.flow.tryEmit(DetailLoad.Loading)
        assertTrue(vm.state.value.loading)

        loader.flow.tryEmit(DetailLoad.Ready(DetailFixtures.intervalRun()))
        val st = vm.state.value
        assertFalse(st.loading)
        assertNull(st.error)
        val c = st.content!!
        assertNotNull(c.analysis)
        assertEquals(Rounds(3, 5), c.rounds)
        assertEquals(12, c.rows.size)
        assertEquals(listOf(Verdict.IN, Verdict.FAST, Verdict.IN, Verdict.SLOW, Verdict.IN), c.analysis!!.steps.filter { it.kind == StepKind.ACTIVE }.map { it.verdict })
    }

    @Test fun failedWithCachedKeepsTheCachedPageAndTheMessage() {
        val loader = FakeLoader()
        val vm = vm(loader)
        loader.flow.tryEmit(DetailLoad.Failed("No connection", DetailFixtures.unstructuredRun()))
        val st = vm.state.value
        assertFalse(st.loading)
        assertEquals("No connection", st.error)
        assertEquals("Lunch run", st.content?.detail?.name)
        assertFalse(st.content!!.structured)
        assertTrue(st.content!!.rows.isEmpty())
    }

    @Test fun failedWithoutCacheKeepsWhatWasShown() {
        val loader = FakeLoader()
        val vm = vm(loader)
        loader.flow.tryEmit(DetailLoad.Ready(DetailFixtures.stravaStub()))
        loader.flow.tryEmit(DetailLoad.Failed("Server error", null))
        assertEquals("Server error", vm.state.value.error)
        assertEquals("Morning Run", vm.state.value.content?.detail?.name)
    }

    @Test fun failedWithNothingShowsTheErrorAndRetryLoadsAgain() {
        var calls = 0
        val loader = object : ActivityDetailLoader {
            override fun load(id: String): Flow<DetailLoad> = flow {
                calls++
                if (calls == 1) throw IllegalStateException("boom") else emit(DetailLoad.Ready(DetailFixtures.stravaStub()))
            }
        }
        val vm = vm(loader)
        assertEquals("boom", vm.state.value.error)
        assertNull(vm.state.value.content)
        vm.retry()
        assertEquals(2, calls)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.content!!.detail.detailsAvailable)
    }

    @Test fun sameDetailIsAnalysedOnce() {
        val loader = FakeLoader()
        val vm = vm(loader)
        val d = DetailFixtures.intervalRun()
        loader.flow.tryEmit(DetailLoad.Ready(d))
        val first = vm.state.value.content
        loader.flow.tryEmit(DetailLoad.Ready(d))
        assertSame(first, vm.state.value.content)
    }

    @Test fun repositoryLoaderMapsRoomRows() = runTest {
        val repo = InMemoryActivityRepository(
            listOf(ActivityEntity("i9", "Spin", "VirtualRide", "2026-11-01T07:00:00", 20_000.0, 2_400, 2_500, avgHr = 140, fetchedAt = 0)),
            listOf(ActivityStreams("i9", time = doubleArrayOf(0.0, 1.0, 2.0), heartrate = doubleArrayOf(120.0, 150.0, 149.0), latlng = doubleArrayOf(40.7, -111.9, 40.71, -111.91))),
        )
        val vm = vm(RepositoryDetailLoader(repo), "i9")
        val d = vm.state.value.content!!.detail
        assertEquals(Sport.RIDE, d.sport)
        assertEquals(150.0, d.maxHr!!, 0.0)
        assertEquals(2, d.route!!.size)
        assertNull(d.plannedSteps)
        assertEquals(3, d.streams!!.size)

        val missing = vm(RepositoryDetailLoader(repo), "nope").state.value
        assertEquals("This activity is not on the phone.", missing.error)
        assertNull(missing.content)
    }

    @Test fun designRunRowsAndCorrections() {
        val c = DetailModel.build(DetailFixtures.intervalRun())
        val reps = c.rows.filter { it.kind == StepKind.ACTIVE }
        assertEquals(listOf(1, 2, 3, 4, 5), reps.map { it.rep })
        assertEquals("4:10–4:20", reps[0].target)
        assertEquals("Warm-up", c.rows.first().title)
        assertEquals("easy", c.rows.first().target)
        assertEquals("Easy 2:00", c.rows[2].title)
        assertNull(reps[0].correction)
        val fast = reps[1].correction!!
        assertEquals(Off.OVER, fast.off)
        assertEquals("▼", fast.chevron)
        assertEquals("fast", fast.word)
        assertTrue(fast.amount, fast.amount.endsWith("s/km"))
        val slow = reps[3].correction!!
        assertEquals("▲", slow.chevron)
        assertEquals("slow", slow.word)
        assertTrue(reps.all { it.status == StepStatus.COMPLETE && it.inRangeFraction != null })
        assertTrue(reps[1].inRangeFraction!! < reps[0].inRangeFraction!!)
        // Hollow arcs are exactly the missed reps.
        assertEquals(2, c.ring.count { it.hollow })
        assertTrue(c.ring.filter { it.hollow }.all { it.kind == StepKind.ACTIVE })
        assertEquals("Tue 10 Nov · 07:12 · Run", DetailModel.meta(c.detail, java.util.Locale.UK))
    }

    @Test fun chartsAndRouteFollowTheSteps() {
        val c = DetailModel.build(DetailFixtures.intervalRun())
        assertEquals(listOf(ChartKind.PACE, ChartKind.HEART_RATE, ChartKind.POWER), c.charts.map { it.kind })
        val pace = c.charts.first()
        assertTrue(pace.invert)
        assertEquals(5, pace.bands.size)
        assertTrue("rep 2 has a fast stretch", pace.flags.any { it > 0 })
        assertTrue("rep 4 has a slow stretch", pace.flags.any { it < 0 })
        assertTrue(pace.t.size <= DetailModel.MAX_POINTS)
        assertEquals(12, c.shading.size)
        val route = c.route!!
        assertEquals((1..5).toList(), route.markers.map { it.rep })
        assertTrue(route.segments.any { it.kind == StepKind.ACTIVE } && route.segments.any { it.kind == StepKind.REST })
    }

    @Test fun headlineSwitchesToSpeedForRide() {
        val ride = DetailModel.build(DetailFixtures.ride())
        assertTrue(ride.headline.any { it.label == "Avg speed" && it.unit == "km/h" })
        assertFalse(ride.headline.any { it.label == "Avg pace" })
        assertEquals(ChartKind.SPEED, ride.charts.first().kind)
        assertFalse(ride.charts.first().invert)
        val run = DetailModel.build(DetailFixtures.intervalRun())
        assertEquals(listOf("Distance", "Time", "Avg pace", "Avg HR", "Max HR", "Load"), run.headline.map { it.label })
        assertEquals("45:00", run.headline[1].value)
    }

    @Test fun stubAndGarminFlags() {
        val stub = DetailModel.build(DetailFixtures.stravaStub())
        assertTrue(stub.charts.isEmpty())
        assertNull(stub.route)
        assertNull(stub.rounds)
        assertFalse(stub.isGarmin)
        assertTrue(DetailModel.build(DetailFixtures.unstructuredRun()).isGarmin)
    }

    @Test fun dwellClearsShortStretches() {
        val t = DoubleArray(30) { it.toDouble() }
        val f = ByteArray(30).also { for (i in 2..6) it[i] = 1; for (i in 10..25) it[i] = -1 }
        DetailModel.applyDwell(t, f, 10.0)
        assertTrue((2..6).all { f[it] == 0.toByte() })
        assertTrue((10..25).all { f[it] == (-1).toByte() })
    }
}
