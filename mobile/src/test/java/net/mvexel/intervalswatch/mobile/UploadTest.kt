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
    private val settings = Settings(apiKey = key)
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
        assertTrue(e!!.message!!.contains("401"))
        assertFalse(e.message!!.contains(key))
    }

    @Test fun serverError5xxFailsSoTheWorkerRetries() {
        server.enqueue(MockResponse().setResponseCode(503))
        val file = File.createTempFile("1_Ride", ".fit").apply { writeBytes(fit) }
        val failure = runBlocking { uploadRecordingFile(api, settings, file) }
        assertNotNull(failure)
        assertTrue(failure!!.contains("503"))
        assertFalse(failure.contains(key))
        // Next attempt succeeds.
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        assertNull(runBlocking { uploadRecordingFile(api, settings, file) })
        file.delete()
    }

    @Test fun missingKeyDoesNotCallTheServer() {
        val file = File.createTempFile("1_Ride", ".fit")
        val failure = runBlocking { uploadRecordingFile(api, Settings(apiKey = ""), file) }
        assertNotNull(failure)
        assertEquals(0, server.requestCount)
        file.delete()
    }
}
