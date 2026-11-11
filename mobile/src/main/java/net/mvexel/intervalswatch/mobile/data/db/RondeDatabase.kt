package net.mvexel.intervalswatch.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ActivityEntity::class, ActivityStreamsEntity::class, PlannedWorkoutPrefsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RondeDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun streams(): ActivityStreamsDao
    abstract fun plannedWorkoutPrefs(): PlannedWorkoutPrefsDao

    companion object {
        fun build(context: Context): RondeDatabase =
            Room.databaseBuilder(context.applicationContext, RondeDatabase::class.java, "ronde.db").build()
    }
}
