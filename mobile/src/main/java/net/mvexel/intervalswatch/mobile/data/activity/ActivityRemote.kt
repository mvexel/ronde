package net.mvexel.intervalswatch.mobile.data.activity

import net.mvexel.intervalswatch.mobile.IcuActivity
import net.mvexel.intervalswatch.mobile.IcuStream
import net.mvexel.intervalswatch.mobile.IntervalsApi
import net.mvexel.intervalswatch.mobile.OAUTH_CLIENT_ID
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.shared.Workout
import java.io.IOException
import java.time.LocalDate
import kotlin.math.roundToInt

/** Where History data comes from. Every method is one intervals.icu call (see IntervalsApi). */
interface ActivityRemoteSource {
    /** Activities started from [oldest] through [newest] (null = now), newest first, at most [limit]. Null when signed out. */
    suspend fun list(oldest: LocalDate, newest: LocalDate?, limit: Int): List<IcuActivity>?

    /** One activity with its `icu_intervals`. */
    suspend fun activity(id: String): IcuActivity

    suspend fun streams(id: String, types: List<String>): List<IcuStream>

    /** The route from `/activity/{id}/map`, as (lat, lng). */
    suspend fun route(id: String): List<Pair<Double, Double>>

    /** The paired planned workout, resolved to absolute targets; null when the event is gone. */
    suspend fun planned(eventId: Long): Workout?
}

class NotSignedInException : IOException("Not signed in to intervals.icu")

/** Signed out: lists are null, detail calls throw [NotSignedInException]. */
object EmptyActivityRemoteSource : ActivityRemoteSource {
    override suspend fun list(oldest: LocalDate, newest: LocalDate?, limit: Int): List<IcuActivity>? = null
    override suspend fun activity(id: String): IcuActivity = throw NotSignedInException()
    override suspend fun streams(id: String, types: List<String>): List<IcuStream> = throw NotSignedInException()
    override suspend fun route(id: String): List<Pair<Double, Double>> = throw NotSignedInException()
    override suspend fun planned(eventId: Long): Workout? = throw NotSignedInException()
}

/** [ActivityRemoteSource] over [IntervalsApi], reading the credential for each call (it can change at login/logout). */
class IntervalsActivitySource(
    private val api: IntervalsApi,
    private val settings: suspend () -> Settings,
) : ActivityRemoteSource {
    private suspend fun signedIn(): Settings = settings().takeIf { it.configured } ?: throw NotSignedInException()

    override suspend fun list(oldest: LocalDate, newest: LocalDate?, limit: Int): List<IcuActivity>? {
        val s = settings().takeIf { it.configured } ?: return null
        return api.activities(s, oldest, newest, limit)
    }

    override suspend fun activity(id: String) = api.activity(signedIn(), id, intervals = true)
    override suspend fun streams(id: String, types: List<String>) = api.activityStreams(signedIn(), id, types)
    override suspend fun route(id: String) = api.activityMap(signedIn(), id)
    override suspend fun planned(eventId: Long) = api.plannedEvent(signedIn(), eventId)
}

/** Ronde's OAuth client id, or null while it is still the placeholder. */
fun rondeClientId(): String? = OAUTH_CLIENT_ID.takeUnless { it.isBlank() || it.startsWith("TODO") }

/** The History row for an intervals.icu activity. */
fun IcuActivity.toEntity(fetchedAt: Long, rondeClientId: String? = rondeClientId()): ActivityEntity {
    val stub = isStravaStub
    return ActivityEntity(
        id = id,
        name = name?.takeIf { it.isNotBlank() } ?: if (stub) "Strava activity" else (type ?: "Activity"),
        type = type.orEmpty(),
        startLocal = startDateLocal.orEmpty(),
        distanceM = distance,
        movingTimeSec = movingTime,
        elapsedTimeSec = elapsedTime,
        avgSpeed = averageSpeed,
        avgHr = averageHeartrate?.roundToInt(),
        avgPower = averageWatts?.roundToInt(),
        load = trainingLoad,
        externalId = externalId,
        pairedEventId = pairedEventId,
        isRonde = isRonde(rondeClientId),
        fetchedAt = fetchedAt,
        maxHr = maxHeartrate?.roundToInt(),
        deviceName = deviceName,
        source = source,
        detailsAvailable = !stub,
        analyzed = analyzed,
        streamTypes = streamTypes?.joinToString(","),
    )
}
