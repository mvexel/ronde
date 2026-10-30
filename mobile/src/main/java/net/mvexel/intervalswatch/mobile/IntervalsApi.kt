package net.mvexel.intervalswatch.mobile

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.shared.IntervalsParser
import net.mvexel.intervalswatch.shared.Workout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** [retryable] is false for client errors another attempt can't fix (anything 4xx except 401/403/429). */
class UploadHttpException(val code: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = code !in 400..499 || code == 401 || code == 403 || code == 429
}

private const val TAG = "IntervalsSync"

/** Auth: Bearer for an OAuth token, HTTP Basic with username "API_KEY" for a pasted key (see [Settings.authHeader]). */
class IntervalsApi(
    private val baseUrl: String = "https://intervals.icu",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun plannedWorkouts(settings: Settings, from: LocalDate = LocalDate.now()): List<Workout> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/v1/athlete/${settings.athleteId}/events".toHttpUrl()
                .newBuilder()
                .addQueryParameter("oldest", from.toString())
                .addQueryParameter("newest", from.plusDays(settings.daysAhead.toLong()).toString())
                .addQueryParameter("category", "WORKOUT")
                .addQueryParameter("resolve", "true")
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", settings.authHeader)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code}")
                    throw IOException(friendlyHttpError(resp.code, body, settings.authType))
                }
                val parsed = IntervalsParser.parse(body)
                Log.i(TAG, "HTTP ${resp.code}, events ${parsed.received}, workouts kept ${parsed.workouts.size}")
                parsed.dropped.forEach { Log.i(TAG, "dropped event ${it.id} type=${it.type}: ${it.reason}") }
                parsed.workouts
            }
        }

    /** Revokes the OAuth token on intervals.icu's side. Throws [IOException] on failure. */
    suspend fun disconnect(settings: Settings) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/disconnect-app")
            .header("Authorization", settings.authHeader)
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("intervals.icu HTTP ${resp.code}")
        }
    }

    /**
     * Uploads a FIT file as a new activity. Returns normally for 200/201 (200 = duplicate, already
     * there); throws [IOException] otherwise. The message never includes the API key.
     */
    suspend fun uploadActivity(settings: Settings, fit: ByteArray, fileName: String, name: String, externalId: String, pairedEventId: Long? = null) =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/v1/athlete/${settings.athleteId}/activities".toHttpUrl()
                .newBuilder()
                .addQueryParameter("name", name)
                .addQueryParameter("external_id", externalId)
                .apply { if (pairedEventId != null) addQueryParameter("paired_event_id", pairedEventId.toString()) }
                .build()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fit.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", settings.authHeader)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw UploadHttpException(resp.code, friendlyHttpError(resp.code, resp.body?.string().orEmpty(), settings.authType))
                }
            }
        }
}

/**
 * User-facing text for a failed intervals.icu call. Never returns the raw response body:
 * 401/403 mean the credential was refused (worded for the [authType]); other errors show the status code and, if the body
 * carries one, its short "error" or "message" text.
 */
internal fun friendlyHttpError(code: Int, body: String, authType: AuthType = AuthType.API_KEY): String {
    if (code == 401 || code == 403) {
        return if (authType == AuthType.OAUTH) "intervals.icu access expired or revoked, log in again"
        else "API key rejected, check it in intervals.icu settings"
    }
    val detail = Regex("\"(?:error|message)\"\\s*:\\s*\"([^\"]{1,100})\"")
        .find(body)?.groupValues?.get(1)
    return if (detail != null) "intervals.icu HTTP $code: $detail" else "intervals.icu HTTP $code"
}
