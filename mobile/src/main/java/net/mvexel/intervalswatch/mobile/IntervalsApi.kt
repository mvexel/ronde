package net.mvexel.intervalswatch.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.shared.IntervalsParser
import net.mvexel.intervalswatch.shared.Workout
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
                    throw IOException("intervals.icu HTTP ${resp.code}: ${body.take(200)}")
                }
                IntervalsParser.parseEvents(body)
            }
        }
}
