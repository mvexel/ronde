package net.mvexel.intervalswatch.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActivityEntity::class, ActivityStreamsEntity::class, PlannedWorkoutPrefsEntity::class,
        ActivityDetailEntity::class, SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RondeDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun streams(): ActivityStreamsDao
    abstract fun plannedWorkoutPrefs(): PlannedWorkoutPrefsDao
    abstract fun details(): ActivityDetailDao
    abstract fun syncState(): SyncStateDao

    companion object {
        fun build(context: Context): RondeDatabase =
            Room.databaseBuilder(context.applicationContext, RondeDatabase::class.java, "ronde.db")
                .addMigrations(*MIGRATIONS)
                .build()

        /**
         * 1 → 2: History from intervals.icu. Activities gain max HR, device (Garmin attribution), source, the Strava
         * stub flag, `analyzed` and stream types; streams gain cadence; new detail cache and sync bookkeeping tables.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `max_hr` INTEGER")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `device_name` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `source` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `details_available` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `analyzed` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `stream_types` TEXT")
                db.execSQL("ALTER TABLE `activity_streams` ADD COLUMN `cadence` BLOB")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_details` (`activity_id` TEXT NOT NULL, `analyzed` TEXT, " +
                        "`source` TEXT NOT NULL, `boundaries` TEXT, `pauses` TEXT, `planned_event_id` INTEGER, " +
                        "`planned_steps` TEXT, `fetched_at` INTEGER NOT NULL, PRIMARY KEY(`activity_id`), " +
                        "FOREIGN KEY(`activity_id`) REFERENCES `activities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
