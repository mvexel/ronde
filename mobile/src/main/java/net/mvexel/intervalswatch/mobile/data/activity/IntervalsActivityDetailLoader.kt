package net.mvexel.intervalswatch.mobile.data.activity

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.mobile.AuthExpiredException
import net.mvexel.intervalswatch.mobile.RateLimitedException
import net.mvexel.intervalswatch.mobile.RecordingLibrary
import net.mvexel.intervalswatch.mobile.data.db.ActivityDao
import net.mvexel.intervalswatch.mobile.data.db.ActivityDetailDao
import net.mvexel.intervalswatch.mobile.data.db.ActivityDetailEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreamsDao
import net.mvexel.intervalswatch.mobile.data.db.StreamCodec
import net.mvexel.intervalswatch.shared.ActivityStreams
import net.mvexel.intervalswatch.shared.FitReader
import net.mvexel.intervalswatch.shared.Recording
import net.mvexel.intervalswatch.shared.RecordingProtocol
import net.mvexel.intervalswatch.shared.WorkoutStep
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import kotlin.math.abs

/** A FIT file the watch sent, still kept on the phone. */
class LocalFit(val fileName: String, val bytes: ByteArray) {
    /** The planned event it was recorded for, from the file name. */
    val eventId: Long? get() = RecordingProtocol.eventId(fileName)
}

/** Finds the kept FIT file of a Ronde upload by its start (the `<epoch>` in `external_id=intervalswatch-<epoch>`). */
fun interface LocalFitSource {
    suspend fun find(startEpochSec: Long): LocalFit?
}

class RecordingLibraryFitSource(context: Context) : LocalFitSource {
    private val library = RecordingLibrary.of(context.applicationContext)
    override suspend fun find(startEpochSec: Long): LocalFit? = withContext(Dispatchers.IO) {
        library.recent().firstOrNull { it.startEpochSec == startEpochSec }
            ?.let { item -> runCatching { LocalFit(item.fileName, library.file(item.fileName).readBytes()) }.getOrNull() }
    }
}

/**
 * [ActivityDetailLoader] backed by Room, intervals.icu and the FIT files kept on the phone.
 *
 * - Cached detail whose `analyzed` still matches the History row (or that came from the local FIT) is served without
 *   any API call.
 * - A Ronde run whose FIT is still on the phone (and matches the activity's elapsed time) uses the watch's own
 *   samples, pauses and laps: only the planned workout is fetched (1 call, when paired).
 * - Otherwise: the activity with intervals (1), its streams (1), the route from /map only when the streams had no
 *   usable latlng (0-1), and the paired planned workout (0-1).
 * - Strava stubs have no details; they are served from the History row with [ActivityDetail.detailsAvailable] false.
 *
 * Emissions: cached detail first as [DetailLoad.Ready] (else [DetailLoad.Loading]), then fresh [DetailLoad.Ready].
 * A failed fetch emits [DetailLoad.Failed] (carrying the History row's summary, if any) only when nothing complete
 * was cached; with stale cache shown, the failure is silent.
 */
class IntervalsActivityDetailLoader(
    private val activityDao: ActivityDao,
    private val streamsDao: ActivityStreamsDao,
    private val detailDao: ActivityDetailDao,
    private val remote: ActivityRemoteSource,
    private val localFits: LocalFitSource = LocalFitSource { null },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val rondeClientId: String? = rondeClientId(),
) : ActivityDetailLoader {

    override fun load(id: String): Flow<DetailLoad> = flow {
        val entity = activityDao.get(id)
        if (entity != null && !entity.detailsAvailable) {
            emit(DetailLoad.Ready(buildDetail(entity, null, null)))
            return@flow
        }
        val cachedRow = detailDao.get(id)
        val cached = if (entity != null && cachedRow != null) buildDetail(entity, cachedRow, streamsDao.get(id)) else null
        if (cached != null) emit(DetailLoad.Ready(cached)) else emit(DetailLoad.Loading)
        if (cachedRow != null && entity != null && isFresh(entity, cachedRow)) return@flow
        try {
            emit(DetailLoad.Ready(fetch(id, entity)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) emit(DetailLoad.Failed(detailErrorMessage(e), entity?.let { buildDetail(it, null, null) }))
        }
    }

    private fun isFresh(a: ActivityEntity, d: ActivityDetailEntity) =
        d.source == ActivityDetailEntity.SOURCE_RECORDING || d.analyzed == a.analyzed

    private suspend fun fetch(id: String, known: ActivityEntity?): ActivityDetail =
        known?.let { fromLocalFit(it) } ?: fromIntervals(id, known)

    private suspend fun fromLocalFit(a: ActivityEntity): ActivityDetail? {
        if (!a.isRonde) return null
        val epoch = a.externalId?.removePrefix("intervalswatch-")?.toLongOrNull() ?: return null
        val fit = localFits.find(epoch) ?: return null
        val rec = withContext(Dispatchers.Default) { runCatching { FitReader.read(fit.bytes) }.getOrNull() } ?: return null
        if (!matches(rec, a) || rec.samples.size < 2) return null
        val eventId = a.pairedEventId ?: fit.eventId
        val planned = planned(eventId)
        val streams = recordingToEntity(a.id, rec)
        val row = ActivityDetailEntity(
            activityId = a.id,
            analyzed = a.analyzed,
            source = ActivityDetailEntity.SOURCE_RECORDING,
            boundaries = ActivityDetailCodec.encodeSpans(ActivityStreams.stepBoundaries(rec)),
            pauses = ActivityDetailCodec.encodeSpans(rec.pauses.map { net.mvexel.intervalswatch.shared.TimeSpan(it.startSec, it.endSec) }),
            plannedEventId = eventId,
            plannedSteps = ActivityDetailCodec.encodeSteps(planned),
            fetchedAt = clock.millis(),
        )
        streamsDao.upsert(streams)
        detailDao.upsert(row)
        return buildDetail(a, row, streams)
    }

    /** The kept file is this activity: same start (by name), and an elapsed time within a minute or 5 %. */
    private fun matches(rec: Recording, a: ActivityEntity): Boolean {
        val elapsed = a.elapsedTimeSec ?: return true
        return abs(rec.elapsedSec - elapsed) <= maxOf(60.0, elapsed * 0.05)
    }

    private suspend fun fromIntervals(id: String, known: ActivityEntity?): ActivityDetail {
        val now = clock.millis()
        val a = remote.activity(id)
        val entity = a.toEntity(now, rondeClientId)
        activityDao.upsertAll(listOf(entity))
        if (!entity.detailsAvailable) return buildDetail(entity, null, null)

        val available = a.streamTypes
        var types = if (available == null) WANTED_STREAMS else WANTED_STREAMS.filter { it in available }
        if ("velocity_smooth" in types) types = types - "velocity"
        val streams = if (types.any { it != "time" }) remote.streams(id, (listOf("time") + types).distinct()) else emptyList()
        val hasLatLng = streams.any { it.type == "latlng" && it.data2 != null }
        val route = if (!hasLatLng && available?.contains("latlng") == true) optional { remote.route(id) } else null
        val eventId = a.pairedEventId ?: known?.pairedEventId
        val planned = planned(eventId)

        val streamsRow = icuStreamsToEntity(id, streams, route)
        val time = streamsRow.time?.let(StreamCodec::decode)
        val row = ActivityDetailEntity(
            activityId = id,
            analyzed = entity.analyzed,
            source = ActivityDetailEntity.SOURCE_INTERVALS,
            boundaries = ActivityDetailCodec.encodeSpans(intervalsToSpans(a.intervals, time)),
            pauses = null,
            plannedEventId = eventId,
            plannedSteps = ActivityDetailCodec.encodeSteps(planned),
            fetchedAt = now,
        )
        streamsDao.upsert(streamsRow)
        detailDao.upsert(row)
        return buildDetail(entity, row, streamsRow)
    }

    /** Planned steps of [eventId]; null when unpaired, gone, or signed out. Other errors propagate (nothing is cached). */
    private suspend fun planned(eventId: Long?): List<WorkoutStep>? {
        if (eventId == null) return null
        return try {
            remote.planned(eventId)?.steps
        } catch (_: NotSignedInException) {
            null
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RateLimitedException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Short user-facing text for a failed detail load. */
internal fun detailErrorMessage(e: Throwable): String = when (e) {
    is RateLimitedException -> "intervals.icu is busy. " +
        (e.retryAfterSec?.let { "Try again in ${maxOf(1, (it + 59) / 60)} min." } ?: "Try again later.")
    is AuthExpiredException, is NotSignedInException -> e.message ?: "Log in to intervals.icu again"
    is UnknownHostException, is SocketTimeoutException -> "No connection to intervals.icu"
    is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "No connection to intervals.icu"
    else -> "Could not load this activity"
}
