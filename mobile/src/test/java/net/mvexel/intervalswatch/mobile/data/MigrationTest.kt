package net.mvexel.intervalswatch.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.mvexel.intervalswatch.mobile.data.db.ActivityDetailEntity
import net.mvexel.intervalswatch.mobile.data.db.PlannedListEntity
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsEntity
import net.mvexel.intervalswatch.mobile.data.db.RondeDatabase
import net.mvexel.intervalswatch.mobile.data.db.SyncStateEntity
import net.mvexel.intervalswatch.mobile.data.db.decode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a database at each exported schema version (mobile/schemas/...), fills it, then opens it with the current
 * [RondeDatabase] and its migrations. Room validates the migrated schema against the entities on open. Every
 * exported version must have a path; the prefs (user data) must survive each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private val schemaDir = File("schemas/${RondeDatabase::class.qualifiedName}")

    @After fun cleanUp() {
        ctx.deleteDatabase(name)
    }

    private fun schema(version: Int) = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun create(version: Int, fill: SQLiteDatabase.() -> Unit) {
        val schema = schema(version)
        ctx.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        for (e in schema["entities"]!!.jsonArray) {
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            o["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.fill()
        db.version = version
        db.close()
    }

    private fun SQLiteDatabase.fillCommon() {
        execSQL(
            "INSERT INTO activities (id, name, type, start_local, distance_m, avg_hr, external_id, is_ronde, fetched_at) " +
                "VALUES ('i1', 'Tempo', 'Run', '2026-11-01T07:00:00', 10000.0, 150, 'intervalswatch-1', 1, 5)",
        )
        execSQL("INSERT INTO activity_streams (activity_id, time) VALUES ('i1', x'0000000000000000')")
        execSQL("INSERT INTO planned_workout_prefs (event_id, pinned, hidden, sort_order) VALUES (7, 1, 0, 1)")
        execSQL("INSERT INTO planned_workout_prefs (event_id, pinned, hidden, sort_order) VALUES (8, 0, 1, 0)")
    }

    private fun open() = Room.databaseBuilder(ctx, RondeDatabase::class.java, name)
        .addMigrations(*RondeDatabase.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    /** The prefs (user data) and the cached rows came through; the new columns and tables work. */
    private fun RondeDatabase.checkCurrent() = runBlocking {
        val a = activities().get("i1")!!
        assertEquals("Tempo", a.name)
        assertEquals(150, a.avgHr)
        assertTrue(a.isRonde)
        assertEquals(1, streams().get("i1")!!.decode().time!!.size)
        assertNull(streams().get("i1")!!.moving)
        assertEquals(
            listOf(PlannedWorkoutPrefsEntity(7, pinned = true, sortOrder = 1), PlannedWorkoutPrefsEntity(8, hidden = true)),
            plannedWorkoutPrefs().observeAll().first().sortedBy { it.eventId },
        )
        plannedList().put(PlannedListEntity(json = "{}", fetchedAt = 1))
        assertEquals(1L, plannedList().get()!!.fetchedAt)
    }

    @Test fun everyExportedSchemaHasAMigrationPath() {
        val versions = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
        val current = versions.last()
        assertEquals(current, schema(current)["version"]!!.jsonPrimitive.int)
        val steps = RondeDatabase.MIGRATIONS.associate { it.startVersion to it.endVersion }
        for (v in versions.dropLast(1)) {
            var at = v
            while (at < current) at = steps[at] ?: error("no migration from $at (exported schema $v)")
        }
    }

    @Test fun migrates1ToCurrentKeepingData() = runBlocking {
        create(1) { fillCommon() }
        val db = open()
        try {
            db.checkCurrent()
            val a = db.activities().get("i1")!!
            assertTrue(a.detailsAvailable) // 1 → 2: new NOT NULL column defaults to 1
            assertNull(a.deviceName)
            assertNull(a.maxHr)
            assertNull(db.streams().get("i1")!!.cadence)
            db.details().upsert(ActivityDetailEntity("i1", null, ActivityDetailEntity.SOURCE_INTERVALS, fetchedAt = 1))
            db.syncState().put(SyncStateEntity("k", "v"))
            assertEquals("v", db.syncState().get("k"))
            db.activities().delete("i1")
            assertNull(db.details().get("i1")) // cascades
        } finally {
            db.close()
        }
    }

    @Test fun migrates2ToCurrentKeepingData() = runBlocking {
        create(2) {
            fillCommon()
            execSQL("UPDATE activity_streams SET cadence = x'0000000000005640' WHERE activity_id = 'i1'")
            execSQL(
                "INSERT INTO activity_details (activity_id, analyzed, source, planned_event_id, planned_steps, fetched_at) " +
                    "VALUES ('i1', 'a1', 'intervals', 7, '[]', 9)",
            )
            execSQL("INSERT INTO sync_state (`key`, value) VALUES ('last_refresh', '123')")
        }
        val db = open()
        try {
            db.checkCurrent()
            assertEquals(88.0, db.streams().get("i1")!!.decode().cadence!![0], 0.0)
            assertEquals(7L, db.details().get("i1")!!.plannedEventId)
            assertEquals("123", db.syncState().get("last_refresh"))
        } finally {
            db.close()
        }
    }
}
