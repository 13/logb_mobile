package dev.logb.android.core.sync

import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.db.rem
import dev.logb.android.core.network.dto.ChangeRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChangeApplierTest {
    private val db = TestDatabase.inMemory()
    private val applier = ChangeApplier(db)

    @After fun close() = db.close()

    private fun row(
        seq: Long, entity: String, uuid: String, op: String, field: String? = null, value: String? = null,
        at: String = "2026-09-01T00:00:00.000Z", device: String = "rest", id: Long? = 1,
    ) = ChangeRow(seq, entity, uuid, op, field, value, at, device, id)

    @Test
    fun `a create for an unknown uuid makes a placeholder that later sets fill in`() = runTest {
        applier.apply(
            listOf(
                row(1, "object", "u1", "create", id = 42),
                row(2, "object", "u1", "set", "name", "\"Golf\""),
                row(3, "object", "u1", "set", "type", "\"car\""),
                row(4, "object", "u1", "set", "purchase_price_cents", "1200"),
            ),
        )
        val o = assertNotNull(db.objectDao().get("u1"))
        assertEquals(42, o.serverId)
        assertEquals("Golf", o.name)
        assertEquals("car", o.type)
        assertEquals(1200, o.purchasePriceCents)
    }

    @Test
    fun `a create for a row the phone made only records the server id`() = runTest {
        db.objectDao().upsert(obj("mine", "Offline bike", type = "bike"))
        applier.apply(listOf(row(1, "object", "mine", "create", id = 77)))
        val o = db.objectDao().get("mine")!!
        assertEquals(77, o.serverId)
        assertEquals("Offline bike", o.name)
    }

    @Test
    fun `an older set is ignored and the clock is untouched`() = runTest {
        applier.apply(listOf(row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "name", "\"New\"", at = "2026-09-02T00:00:00.000Z", device = "b")))
        applier.apply(listOf(row(3, "object", "u1", "set", "name", "\"Old\"", at = "2026-09-01T00:00:00.000Z", device = "a")))
        assertEquals("New", db.objectDao().get("u1")!!.name)
        assertEquals("2026-09-02T00:00:00.000Z", db.fieldClockDao().get("object", "u1", "name")!!.editedAt)
    }

    @Test
    fun `equal stamps break on device id`() = runTest {
        applier.apply(listOf(row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "name", "\"A\"", device = "a")))
        applier.apply(listOf(row(3, "object", "u1", "set", "name", "\"B\"", device = "b")))
        assertEquals("B", db.objectDao().get("u1")!!.name)
        applier.apply(listOf(row(4, "object", "u1", "set", "name", "\"A again\"", device = "a")))
        assertEquals("B", db.objectDao().get("u1")!!.name)
    }

    @Test
    fun `integer references are translated to uuids through server ids`() = runTest {
        applier.apply(
            listOf(
                row(1, "object", "house", "create", id = 10), row(2, "object", "garage", "create", id = 11),
                row(3, "object", "garage", "set", "parent_id", "10"),
            ),
        )
        assertEquals("house", db.objectDao().get("garage")!!.parentUuid)
        assertFalse(db.syncStateDao().get()?.bootstrapNeeded ?: false)
    }

    @Test
    fun `an unresolvable reference is applied as null and asks for a bootstrap`() = runTest {
        db.syncStateDao().upsert(SyncStateEntity(deviceId = "d", bootstrapNeeded = false))
        applier.apply(listOf(row(1, "object", "garage", "create", id = 11), row(2, "object", "garage", "set", "parent_id", "999")))
        assertNull(db.objectDao().get("garage")!!.parentUuid)
        assertTrue(db.syncStateDao().get()!!.bootstrapNeeded)
    }

    @Test
    fun `a null value clears the field, whether SQL NULL or the string null`() = runTest {
        applier.apply(
            listOf(
                row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "purchase_price_cents", "1200"),
                row(3, "object", "u1", "set", "purchase_price_cents", null, at = "2026-09-02T00:00:00.000Z"),
            ),
        )
        assertNull(db.objectDao().get("u1")!!.purchasePriceCents)
        applier.apply(
            listOf(
                row(4, "object", "u1", "set", "purchase_price_cents", "1300", at = "2026-09-03T00:00:00.000Z"),
                row(5, "object", "u1", "set", "purchase_price_cents", "null", at = "2026-09-04T00:00:00.000Z"),
            ),
        )
        assertNull(db.objectDao().get("u1")!!.purchasePriceCents)
    }

    @Test
    fun `deleting an object tombstones its activities, attachments, reminders and descendants`() = runTest {
        applier.apply(
            listOf(
                row(1, "object", "house", "create", id = 10), row(2, "object", "garage", "create", id = 11),
                row(3, "object", "garage", "set", "parent_id", "10"),
            ),
        )
        db.activityDao().upsert(act("a1", "garage", "2026-01-01"))
        db.reminderDao().upsert(rem("r1", "house"))
        db.objectDao().upsert(obj("other", "Bike"))
        db.reminderDao().upsert(rem("r-other", "other").copy(doneActivityUuid = "a1", doneAt = T0))
        applier.apply(listOf(row(4, "object", "house", "delete", at = "2026-09-03T00:00:00.000Z")))
        assertNotNull(db.objectDao().get("house")!!.deletedAt)
        assertNotNull(db.objectDao().get("garage")!!.deletedAt)
        assertNotNull(db.activityDao().get("a1")!!.deletedAt)
        assertNotNull(db.reminderDao().get("r1")!!.deletedAt)
        assertNull(db.objectDao().get("other")!!.deletedAt)
        assertNull(db.reminderDao().get("r-other")!!.doneActivityUuid, "a reminder elsewhere no longer names a gone entry")
    }

    @Test
    fun `deleting an activity clears covers pointing at its attachments and unlinks reminders`() = runTest {
        db.objectDao().upsert(obj("o1", "Golf"))
        db.activityDao().upsert(act("a1", "o1", "2026-01-01"))
        db.fileDao().upsert(dev.logb.android.core.db.entity.FileEntity("f1", 1, "sha", "a.png", "image/png", 10, null, null, null, T0, null))
        db.attachmentDao().upsert(dev.logb.android.core.db.entity.AttachmentEntity("t1", 1, "o1", "a1", "f1", "photo", "", T0, null))
        db.objectDao().upsert(obj("o1", "Golf").copy(coverAttachmentUuid = "t1"))
        db.reminderDao().upsert(rem("r1", "o1", doneAt = T0).copy(doneActivityUuid = "a1"))
        applier.apply(listOf(row(1, "activity", "a1", "delete")))
        assertNotNull(db.attachmentDao().get("t1")!!.deletedAt)
        assertNull(db.objectDao().get("o1")!!.coverAttachmentUuid)
        assertNull(db.reminderDao().get("r1")!!.doneActivityUuid)
    }

    @Test
    fun `a set on an unknown entity or an unknown field is skipped, not fatal`() = runTest {
        applier.apply(listOf(row(1, "activity", "ghost", "set", "title", "\"x\"")))
        applier.apply(listOf(row(2, "object", "u1", "create"), row(3, "object", "u1", "set", "user_id", "7"), row(4, "gizmo", "u1", "set", "name", "\"x\"")))
        assertNull(db.activityDao().get("ghost"))
        assertEquals("", db.objectDao().get("u1")!!.name)
    }

    @Test
    fun `a value of the wrong type clears rather than corrupts`() = runTest {
        applier.apply(listOf(row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "purchase_price_cents", "\"twelve\"")))
        assertNull(db.objectDao().get("u1")!!.purchasePriceCents)
    }
}
