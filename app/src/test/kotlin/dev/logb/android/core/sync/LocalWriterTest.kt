package dev.logb.android.core.sync

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalWriterTest {
    private val db = TestDatabase.inMemory()
    private val w = LocalWriter(db)

    @After fun close() = db.close()

    @Test
    fun `a create queues one create op and no sets, and edits before push change the row only`() = runTest {
        w.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        w.set("object", "u1", mapOf("name" to "Golf VII")) { db.objectDao().upsert(db.objectDao().get("u1")!!.copy(name = "Golf VII")) }
        assertEquals(listOf("create"), db.opDao().pending().map { it.kind })
        assertEquals("Golf VII", db.objectDao().get("u1")!!.name)
    }

    @Test
    fun `a set on a server row queues one op per field with a JSON value and stamps the clock`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        w.set("object", "u1", mapOf("name" to "Polo", "purchase_price_cents" to 1200L, "parent_id" to null)) { }
        val ops = db.opDao().pending()
        assertEquals(listOf("set", "set", "set"), ops.map { it.kind })
        assertEquals(mapOf("name" to "\"Polo\"", "purchase_price_cents" to "1200", "parent_id" to "null"), ops.associate { it.field!! to it.valueJson })
        val clock = assertNotNull(db.fieldClockDao().get("object", "u1", "name"))
        assertEquals(w.deviceId(), clock.deviceId)
        assertEquals(ops[0].editedAt, clock.editedAt)
    }

    @Test
    fun `deleting a row whose create is queued removes it and its children and sends nothing`() = runTest {
        w.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        w.create("activity", "a1") { db.activityDao().upsert(act("a1", "u1", "2026-01-01")) }
        w.delete("object", "u1")
        assertNull(db.objectDao().get("u1"))
        assertNull(db.activityDao().get("a1"))
        assertTrue(db.opDao().pending().isEmpty())
    }

    @Test
    fun `deleting a server row tombstones the cascade, drops queued sets for it, and queues one delete`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        db.activityDao().upsert(act("a1", "u1", "2026-01-01").copy(serverId = 9))
        w.set("activity", "a1", mapOf("title" to "x")) { }
        w.delete("object", "u1")
        assertNotNull(db.objectDao().get("u1")!!.deletedAt)
        assertNotNull(db.activityDao().get("a1")!!.deletedAt)
        val ops = db.opDao().pending()
        assertEquals(listOf("delete"), ops.map { it.kind })
        assertEquals("u1", ops.single().entityUuid)
    }

    @Test
    fun `the device id is minted once and kept`() = runTest {
        val a = w.deviceId()
        assertEquals(a, w.deviceId())
        assertEquals(36, a.length)
    }
}
