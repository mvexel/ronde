package net.mvexel.intervalswatch.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.mvexel.intervalswatch.mobile.data.db.ActivityDetailEntity
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.data.db.decode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a version-1 database from the exported schema (mobile/schemas/.../1.json), fills it, then opens it with the
 * current [RondeDatabase] and its migrations. Room validates the migrated schema against the entities on open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    private fun createV1() {
        val schema = Json.parseToJsonElement(File("schemas/${RondeDatabase::class.qualifiedName}/1.json").readText())
            .jsonObject["database"]!!.jsonObject
        ctx.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        for (e in schema["entities"]!!.jsonArray) {
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            o["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.execSQL(
            "INSERT INTO activities (id, name, type, start_local, distance_m, avg_hr, external_id, is_ronde, fetched_at) " +
                "VALUES ('i1', 'Tempo', 'Run', '2026-11-01T07:00:00', 10000.0, 150, 'intervalswatch-1', 1, 5)",
        )
        db.execSQL("INSERT INTO activity_streams (activity_id, time) VALUES ('i1', x'0000000000000000')")
        db.execSQL("INSERT INTO planned_workout_prefs (event_id, pinned, hidden, sort_order) VALUES (7, 1, 0, 1)")
        db.version = 1
        db.close()
    }

    @Test fun migrates1To2KeepingData() = runBlocking {
        createV1()
        val db = Room.databaseBuilder(ctx, RondeDatabase::class.java, name)
            .addMigrations(*RondeDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val a = db.activities().get("i1")!!
            assertEquals("Tempo", a.name)
            assertEquals(150, a.avgHr)
            assertTrue(a.isRonde)
            assertTrue(a.detailsAvailable) // new NOT NULL column defaults to 1
            assertNull(a.deviceName)
            assertNull(a.maxHr)
            assertEquals(1, db.streams().get("i1")!!.decode().time!!.size)
            assertNull(db.streams().get("i1")!!.cadence)
            assertEquals(listOf(7L), db.plannedWorkoutPrefs().observeAll().first().map { it.eventId })
            db.details().upsert(ActivityDetailEntity("i1", null, ActivityDetailEntity.SOURCE_INTERVALS, fetchedAt = 1))
            db.syncState().put(net.mvexel.intervalswatch.mobile.data.db.SyncStateEntity("k", "v"))
            assertEquals("v", db.syncState().get("k"))
            db.activities().delete("i1")
            assertNull(db.details().get("i1")) // cascades
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }
}
