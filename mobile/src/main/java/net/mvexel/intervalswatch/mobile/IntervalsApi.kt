package net.mvexel.intervalswatch.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.shared.IntervalsParser
import net.mvexel.intervalswatch.shared.Workout
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Personal API key auth: HTTP Basic with username "API_KEY" (intervals.icu → Settings → Developer). */
class IntervalsApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun plannedWorkouts(settings: Settings, from: LocalDate = LocalDate.now()): List<Workout> =
        withContext(Dispatchers.IO) {
            val url = "https://intervals.icu/api/v1/athlete/${settings.athleteId}/events".toHttpUrl()
                .newBuilder()
                .addQueryParameter("oldest", from.toString())
                .addQueryParameter("newest", from.plusDays(settings.daysAhead.toLong()).toString())
                .addQueryParameter("category", "WORKOUT")
                .addQueryParameter("resolve", "true")
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic("API_KEY", settings.apiKey))
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException(friendlyHttpError(resp.code, body))
                }
                IntervalsParser.parseEvents(body)
            }
        }

    /**
     * Uploads a FIT file as a new activity. Returns normally for 200/201 (200 = duplicate, already
     * there); throws [IOException] otherwise. The message never includes the API key.
     */
    suspend fun uploadActivity(settings: Settings, fit: ByteArray, fileName: String, name: String, externalId: String) =
        withContext(Dispatchers.IO) {
            val url = "https://intervals.icu/api/v1/athlete/${settings.athleteId}/activities".toHttpUrl()
                .newBuilder()
                .addQueryParameter("name", name)
                .addQueryParameter("external_id", externalId)
                .build()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fit.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic("API_KEY", settings.apiKey))
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("intervals.icu HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
                }
            }
        }
}

/**
 * User-facing text for a failed intervals.icu call. Never returns the raw response body:
 * 401/403 mean the key was refused; other errors show the status code and, if the body
 * carries one, its short "error" or "message" text.
 */
internal fun friendlyHttpError(code: Int, body: String): String {
    if (code == 401 || code == 403) return "API key rejected, check it in intervals.icu settings"
    val detail = Regex("\"(?:error|message)\"\\s*:\\s*\"([^\"]{1,100})\"")
        .find(body)?.groupValues?.get(1)
    return if (detail != null) "intervals.icu HTTP $code: $detail" else "intervals.icu HTTP $code"
}
