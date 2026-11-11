package net.mvexel.intervalswatch.mobile.data.activity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.mvexel.intervalswatch.mobile.data.db.ActivityDao
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreamsDao
import net.mvexel.intervalswatch.mobile.data.db.decode
import net.mvexel.intervalswatch.mobile.data.db.encode

/** Completed activities for the History tab and Activity Detail. Room is the source of truth; [refresh] fills it. */
interface ActivityRepository {
    /** Newest first. */
    fun activities(): Flow<List<ActivityEntity>>
    fun activity(id: String): Flow<ActivityEntity?>
    fun streams(id: String): Flow<ActivityStreams?>

    /** Fetches recent activities from the remote source into the store. */
    suspend fun refresh()
}

/** Where activities come from. The intervals.icu implementation is a later item. */
interface ActivityRemoteSource {
    suspend fun recentActivities(): List<ActivityEntity>
    suspend fun streams(activityId: String): ActivityStreams?
}

/**
 * TODO(history-fetch): replace with an intervals.icu source (GET /api/v1/athlete/{id}/activities and
 *  /api/v1/activity/{id}/streams), mapping external_id to isRonde via isRondeExternalId. Add it next to
 *  IntervalsApi without changing that class's existing contract. Until then History stays empty.
 */
object EmptyActivityRemoteSource : ActivityRemoteSource {
    override suspend fun recentActivities(): List<ActivityEntity> = emptyList()
    override suspend fun streams(activityId: String): ActivityStreams? = null
}

class RoomActivityRepository(
    private val activityDao: ActivityDao,
    private val streamsDao: ActivityStreamsDao,
    private val remote: ActivityRemoteSource = EmptyActivityRemoteSource,
) : ActivityRepository {
    override fun activities() = activityDao.observeAll()
    override fun activity(id: String) = activityDao.observe(id)
    override fun streams(id: String) = streamsDao.observe(id).map { it?.decode() }

    override suspend fun refresh() {
        val fetched = remote.recentActivities()
        if (fetched.isNotEmpty()) activityDao.upsertAll(fetched)
    }
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

    override suspend fun refresh() {
        refreshes++
    }

    fun put(a: ActivityEntity) = rows.update { it + (a.id to a) }
    fun putStreams(s: ActivityStreams) = streamRows.update { it + (s.activityId to s) }
}
