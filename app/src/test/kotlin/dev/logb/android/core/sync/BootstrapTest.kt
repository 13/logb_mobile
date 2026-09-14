package dev.logb.android.core.sync

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.entity.FieldClockEntity
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.network.LogbJson
import dev.logb.android.core.network.dto.BootstrapResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Against `fixtures/bootstrap.json`, captured from a phase-0 server seeded by `seed.py`. */
@RunWith(RobolectricTestRunner::class)
class BootstrapTest {
    private val db = TestDatabase.inMemory()
    private val snapshot: BootstrapResult = LogbJson.decodeFromString(
        BootstrapResult.serializer(), javaClass.getResource("/fixtures/bootstrap.json")!!.readText(),
    )

    @After fun close() = db.close()

    private fun uuidOf(rows: List<JsonObject>, name: String, key: String = "name"): String =
        rows.first { it[key]!!.jsonPrimitive.content == name }["client_uuid"]!!.jsonPrimitive.content

    @Test
    fun `every row lands keyed by uuid with its server id and references by uuid`() = runTest {
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        val house = uuidOf(snapshot.objects, "House")
        val garage = db.objectDao().get(uuidOf(snapshot.objects, "Garage"))!!
        assertEquals(house, garage.parentUuid)
        assertEquals(listOf("House", "Garage"), db.objectDao().ancestors(uuidOf(snapshot.objects, "Main light")).map { it.name })

        val golf = db.objectDao().get(uuidOf(snapshot.objects, "Golf"))!!
        assertEquals(4, golf.serverId)
        assertEquals("km", golf.counterUnit)
        assertEquals(1850000, golf.purchasePriceCents)
        val oil = db.activityDao().get(uuidOf(snapshot.activities, "Oil change", key = "title"))!!
        assertEquals(golf.uuid, oil.objectUuid)
        assertEquals(84210, oil.counterValue)
        val photo = db.attachmentDao().forObject(golf.uuid).first().single()
        assertEquals(oil.uuid, photo.attachment.activityUuid)
        assertEquals("photo", photo.attachment.kind)
        assertEquals("Receipt", photo.attachment.caption)
        assertEquals("image/png", photo.file.mime)
        assertEquals(64, photo.file.sha256.length)
        assertEquals(photo.attachment.uuid, golf.coverAttachmentUuid, "the cover reference is translated to the attachment's uuid")

        val reminders = db.reminderDao().forObject(golf.uuid).first()
        assertEquals(setOf("Oil change", "Inspection", "Monthly reading"), reminders.map { it.title }.toSet())
        val done = reminders.first { it.title == "Oil change" && it.doneAt != null }
        assertEquals(oil.uuid, done.doneActivityUuid)
        val reading = reminders.first { it.kind == "reading" }
        assertEquals(1, reading.everyN)
        assertEquals("month", reading.everyUnit)
    }

    @Test
    fun `sync state records the cursor, epoch and device and clears bootstrap_needed`() = runTest {
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        val s = assertNotNull(db.syncStateDao().get())
        assertEquals(snapshot.seq, s.cursorSeq)
        assertEquals(snapshot.epoch, s.epoch)
        assertEquals("dev-1", s.deviceId)
        assertFalse(s.bootstrapNeeded)
    }

    @Test
    fun `a re-bootstrap replaces server rows but keeps local-only rows, queued ops and the device id`() = runTest {
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        val golf = uuidOf(snapshot.objects, "Golf")
        db.objectDao().upsert(db.objectDao().get(golf)!!.copy(name = "Renamed locally"))
        db.objectDao().upsert(obj("local-only-1", "Offline bike", type = "bike"))
        db.opDao().insert(OpEntity(id = "op1", kind = "create", entity = "object", entityUuid = "local-only-1", field = null, valueJson = null, editedAt = "t"))
        db.fieldClockDao().upsert(FieldClockEntity("object", golf, "name", "2026-01-01T00:00:00.000Z", "dev-1"))

        Bootstrap(db).apply(snapshot, deviceId = "dev-2")

        assertEquals("Golf", db.objectDao().get(golf)!!.name, "the snapshot is the truth for a server row")
        assertNotNull(db.objectDao().get("local-only-1"))
        assertEquals(1, db.opDao().pending().size)
        assertNull(db.fieldClockDao().get("object", golf, "name"), "the clock restarts with the snapshot")
        assertEquals("dev-1", db.syncStateDao().get()!!.deviceId, "a device keeps its identity across bootstraps")
    }
}
