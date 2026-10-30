package net.mvexel.intervalswatch.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class UploadTest {
    private val key = "sekrit-api-key-123"
    private val settings = Settings(token = key)
    private lateinit var server: MockWebServer
    private lateinit var api: IntervalsApi
    private val fit = byteArrayOf(14, 0x20, 0, 0, 1, 2, 3)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = IntervalsApi(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    private fun upload() = runBlocking { api.uploadActivity(settings, fit, "1_Ride.fit", "Ride", "intervalswatch-1") }

    @Test fun created201IsSuccess() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""[{"id":"i1"}]"""))
        upload()
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/api/v1/athlete/0/activities?"))
        assertTrue(req.path!!.contains("external_id=intervalswatch-1"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("""name="file"; filename="1_Ride.fit""""))
        assertNotNull(req.getHeader("Authorization"))
    }

    @Test fun duplicate200IsSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        upload()
    }

    @Test fun unauthorized401FailsWithoutKey() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val e = runCatching { upload() }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("API key rejected"))
        assertFalse(e.message!!.contains(key))
    }

    @Test fun serverError5xxFailsSoTheWorkerRetries() {
        server.enqueue(MockResponse().setResponseCode(503))
        val file = File.createTempFile("1_Ride", ".fit").apply { writeBytes(fit) }
        val failure = runBlocking { uploadRecordingFile(api, settings, file) }
        assertNotNull(failure)
        assertTrue(failure!!.reason.contains("503"))
        assertTrue(failure.retryable)
        assertFalse(failure.reason.contains(key))
        // Next attempt succeeds.
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        assertNull(runBlocking { uploadRecordingFile(api, settings, file) })
        file.delete()
    }

    @Test fun badRequest400IsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"bad fit\"}"))
        val file = File.createTempFile("1_Ride", ".fit").apply { writeBytes(fit) }
        val failure = runBlocking { uploadRecordingFile(api, settings, file) }!!
        assertFalse(failure.retryable)
        assertTrue(failure.reason.contains("bad fit"))
        file.delete()
    }

    @Test fun authAndRateLimitErrorsAreRetried() {
        for (code in listOf(401, 403, 429)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val file = File.createTempFile("1_Ride", ".fit").apply { writeBytes(fit) }
            assertTrue("$code", runBlocking { uploadRecordingFile(api, settings, file) }!!.retryable)
            file.delete()
        }
    }

    @Test fun uploadUsesTheRealWorkoutName() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        val f = File(System.getProperty("java.io.tmpdir"), net.mvexel.intervalswatch.shared.RecordingProtocol.fileName(1_790_000_000_000L, "Tempo 3x10' @ 95%"))
        f.writeBytes(fit)
        assertNull(runBlocking { uploadRecordingFile(api, settings, f) })
        val url = server.takeRequest().requestUrl!!
        assertEquals("Tempo 3x10' @ 95%", url.queryParameter("name"))
        assertEquals("intervalswatch-1790000000", url.queryParameter("external_id"))
        f.delete()
    }

    @Test fun uploadPairsWithThePlannedEvent() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        val f = File(System.getProperty("java.io.tmpdir"), net.mvexel.intervalswatch.shared.RecordingProtocol.fileName(1_790_000_000_000L, "Tempo", 123456789L))
        f.writeBytes(fit)
        assertNull(runBlocking { uploadRecordingFile(api, settings, f) })
        val url = server.takeRequest().requestUrl!!
        assertEquals("123456789", url.queryParameter("paired_event_id"))
        assertEquals("Tempo", url.queryParameter("name"))
        assertEquals("intervalswatch-1790000000", url.queryParameter("external_id"))
        f.delete()
    }

    @Test fun noEventIdSendsNoPairedParameter() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        val f = File(System.getProperty("java.io.tmpdir"), net.mvexel.intervalswatch.shared.RecordingProtocol.fileName(1_790_000_000_001L, "Free"))
        f.writeBytes(fit)
        assertNull(runBlocking { uploadRecordingFile(api, settings, f) })
        assertNull(server.takeRequest().requestUrl!!.queryParameter("paired_event_id"))
        f.delete()
    }

    @Test fun missingKeyDoesNotCallTheServer() {
        val file = File.createTempFile("1_Ride", ".fit")
        val failure = runBlocking { uploadRecordingFile(api, Settings(token = ""), file) }
        assertNotNull(failure)
        assertEquals(0, server.requestCount)
        file.delete()
    }

    private val oauth = Settings(token = "tok-123", authType = AuthType.OAUTH)

    @Test fun oauthUploadSendsBearer() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        runBlocking { api.uploadActivity(oauth, fit, "1_Ride.fit", "Ride", "intervalswatch-1") }
        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun apiKeyUploadSendsBasic() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        upload()
        assertEquals(okhttp3.Credentials.basic("API_KEY", key), server.takeRequest().getHeader("Authorization"))
    }

    @Test fun plannedWorkoutsSendsBearerForOAuthAndBasicForKey() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        runBlocking { api.plannedWorkouts(oauth); api.plannedWorkouts(settings) }
        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))
        assertEquals(okhttp3.Credentials.basic("API_KEY", key), server.takeRequest().getHeader("Authorization"))
    }

    @Test fun oauthAuthErrorDoesNotMentionApiKey() {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = runCatching { runBlocking { api.uploadActivity(oauth, fit, "1_Ride.fit", "Ride", "x") } }.exceptionOrNull()!!
        assertFalse(e.message!!, e.message!!.contains("API key"))
        assertFalse(e.message!!.contains("tok-123"))
    }

    @Test fun logoutRevokesThenClears() {
        server.enqueue(MockResponse().setResponseCode(200))
        var cleared = false
        runBlocking { logout(oauth, api) { cleared = true } }
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/disconnect-app", req.path)
        assertEquals("Bearer tok-123", req.getHeader("Authorization"))
        assertTrue(cleared)
    }

    @Test fun logoutClearsEvenWhenDisconnectFails() {
        server.enqueue(MockResponse().setResponseCode(500))
        var cleared = false
        runBlocking { logout(oauth, api) { cleared = true } }
        assertTrue(cleared)
        server.shutdown()
        cleared = false
        runBlocking { logout(oauth, api) { cleared = true } }
        assertTrue(cleared)
    }

    @Test fun logoutOfApiKeyMakesNoRequest() {
        runBlocking { logout(settings, api) {} }
        assertEquals(0, server.requestCount)
    }
}
