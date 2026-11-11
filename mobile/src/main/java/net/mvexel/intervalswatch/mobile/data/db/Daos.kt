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

    @Upsert
    suspend fun upsertAll(activities: List<ActivityEntity>)

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ActivityStreamsDao {
    @Query("SELECT * FROM activity_streams WHERE activity_id = :activityId")
    fun observe(activityId: String): Flow<ActivityStreamsEntity?>

    @Upsert
    suspend fun upsert(streams: ActivityStreamsEntity)
}

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
