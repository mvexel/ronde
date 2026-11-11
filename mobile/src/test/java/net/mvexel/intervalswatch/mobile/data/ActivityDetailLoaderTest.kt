package net.mvexel.intervalswatch.mobile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.mvexel.intervalswatch.mobile.IntervalsActivityJson
import net.mvexel.intervalswatch.mobile.RateLimitedException
import net.mvexel.intervalswatch.mobile.data.activity.DetailLoad
import net.mvexel.intervalswatch.mobile.data.activity.IntervalsActivityDetailLoader
import net.mvexel.intervalswatch.mobile.data.activity.LatLon
import net.mvexel.intervalswatch.mobile.data.activity.LocalFit
import net.mvexel.intervalswatch.mobile.data.activity.LocalFitSource
import net.mvexel.intervalswatch.mobile.data.activity.detailErrorMessage
import net.mvexel.intervalswatch.mobile.data.activity.toEntity
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.fixture
import net.mvexel.intervalswatch.shared.EventDto
import net.mvexel.intervalswatch.shared.FitWriter
import net.mvexel.intervalswatch.shared.IntervalsParser
import net.mvexel.intervalswatch.shared.PlanVsActual
import net.mvexel.intervalswatch.shared.RecLap
import net.mvexel.intervalswatch.shared.RecPause
import net.mvexel.intervalswatch.shared.RecSample
import net.mvexel.intervalswatch.shared.Recording
import net.mvexel.intervalswatch.shared.RecordingProtocol
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.TimeSpan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityDetailLoaderTest {
    private lateinit var db: RondeDatabase
    private val clock = MutableClock(Instant.parse("2026-11-11T18:00:00Z"))
    private val list = IntervalsActivityJson.activities(fixture("activities.json"))
    private val remote = FakeHistoryRemote(list)
    private var localFit: LocalFit? = null
    private lateinit var loader: IntervalsActivityDetailLoader

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RondeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { db.activities().upsertAll(list.map { it.toEntity(0, "4242") }) }
        remote.details["i1001"] = IntervalsActivityJson.activity(fixture("activity_i1001.json"))
        remote.streams["i1001"] = IntervalsActivityJson.streams(fixture("streams_i1001.json"))
        val event = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(EventDto.serializer(), fixture("event_555001.json"))
        remote.planned[555001] = IntervalsParser.toWorkout(event)!!
        loader = IntervalsActivityDetailLoader(
            db.activities(), db.streams(), db.details(), remote, LocalFitSource { sec -> localFit?.takeIf { it.fileName.startsWith("${sec}_") } }, clock, "4242",
        )
    }

    @After fun close() = db.close()

    private fun load(id: String) = runBlocking { loader.load(id).toList() }

    @Test fun fetchesOnceThenServesFromRoom() {
        val first = load("i1001")
        assertEquals(DetailLoad.Loading, first[0])
        val d = (first[1] as DetailLoad.Ready).detail
        assertEquals(2, first.size)
        assertEquals(listOf("activity i1001", "streams i1001 time,velocity_smooth,heartrate,distance,cadence,latlng", "planned 555001"), remote.calls)

        assertEquals(Sport.RUN, d.sport)
        assertTrue(d.isRonde)
        assertTrue(d.detailsAvailable)
        assertEquals(171.0, d.maxHr!!, 0.0)
        val s = d.streams!!
        assertEquals(6, s.size)
        assertTrue(s.velocityMps!![3].isNaN())
        assertEquals(162.0, s.heartrate!![4], 0.0)
        assertEquals(90.0, s.cadence!![3], 0.0)
        assertNull(s.watts)
        assertEquals(6, d.route!!.size)
        assertEquals(LatLon(40.76, -111.89), d.route!!.first())
        assertEquals(listOf(TimeSpan(0.0, 2.0), TimeSpan(2.0, 4.0)), d.boundaries)
        assertEquals(8, d.plannedSteps!!.size)
        assertEquals(StepKind.WARMUP, d.plannedSteps!!.first().kind)
        // What the Detail screen does with it.
        PlanVsActual.analyze(d.plannedSteps!!, d.sport!!, s, d.boundaries)

        val again = load("i1001")
        assertEquals(1, again.size)
        assertEquals(d.copy(streams = null), (again[0] as DetailLoad.Ready).detail.copy(streams = null))
        assertEquals(3, remote.calls.size) // nothing new
    }

    @Test fun staleCacheIsShownThenReplaced() = runBlocking {
        load("i1001")
        db.activities().upsertAll(listOf(list[0].copy(analyzed = "2026-11-11T09:00:00Z").toEntity(0, "4242")))
        remote.details["i1001"] = remote.details["i1001"]!!.copy(analyzed = "2026-11-11T09:00:00Z", name = "Tempo (edited)")
        val out = load("i1001")
        assertEquals(2, out.size)
        assertEquals("Tempo 3x10'", (out[0] as DetailLoad.Ready).detail.name)
        assertEquals("Tempo (edited)", (out[1] as DetailLoad.Ready).detail.name)
        assertEquals(6, remote.calls.size)
        assertEquals(1, load("i1001").size) // fresh again
    }

    @Test fun failureWithoutCacheCarriesTheSummary() {
        remote.error = IOException("intervals.icu HTTP 500")
        val out = load("i1001")
        assertEquals(DetailLoad.Loading, out[0])
        val f = out[1] as DetailLoad.Failed
        assertEquals("intervals.icu HTTP 500", f.message)
        assertEquals("Tempo 3x10'", f.cached!!.name)
        assertNull(f.cached!!.streams)
    }

    @Test fun failureWithStaleCacheKeepsTheCachedDetail() = runBlocking {
        load("i1001")
        db.activities().upsertAll(listOf(list[0].copy(analyzed = "changed").toEntity(0, "4242")))
        remote.error = RateLimitedException(300)
        val out = load("i1001")
        assertEquals(1, out.size)
        assertTrue(out[0] is DetailLoad.Ready)
    }

    @Test fun stravaStubNeedsNoCall() {
        val out = load("i999")
        val d = (out.single() as DetailLoad.Ready).detail
        assertFalse(d.detailsAvailable)
        assertEquals("Strava activity", d.name)
        assertNull(d.streams)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun routeFallsBackToTheMapEndpoint() {
        remote.streams["i1000"] = listOf(
            net.mvexel.intervalswatch.mobile.IcuStream("time", doubleArrayOf(0.0, 1.0, 2.0), null),
            net.mvexel.intervalswatch.mobile.IcuStream("watts", doubleArrayOf(180.0, 200.0, 210.0), null),
        )
        remote.routes["i1000"] = listOf(40.70 to -111.95, 40.75 to -111.90)
        val d = (load("i1000").last() as DetailLoad.Ready).detail
        assertTrue(remote.calls.contains("route i1000"))
        assertFalse(remote.calls.any { it.startsWith("planned") }) // not paired
        assertEquals("Garmin Edge 840", d.deviceName)
        assertEquals(2, d.route!!.size)
        assertEquals(210.0, d.streams!!.watts!![2], 0.0)
        assertNull(d.boundaries)
    }

    @Test fun unknownIdIsFetched() {
        remote.details["i5"] = icu("i5", "2026-11-01T07:00:00")
        val out = load("i5")
        assertEquals("Run i5", (out.last() as DetailLoad.Ready).detail.name)
        assertEquals("activity i5", remote.calls.first())
    }

    private fun recording(elapsed: Double) = Recording(
        name = "Tempo", sport = Sport.RUN, indoor = false, startEpochMs = 1_794_322_931_000L,
        samples = listOf(RecSample(0.0, 0.0, 40.76, -111.89, hr = 120, speed = 3.0), RecSample(1.0, 3.0, 40.7601, -111.8901, hr = 130, speed = 3.1),
            RecSample(2.0, 6.0, hr = 140, speed = 3.2), RecSample(elapsed, 9.0, hr = 150, speed = 3.3)),
        laps = listOf(RecLap(0.0, 1.0, 0.0, 3.0, StepKind.WARMUP), RecLap(1.0, elapsed, 3.0, 9.0, StepKind.ACTIVE)),
        pauses = listOf(RecPause(2.0, 3.0)),
        elapsedSec = elapsed,
        totalM = 9.0,
    )

    @Test fun rondeRunWithItsFitOnThePhoneUsesTheRecording() {
        localFit = LocalFit(RecordingProtocol.fileName(1_794_322_931_000L, "Tempo", 555001), FitWriter.encode(recording(3120.0)))
        val d = (load("i1001").last() as DetailLoad.Ready).detail
        assertEquals(listOf("planned 555001"), remote.calls)
        assertEquals(listOf(TimeSpan(0.0, 1.0), TimeSpan(1.0, 3120.0)), d.boundaries)
        assertEquals(listOf(TimeSpan(2.0, 3.0)), d.streams!!.pauses)
        assertEquals(4, d.streams!!.size)
        assertEquals(2, d.route!!.size)
        assertEquals(8, d.plannedSteps!!.size)
        assertEquals(1, load("i1001").size) // cached
        assertEquals(1, remote.calls.size)
    }

    @Test fun fitThatDoesNotMatchIsIgnored() {
        localFit = LocalFit(RecordingProtocol.fileName(1_794_322_931_000L, "Tempo"), FitWriter.encode(recording(600.0)))
        load("i1001")
        assertEquals("activity i1001", remote.calls.first())
    }

    @Test fun errorMessages() {
        assertEquals("intervals.icu is busy. Try again in 2 min.", detailErrorMessage(RateLimitedException(90)))
        assertEquals("No connection to intervals.icu", detailErrorMessage(java.net.UnknownHostException("intervals.icu")))
    }
}
