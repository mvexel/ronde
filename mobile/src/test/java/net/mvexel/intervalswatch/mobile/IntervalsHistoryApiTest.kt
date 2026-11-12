package net.mvexel.intervalswatch.mobile

import kotlinx.coroutines.runBlocking
import net.mvexel.intervalswatch.shared.StepKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/** Reads from fixtures under test/resources/intervals, written from the intervals.icu OpenAPI spec. */
fun fixture(name: String): String =
    requireNotNull(IntervalsHistoryApiTest::class.java.getResource("/intervals/$name")) { "missing fixture $name" }.readText()

/** Robolectric only because plannedWorkouts logs through android.util.Log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntervalsHistoryApiTest {
    private val settings = Settings(token = "k", athleteId = "0")
    private lateinit var server: MockWebServer
    private var nowMs = 1_000_000L
    private val gate = RateLimitGate { nowMs }
    private lateinit var api: IntervalsApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = IntervalsApi(baseUrl = server.url("/").toString().trimEnd('/'), gate = gate)
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    @Test fun listActivitiesSendsDateRangeLimitAndFields() = runBlocking {
        ok(fixture("activities.json"))
        val list = api.activities(settings, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 11), limit = 50)
        val req = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/athlete/0/activities", req.encodedPath)
        assertEquals("2026-10-28", req.queryParameter("oldest"))
        assertEquals("2026-11-11T23:59:59", req.queryParameter("newest"))
        assertEquals("50", req.queryParameter("limit"))
        val fields = req.queryParameter("fields")!!.split(',')
        assertTrue(fields.containsAll(listOf("id", "start_date_local", "external_id", "oauth_client_id", "device_name", "source", "analyzed")))

        assertEquals(listOf("i1001", "i1000", "i999"), list.map { it.id })
        val run = list[0]
        assertEquals("Tempo 3x10'", run.name)
        assertEquals(10234.5, run.distance!!, 0.0)
        assertEquals(3011, run.movingTime)
        assertEquals(152.0, run.averageHeartrate!!, 0.0)
        assertEquals(171.0, run.maxHeartrate!!, 0.0)
        assertEquals(64, run.trainingLoad)
        assertEquals(555001L, run.pairedEventId)
        assertEquals("2026-11-10T15:10:02.123Z", run.analyzed)
        assertTrue("latlng" in run.streamTypes!!)
        assertTrue(run.isRonde(null))
        assertFalse(run.isStravaStub)

        val ride = list[1]
        assertEquals(138.6, ride.averageHeartrate!!, 1e-9) // spec says int32, a float must not break parsing
        assertEquals(201.0, ride.averageWatts!!, 0.0)
        assertEquals("Garmin Edge 840", ride.deviceName)
        assertFalse(ride.isRonde("4242"))

        val stub = list[2]
        assertTrue(stub.isStravaStub)
        assertNull(stub.name)
        assertEquals("2026-11-05T06:30:00", stub.startDateLocal)
    }

    @Test fun recentListLeavesNewestOutSoItMeansNow() = runBlocking {
        ok("[]")
        assertTrue(api.activities(settings, LocalDate.of(2026, 10, 28)).isEmpty())
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("newest"))
        assertNull(url.queryParameter("limit"))
    }

    @Test fun rondeIsDetectedByOauthClientIdToo() {
        val a = IntervalsActivityJson.activities("""[{"id":"i5","oauth_client_id":4242,"source":"OAUTH_CLIENT"}]""").single()
        assertTrue(a.isRonde("4242"))
        assertFalse(a.isRonde(null))
        assertFalse(a.isRonde("77"))
    }

    @Test fun singleActivityWithIntervals() = runBlocking {
        ok(fixture("activity_i1001.json"))
        val a = api.activity(settings, "i1001", intervals = true)
        val req = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/activity/i1001", req.encodedPath)
        assertEquals("true", req.queryParameter("intervals"))
        assertEquals(2, a.intervals!!.size)
        assertEquals("WORK", a.intervals!![0].type)
        assertEquals(2, a.intervals!![0].startIndex)
        assertEquals(4.0, a.intervals!![0].endTime!!, 0.0)
    }

    @Test fun streamsAskForExplicitTypesAndKeepNullsAsNaN() = runBlocking {
        ok(fixture("streams_i1001.json"))
        val s = api.activityStreams(settings, "i1001", listOf("time", "velocity_smooth", "latlng")).associateBy { it.type }
        val req = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/activity/i1001/streams.json", req.encodedPath)
        assertEquals("time,velocity_smooth,latlng", req.queryParameter("types"))
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), s["time"]!!.data, 0.0)
        assertTrue(s["velocity_smooth"]!!.data[3].isNaN())
        assertNull(s["time"]!!.data2)
        assertEquals(-111.8905, s["latlng"]!!.data2!![5], 0.0)
    }

    @Test fun intervalsEndpointAndMap() = runBlocking {
        ok(fixture("intervals_i1001.json"))
        assertEquals(1, api.activityIntervals(settings, "i1001").size)
        assertEquals("/api/v1/activity/i1001/intervals", server.takeRequest().requestUrl!!.encodedPath)
        ok(fixture("map_i1000.json"))
        val route = api.activityMap(settings, "i1000")
        assertEquals("/api/v1/activity/i1000/map", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(listOf(40.70 to -111.95, 40.75 to -111.90, 40.80 to -111.85), route)
    }

    @Test fun pairedEventIsResolvedIntoSteps() = runBlocking {
        ok(fixture("event_555001.json"))
        val w = api.plannedEvent(settings, 555001)!!
        val req = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/athlete/0/events/555001", req.encodedPath)
        assertEquals("true", req.queryParameter("resolve"))
        assertEquals(8, w.steps.size) // warmup, 3 x (work, recover), cooldown
        assertEquals(StepKind.WARMUP, w.steps.first().kind)
        assertEquals(3.68, w.steps[1].primaryTarget!!.low, 1e-9)
        assertEquals(StepKind.REST, w.steps[2].kind)
    }

    @Test fun missingEventIsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}"""))
        assertNull(api.plannedEvent(settings, 1))
    }

    @Test fun rateLimitHonoursRetryAfterWithoutCallingAgain() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        try {
            api.activities(settings, LocalDate.of(2026, 11, 1))
            fail("expected 429")
        } catch (e: RateLimitedException) {
            assertEquals(120L, e.retryAfterSec)
        }
        nowMs += 60_000
        try {
            api.activity(settings, "i1")
            fail("still blocked")
        } catch (e: RateLimitedException) {
            assertEquals(60L, e.retryAfterSec)
        }
        assertEquals(1, server.requestCount) // the blocked call never went out
        nowMs += 61_000
        ok(fixture("activity_i1001.json"))
        assertEquals("i1001", api.activity(settings, "i1001").id)
        assertEquals(2, server.requestCount)
    }

    @Test fun rateLimitWithoutRetryAfterBacksOffByDefault() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        runCatching { api.activities(settings, LocalDate.of(2026, 11, 1)) }
        assertEquals(nowMs + RateLimitGate.DEFAULT_BACKOFF_SEC * 1000, gate.blockedUntilMs)
    }

    @Test fun plannedWorkouts429StillThrowsIOExceptionAndBlocksReads() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        try {
            api.plannedWorkouts(settings, LocalDate.of(2026, 11, 1))
            fail()
        } catch (e: IOException) {
            assertEquals("intervals.icu HTTP 429", e.message)
        }
        assertTrue(gate.blockedUntilMs > nowMs)
    }

    @Test fun authRejectionOnReads() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.activities(settings, LocalDate.of(2026, 11, 1))
            fail()
        } catch (e: AuthExpiredException) {
            // log in again
        }
    }

    @Test fun retryAfterFormats() {
        assertEquals(5L, parseRetryAfter("5"))
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter("soon"))
        val now = java.time.ZonedDateTime.parse("2026-11-11T10:00:00Z").toInstant().toEpochMilli()
        assertEquals(90L, parseRetryAfter("Wed, 11 Nov 2026 10:01:30 GMT", now))
    }
}
