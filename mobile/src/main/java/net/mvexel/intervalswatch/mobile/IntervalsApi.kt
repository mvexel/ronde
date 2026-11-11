package net.mvexel.intervalswatch.mobile

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.mvexel.intervalswatch.shared.EventDto
import net.mvexel.intervalswatch.shared.IntervalsParser
import net.mvexel.intervalswatch.shared.Workout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** [retryable] is false for client errors another attempt can't fix (anything 4xx except 401/403/429). */
class UploadHttpException(val code: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = code !in 400..499 || code == 401 || code == 403 || code == 429
}

/** intervals.icu refused the credential (401, or 403 for an OAuth token): the user has to log in again. */
class AuthExpiredException(message: String) : IOException(message)

/** 401 always means the credential was refused; for an OAuth token 403 does too (revoked or expired, T01). */
internal fun isAuthRejection(code: Int, authType: AuthType): Boolean =
    code == 401 || (code == 403 && authType == AuthType.OAUTH)

private const val TAG = "IntervalsSync"

/** intervals.icu answered 429. [retryAfterSec] is from `Retry-After` (or how long the [RateLimitGate] still blocks). */
class RateLimitedException(val retryAfterSec: Long?, message: String = "intervals.icu is rate limiting, try again later") :
    IOException(message)

/**
 * Remembers a 429's `Retry-After` so no further read call goes out before it passes (the API's per-app budget is
 * shared by every Ronde user, so hammering it after a 429 hurts everyone). [shared] is process-wide.
 */
class RateLimitGate(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var blockedUntilMs: Long = 0L
        private set

    /** Throws [RateLimitedException] while blocked. */
    fun check() {
        val left = blockedUntilMs - now()
        if (left > 0) throw RateLimitedException((left + 999) / 1000)
    }

    fun block(retryAfterSec: Long?) {
        val sec = (retryAfterSec ?: DEFAULT_BACKOFF_SEC).coerceIn(1, MAX_BACKOFF_SEC)
        blockedUntilMs = maxOf(blockedUntilMs, now() + sec * 1000)
    }

    companion object {
        const val DEFAULT_BACKOFF_SEC = 60L
        const val MAX_BACKOFF_SEC = 24 * 3600L
        val shared = RateLimitGate()
    }
}

/** `Retry-After`: delta-seconds or an HTTP date. Null when absent or unreadable. */
internal fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    v.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    return runCatching {
        val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        ((at - nowMs + 999) / 1000).coerceAtLeast(0)
    }.getOrNull()
}

/** Auth: Bearer for an OAuth token, HTTP Basic with username "API_KEY" for a pasted key (see [Settings.authHeader]). */
class IntervalsApi(
    private val baseUrl: String = "https://intervals.icu",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gate: RateLimitGate = RateLimitGate.shared,
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
                    val msg = friendlyHttpError(resp.code, body, settings.authType)
                    if (resp.code == 429) {
                        val after = parseRetryAfter(resp.header("Retry-After"))
                        gate.block(after)
                        throw RateLimitedException(after, msg)
                    }
                    throw if (isAuthRejection(resp.code, settings.authType)) AuthExpiredException(msg) else IOException(msg)
                }
                val parsed = IntervalsParser.parse(body)
                Log.i(TAG, "HTTP ${resp.code}, events ${parsed.received}, workouts kept ${parsed.workouts.size}")
                parsed.dropped.forEach { Log.i(TAG, "dropped event ${it.id} type=${it.type}: ${it.reason}") }
                parsed.workouts
            }
        }

    // ---- History reads. Each is one API call; all go through [gate] and throw [RateLimitedException] on 429. ----

    /**
     * `GET /athlete/{id}/activities`, newest first. [oldest] counts from the start of that day, [newest] to the end
     * of that day (null = now). [fields] trims the payload; null asks for everything.
     */
    suspend fun activities(
        settings: Settings,
        oldest: LocalDate,
        newest: LocalDate? = null,
        limit: Int? = null,
        fields: List<String>? = IntervalsActivityJson.LIST_FIELDS,
    ): List<IcuActivity> {
        val url = "$baseUrl/api/v1/athlete/${settings.athleteId}/activities".toHttpUrl().newBuilder()
            .addQueryParameter("oldest", oldest.toString())
            .apply {
                if (newest != null) addQueryParameter("newest", "${newest}T23:59:59")
                if (limit != null) addQueryParameter("limit", limit.toString())
                if (fields != null) addQueryParameter("fields", fields.joinToString(","))
            }
            .build()
        return IntervalsActivityJson.activities(get(settings, url)!!)
    }

    /** `GET /activity/{id}`, with `icu_intervals` when [intervals] is true (same single call). */
    suspend fun activity(settings: Settings, id: String, intervals: Boolean = false): IcuActivity {
        val url = "$baseUrl/api/v1/activity/$id".toHttpUrl().newBuilder()
            .apply { if (intervals) addQueryParameter("intervals", "true") }
            .build()
        return IntervalsActivityJson.activity(get(settings, url)!!)
    }

    /** `GET /activity/{id}/streams.json?types=...`. Always pass explicit [types] (see the API's raw_watts note). */
    suspend fun activityStreams(settings: Settings, id: String, types: List<String>): List<IcuStream> {
        val url = "$baseUrl/api/v1/activity/$id/streams.json".toHttpUrl().newBuilder()
            .addQueryParameter("types", types.joinToString(","))
            .build()
        return IntervalsActivityJson.streams(get(settings, url)!!)
    }

    /** `GET /activity/{id}/intervals`. Prefer [activity] with `intervals = true` when the summary is needed too. */
    suspend fun activityIntervals(settings: Settings, id: String): List<IcuInterval> =
        IntervalsActivityJson.intervals(get(settings, "$baseUrl/api/v1/activity/$id/intervals".toHttpUrl())!!)

    /** `GET /activity/{id}/map`: the route as (lat, lng) pairs. Empty without GPS. */
    suspend fun activityMap(settings: Settings, id: String): List<Pair<Double, Double>> =
        IntervalsActivityJson.mapLatLngs(get(settings, "$baseUrl/api/v1/activity/$id/map".toHttpUrl())!!)

    /**
     * The planned workout an activity is paired with: `GET /athlete/{id}/events/{eventId}?resolve=true`, parsed like
     * [plannedWorkouts]. Null when the event is gone (404).
     */
    suspend fun plannedEvent(settings: Settings, eventId: Long): Workout? {
        val url = "$baseUrl/api/v1/athlete/${settings.athleteId}/events/$eventId".toHttpUrl().newBuilder()
            .addQueryParameter("resolve", "true")
            .build()
        val body = get(settings, url, nullOn404 = true) ?: return null
        return IntervalsParser.toWorkout(eventJson.decodeFromString(EventDto.serializer(), body))
    }

    /** One GET. Throws [RateLimitedException], [AuthExpiredException] or [IOException]; null only for a 404 when [nullOn404]. */
    private suspend fun get(settings: Settings, url: HttpUrl, nullOn404: Boolean = false): String? = withContext(Dispatchers.IO) {
        gate.check()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", settings.authHeader)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> body
                resp.code == 404 && nullOn404 -> null
                resp.code == 429 -> {
                    val after = parseRetryAfter(resp.header("Retry-After"))
                    gate.block(after)
                    throw RateLimitedException(after)
                }
                else -> {
                    val msg = friendlyHttpError(resp.code, body, settings.authType)
                    throw if (isAuthRejection(resp.code, settings.authType)) AuthExpiredException(msg) else IOException(msg)
                }
            }
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

private val eventJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
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
