package net.mvexel.intervalswatch.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    /** Newest first. */
    @Query("SELECT * FROM activities ORDER BY start_local DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun observe(id: String): Flow<ActivityEntity?>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: String): ActivityEntity?

    /** Upsert (not REPLACE), so cached streams and detail rows survive a list refresh. */
    @Upsert
    suspend fun upsertAll(activities: List<ActivityEntity>)

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: String)

    /** Ids starting on or after [from] and before [until] (ISO local date or date-time strings compare in order). */
    @Query("SELECT id FROM activities WHERE start_local >= :from AND start_local < :until")
    suspend fun idsBetween(from: String, until: String): List<String>

    @Query("DELETE FROM activities WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM activities")
    suspend fun deleteAll()
}

@Dao
interface ActivityStreamsDao {
    @Query("SELECT * FROM activity_streams WHERE activity_id = :activityId")
    fun observe(activityId: String): Flow<ActivityStreamsEntity?>

    @Query("SELECT * FROM activity_streams WHERE activity_id = :activityId")
    suspend fun get(activityId: String): ActivityStreamsEntity?

    @Upsert
    suspend fun upsert(streams: ActivityStreamsEntity)

    @Query("DELETE FROM activity_streams WHERE activity_id = :activityId")
    suspend fun delete(activityId: String)
}

@Dao
interface ActivityDetailDao {
    @Query("SELECT * FROM activity_details WHERE activity_id = :activityId")
    suspend fun get(activityId: String): ActivityDetailEntity?

    @Upsert
    suspend fun upsert(detail: ActivityDetailEntity)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

suspend fun SyncStateDao.put(key: String, value: String) = put(SyncStateEntity(key, value))

@Dao
interface PlannedWorkoutPrefsDao {
    @Query("SELECT * FROM planned_workout_prefs")
    fun observeAll(): Flow<List<PlannedWorkoutPrefsEntity>>

    @Query("SELECT * FROM planned_workout_prefs WHERE event_id = :eventId")
    suspend fun get(eventId: Long): PlannedWorkoutPrefsEntity?

    @Query("SELECT event_id FROM planned_workout_prefs WHERE hidden = 1")
    suspend fun hiddenIds(): List<Long>

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM planned_workout_prefs WHERE pinned = 1")
    suspend fun maxPinnedOrder(): Int

    @Upsert
    suspend fun upsert(prefs: PlannedWorkoutPrefsEntity)
}

/** Read-modify-write of one workout's prefs; a missing row starts from the defaults. */
suspend fun PlannedWorkoutPrefsDao.edit(eventId: Long, change: (PlannedWorkoutPrefsEntity) -> PlannedWorkoutPrefsEntity) =
    upsert(change(get(eventId) ?: PlannedWorkoutPrefsEntity(eventId)))

/** Pins (at the end of the pinned group) or unpins one workout. */
suspend fun PlannedWorkoutPrefsDao.setPinned(eventId: Long, pinned: Boolean) {
    val order = if (pinned) maxPinnedOrder() + 1 else 0
    edit(eventId) { it.copy(pinned = pinned, sortOrder = order) }
}

suspend fun PlannedWorkoutPrefsDao.setHidden(eventId: Long, hidden: Boolean) = edit(eventId) { it.copy(hidden = hidden) }
