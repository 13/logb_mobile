package dev.logb.android.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LogbDatabase::class.java)

    @Test fun `1 to 2 keeps rows, gives them empty tags, adds object_types and asks for a bootstrap`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO objects (uuid, server_id, name, type, description, created_at, updated_at) VALUES ('o1', 7, 'Golf', 'car', '', 't', 't')")
            db.execSQL("INSERT INTO activities (uuid, server_id, object_uuid, date, category, title, notes, created_at, updated_at) VALUES ('a1', 9, 'o1', '2026-01-01', 'repair', 'Brakes', '', 't', 't')")
            db.execSQL("INSERT INTO sync_state (id, cursor_seq, epoch, clock_offset_ms, device_id, bootstrap_needed) VALUES (1, 42, 'e', 0, 'device', 0)")
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2)
        db.query("SELECT name, tags FROM objects").use { c -> c.moveToFirst(); assertEquals("Golf", c.getString(0)); assertEquals("[]", c.getString(1)) }
        db.query("SELECT title, tags FROM activities").use { c -> c.moveToFirst(); assertEquals("Brakes", c.getString(0)); assertEquals("[]", c.getString(1)) }
        db.query("SELECT cursor_seq, bootstrap_needed FROM sync_state").use { c -> c.moveToFirst(); assertEquals(42, c.getInt(0)); assertEquals(1, c.getInt(1)) }
        db.query("SELECT COUNT(*) FROM object_types").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    private companion object { const val DB = "migration-test.db" }
}
