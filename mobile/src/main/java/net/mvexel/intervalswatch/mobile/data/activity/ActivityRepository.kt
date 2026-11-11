package net.mvexel.intervalswatch.mobile.data.activity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mvexel.intervalswatch.mobile.IcuActivity
import net.mvexel.intervalswatch.mobile.data.db.ActivityDao
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreamsDao
import net.mvexel.intervalswatch.mobile.data.db.SyncStateDao
import net.mvexel.intervalswatch.mobile.data.db.SyncStateEntity
import net.mvexel.intervalswatch.mobile.data.db.decode
import net.mvexel.intervalswatch.mobile.data.db.encode
import java.time.Clock
import java.time.LocalDate

/** Completed activities for the History tab and Activity Detail. Room is the source of truth; [refresh] fills it. */
interface ActivityRepository {
    /** Newest first. */
    fun activities(): Flow<List<ActivityEntity>>
    fun activity(id: String): Flow<ActivityEntity?>
    fun streams(id: String): Flow<ActivityStreams?>

    /**
     * Fetches the last [RoomActivityRepository.RECENT_DAYS] days from intervals.icu (one API call). Call it when
     * History opens; it does nothing if the last refresh was under [RoomActivityRepository.MIN_REFRESH_MS] ago.
     * Pull-to-refresh should pass [force] = true. Throws on network, auth or rate-limit errors.
     */
    suspend fun refresh(force: Boolean = false)

    /** Background work (SyncWorker): a refresh if the last one is old, then one page of older history. */
    suspend fun backgroundSync() {}

    /** Forgets everything (logout). */
    suspend fun clear() {}
}

/**
 * Room-backed [ActivityRepository].
 *
 * API budget: [refresh] is one call and is throttled; [backgroundSync] is at most two calls (refresh only when the
 * last one is [BACKGROUND_REFRESH_MS] old, then one backfill page while the backfill runs). The backfill walks back
 * [BACKFILL_DAYS] in [BACKFILL_WINDOW_DAYS]-day pages, then rests until [RESWEEP_MS] later, when it walks again to
 * pick up edits and deletions of older activities.
 */
class RoomActivityRepository(
    private val activityDao: ActivityDao,
    private val streamsDao: ActivityStreamsDao,
    private val remote: ActivityRemoteSource = EmptyActivityRemoteSource,
    syncState: SyncStateDao? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val rondeClientId: String? = rondeClientId(),
) : ActivityRepository {
    private val state: SyncStateDao = syncState ?: MemorySyncState()
    private val mutex = Mutex()

    override fun activities() = activityDao.observeAll()
    override fun activity(id: String) = activityDao.observe(id)
    override fun streams(id: String) = streamsDao.observe(id).map { it?.decode() }

    override suspend fun refresh(force: Boolean) = mutex.withLock {
        val now = clock.millis()
        val last = state.get(KEY_LAST_REFRESH)?.toLongOrNull() ?: 0L
        if (!force && now - last in 0 until MIN_REFRESH_MS) return@withLock
        refreshLocked(now)
    }

    override suspend fun backgroundSync() = mutex.withLock {
        val now = clock.millis()
        val last = state.get(KEY_LAST_REFRESH)?.toLongOrNull() ?: 0L
        if (now - last !in 0 until BACKGROUND_REFRESH_MS) refreshLocked(now)
        backfillLocked(now)
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        activityDao.deleteAll()
        state.clear()
    }

    /** One backfill page (for tests and the worker); true while older history remains to fetch. */
    suspend fun backfill(): Boolean = mutex.withLock { backfillLocked(clock.millis()) }

    private suspend fun refreshLocked(now: Long) {
        val oldest = today().minusDays(RECENT_DAYS)
        val rows = remote.list(oldest, null, RECENT_LIMIT) ?: return
        store(rows, now, from = oldest.toString(), until = END, complete = rows.size < RECENT_LIMIT)
        state.put(SyncStateEntity(KEY_LAST_REFRESH, now.toString()))
    }

    private suspend fun backfillLocked(now: Long): Boolean {
        val today = today()
        val floor = today.minusDays(BACKFILL_DAYS)
        val doneAt = state.get(KEY_BACKFILL_DONE)?.toLongOrNull()
        if (doneAt != null) {
            if (now - doneAt in 0 until RESWEEP_MS) return false
            state.put(SyncStateEntity(KEY_BACKFILL_DONE, ""))
            state.put(SyncStateEntity(KEY_BACKFILL_CURSOR, ""))
        }
        // Everything on or after the cursor date is covered; the next page ends the day before it.
        val cursor = state.get(KEY_BACKFILL_CURSOR)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: today.minusDays(RECENT_DAYS)
        if (cursor <= floor) return finishBackfill(now)
        val windowStart = maxOf(cursor.minusDays(BACKFILL_WINDOW_DAYS), floor)
        val rows = remote.list(windowStart, cursor.minusDays(1), BACKFILL_LIMIT) ?: return false
        val complete = rows.size < BACKFILL_LIMIT
        store(rows, now, from = windowStart.toString(), until = cursor.toString(), complete = complete)
        val next = if (complete) {
            windowStart
        } else {
            // Truncated (newest first): the oldest day returned may be partial, so fetch it again next time.
            val oldestDay = rows.mapNotNull { day(it.startDateLocal) }.minOrNull() ?: windowStart
            if (oldestDay.plusDays(1) < cursor) oldestDay.plusDays(1) else oldestDay
        }
        state.put(SyncStateEntity(KEY_BACKFILL_CURSOR, next.toString()))
        return if (next <= floor) finishBackfill(now) else true
    }

    private suspend fun finishBackfill(now: Long): Boolean {
        state.put(SyncStateEntity(KEY_BACKFILL_DONE, now.toString()))
        return false
    }

    /**
     * Upserts [rows]. When the response was [complete] (not cut off by the limit), local rows in [from, until) that
     * intervals.icu no longer returns were deleted there, so they go here too.
     */
    private suspend fun store(rows: List<IcuActivity>, now: Long, from: String, until: String, complete: Boolean) {
        val entities = rows.map { it.toEntity(now, rondeClientId) }
        if (entities.isNotEmpty()) activityDao.upsertAll(entities)
        if (complete) {
            val keep = entities.mapTo(HashSet()) { it.id }
            activityDao.idsBetween(from, until).filterNot { it in keep }.chunked(500).forEach { activityDao.deleteIds(it) }
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun day(startLocal: String?): LocalDate? =
        startLocal?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

    companion object {
        const val RECENT_DAYS = 14L
        const val RECENT_LIMIT = 200
        const val MIN_REFRESH_MS = 2 * 60_000L
        const val BACKGROUND_REFRESH_MS = 8 * 3_600_000L
        const val BACKFILL_DAYS = 365L
        const val BACKFILL_WINDOW_DAYS = 90L
        const val BACKFILL_LIMIT = 100
        const val RESWEEP_MS = 7 * 24 * 3_600_000L
        private const val END = "9999"
        internal const val KEY_LAST_REFRESH = "history.last_refresh_ms"
        internal const val KEY_BACKFILL_CURSOR = "history.backfill_cursor"
        internal const val KEY_BACKFILL_DONE = "history.backfill_done_ms"
    }
}

/** [SyncStateDao] in memory, for a repository built without a database. */
private class MemorySyncState : SyncStateDao {
    private val map = HashMap<String, String>()
    override suspend fun get(key: String): String? = map[key]?.takeIf { it.isNotEmpty() }
    override suspend fun put(entry: SyncStateEntity) { map[entry.key] = entry.value }
    override suspend fun clear() = map.clear()
}

/** In-memory repository for previews and tests. */
class InMemoryActivityRepository(
    initial: List<ActivityEntity> = emptyList(),
    initialStreams: List<ActivityStreams> = emptyList(),
) : ActivityRepository {
    private val rows = MutableStateFlow(initial.associateBy { it.id })
    private val streamRows = MutableStateFlow(initialStreams.associateBy { it.activityId })
    var refreshes = 0
        private set

    override fun activities() = rows.map { m -> m.values.sortedByDescending { it.startLocal } }
    override fun activity(id: String) = rows.map { it[id] }
    override fun streams(id: String) = streamRows.map { it[id]?.encode()?.decode() }

    override suspend fun refresh(force: Boolean) {
        refreshes++
    }

    fun put(a: ActivityEntity) = rows.update { it + (a.id to a) }
    fun putStreams(s: ActivityStreams) = streamRows.update { it + (s.activityId to s) }
}
