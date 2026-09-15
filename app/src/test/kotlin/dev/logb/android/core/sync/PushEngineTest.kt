package dev.logb.android.core.sync

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.domain.Tags
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.server.Capabilities
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PushEngineTest {
    private val server = MockWebServer()
    private val db = TestDatabase.inMemory()
    private lateinit var engine: PushEngine
    private lateinit var writer: LocalWriter
    private val store = dev.logb.android.core.blobs.BlobStore(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    @Before
    fun start() = runTest {
        server.start()
        engine = PushEngine(db, ApiClient.create(server.url("/").toString(), { "t" }), store)
        writer = LocalWriter(db)
        db.syncStateDao().upsert(SyncStateEntity(deviceId = "phone-1", bootstrapNeeded = false))
    }

    @After
    fun stop() { server.close(); db.close() }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()
    private val objectDto = """{"id":42,"name":"Golf","type":"car","created_at":"t","updated_at":"t","client_uuid":"u1"}"""

    @Test
    fun `an object create posts the row with its client_uuid and stores the server id`() = runTest {
        writer.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        writer.set("object", "u1", mapOf("name" to "Golf VII")) { db.objectDao().upsert(db.objectDao().get("u1")!!.copy(name = "Golf VII")) }
        server.enqueue(json(objectDto, 201))
        engine.run()
        val r = server.takeRequest()
        assertEquals("/api/objects", r.url.encodedPath)
        val body = r.body!!.utf8()
        assertTrue(body.contains("\"client_uuid\":\"u1\""), body)
        assertTrue(body.contains("\"name\":\"Golf VII\""), "the create carries the row's current values: $body")
        assertEquals(42, db.objectDao().get("u1")!!.serverId)
        assertTrue(db.opDao().pending().isEmpty())
    }

    @Test
    fun `an entry under an unpushed object waits until the object is on the server, in order`() = runTest {
        writer.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        writer.create("activity", "a1") { db.activityDao().upsert(act("a1", "u1", "2026-01-01", title = "Oil")) }
        server.enqueue(json(objectDto, 201))
        server.enqueue(json("""{"id":7,"object_id":42,"date":"2026-01-01","category":"maintenance","title":"Oil","created_at":"t","updated_at":"t"}""", 201))
        engine.run()
        assertEquals("/api/objects", server.takeRequest().url.encodedPath)
        assertEquals("/api/objects/42/activities", server.takeRequest().url.encodedPath)
        assertEquals(7, db.activityDao().get("a1")!!.serverId)
        assertTrue(db.opDao().pending().isEmpty())
    }

    @Test
    fun `sets and a delete become one push batch, and outcomes are applied per op`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4), obj("u2", "Bike", serverId = 5), obj("house", "House", serverId = 6))
        writer.set("object", "u1", mapOf("name" to "Polo", "parent_id" to "house")) { }
        writer.set("object", "u2", mapOf("name" to "Nope")) { }
        writer.delete("object", "u2")
        val ops = db.opDao().pending()
        assertEquals(listOf("set", "set", "delete"), ops.map { it.kind })
        server.enqueue(json("""{"results":[{"client_op_id":"${ops[0].id}","outcome":"accepted"},{"client_op_id":"${ops[1].id}","outcome":"superseded"},{"client_op_id":"${ops[2].id}","outcome":"rejected","reason":"unknown entity_uuid"}],"server_time":"2026-09-14T10:00:00.000Z","ids":{}}"""))
        engine.run()
        val r = server.takeRequest()
        assertEquals("/api/sync/push", r.url.encodedPath)
        val body = r.body!!.utf8()
        assertTrue(body.contains("\"device_id\":\"phone-1\""), body)
        assertTrue(body.contains("\"field\":\"parent_id\",\"value\":6"), "the parent's uuid is sent as the server's id: $body")
        assertTrue(db.opDao().pending().isEmpty())
        val dead = db.opDao().dead()
        assertEquals(1, dead.first().size)
        assertEquals("unknown entity_uuid", dead.first().single().lastError)
    }

    @Test
    fun `an old server without tags or own types gets only the ops it understands, the rest stay pending`() = runTest {
        val oldServerEngine = PushEngine(db, ApiClient.create(server.url("/").toString(), { "t" }), store, Capabilities.NONE)
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        writer.create("object_type", "t1") { db.objectTypeDao().upsert(ObjectTypeEntity("t1", null, "Boat", "tool", "[\"repair\",\"other\"]", "h", "t", "t", null)) }
        writer.set("object", "u1", mapOf("name" to "Golf VII")) { }
        writer.set("object", "u1", mapOf("tags" to Tags.toJson(listOf("Summer")))) { }
        assertEquals(listOf("create", "set", "set"), db.opDao().pending().map { it.kind })

        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val ids = Regex("\"client_op_id\":\"([^\"]+)\"").findAll(request.body?.utf8() ?: "").map { it.groupValues[1] }.toList()
                return json("""{"results":[${ids.joinToString(",") { "{\"client_op_id\":\"$it\",\"outcome\":\"accepted\"}" }}],"server_time":"2026-09-14T10:00:00.000Z","ids":{}}""")
            }
        }
        oldServerEngine.run()

        val push = server.takeRequest()
        assertEquals("/api/sync/push", push.url.encodedPath)
        assertTrue(push.body!!.utf8().contains("\"field\":\"name\""), push.body!!.utf8())
        assertTrue(!push.body!!.utf8().contains("\"field\":\"tags\""), "the tags set must not be pushed to an old server: ${push.body!!.utf8()}")
        assertEquals(1, server.requestCount, "no /api/types request: an object_type create must not be pushed either")

        val pending = db.opDao().pending()
        assertEquals(listOf("create", "set"), pending.map { it.kind }, "the object_type create and the tags set stay pending, not dead")
        assertTrue(db.opDao().dead().first().isEmpty(), "held-back ops are pending, never dead")
    }

    @Test
    fun `a 409 on a create marks it dead, a network failure leaves everything queued, attachments are skipped`() = runTest {
        writer.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        server.enqueue(json("""{"error":"conflict","message":"client_uuid names a row you cannot reuse"}""", 409))
        engine.run()
        assertEquals(1, db.opDao().dead().first().size)

        writer.create("object", "u2") { db.objectDao().upsert(obj("u2", "Bike")) }
        server.close()
        runCatching { engine.run() }
        assertEquals(1, db.opDao().pending().size)
        writer.create("attachment", "t1") { }
        assertEquals(2, db.opDao().pending().size)
    }

    @Test
    fun `a reminder marked done before its create is pushed sends the done fields as sets afterwards`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        writer.create("reminder", "r1") { db.reminderDao().upsert(dev.logb.android.core.db.rem("r1", "u1", title = "Check", dueDate = "2026-09-01")) }
        writer.set("reminder", "r1", mapOf("done_at" to "2026-09-14T10:00:00.000Z")) { db.reminderDao().upsert(db.reminderDao().get("r1")!!.copy(doneAt = "2026-09-14T10:00:00.000Z")) }
        assertEquals(listOf("create"), db.opDao().pending().map { it.kind })
        // The set's op id is minted during the push, so the answer is built from the request.
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/reminders")) return json("""{"id":9,"object_id":4,"title":"Check","due_date":"2026-09-01","created_at":"t"}""", 201)
                val ids = Regex("\"client_op_id\":\"([^\"]+)\"").findAll(request.body?.utf8() ?: "").map { it.groupValues[1] }.toList()
                return json("""{"results":[${ids.joinToString(",") { "{\"client_op_id\":\"$it\",\"outcome\":\"accepted\"}" }}],"server_time":"2026-09-14T10:00:00.000Z","ids":{}}""")
            }
        }
        engine.run()
        assertTrue(db.opDao().pending().isEmpty())
        assertEquals("/api/objects/4/reminders", server.takeRequest().url.encodedPath)
        val push = server.takeRequest()
        assertEquals("/api/sync/push", push.url.encodedPath)
        assertTrue(push.body!!.utf8().contains("\"field\":\"done_at\""), push.body!!.utf8())
    }

    private suspend fun importPng(objectUuid: String, activityUuid: String?): String {
        val bytes = javaClass.getResource("/fixtures/small.png")!!.readBytes()
        return dev.logb.android.feature.entries.AttachmentRepository(db, store, writer).import(bytes.inputStream(), "a.png", "image/png", objectUuid, activityUuid, caption = "Receipt")
    }

    @Test
    fun `an attachment create uploads multipart with its ids and adopts the servers file uuid on dedup`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        db.activityDao().upsert(act("a1", "u1", "2026-01-01").copy(serverId = 7))
        val attachment = importPng("u1", "a1")
        val localFile = db.attachmentDao().get(attachment)!!.fileUuid
        server.enqueue(json("""{"id":33,"object_id":4,"activity_id":7,"file_id":12,"kind":"photo","caption":"Receipt","original_name":"a.png","mime":"image/png","size":91,"created_at":"t","client_uuid":"$attachment","file_uuid":"server-file-uuid"}""", 201))
        engine.run()
        val r = server.takeRequest()
        assertEquals("/api/objects/4/attachments", r.url.encodedPath)
        val body = r.body!!.utf8()
        assertTrue(body.contains("name=\"client_uuid\""), body); assertTrue(body.contains(attachment), body)
        assertTrue(body.contains("name=\"activity_id\"") && body.contains("\r\n\r\n7\r\n"), body)
        assertTrue(body.contains("name=\"caption\"") && body.contains("Receipt"), body)
        assertTrue(body.contains("filename=\"a.png\"") && body.contains("Content-Type: image/png"), body)
        val t = db.attachmentDao().get(attachment)!!
        assertEquals(33, t.serverId); assertEquals("server-file-uuid", t.fileUuid)
        assertEquals(12, db.fileDao().get("server-file-uuid")!!.serverId)
        assertEquals(null, db.fileDao().get(localFile), "the local-only file row is gone")
        assertTrue(db.opDao().pending().isEmpty())
    }

    @Test
    fun `an attachment whose entry has no server id waits, and a missing blob marks the op dead`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        db.activityDao().upsert(act("a1", "u1", "2026-01-01"))
        val attachment = importPng("u1", "a1")
        engine.run()
        assertEquals(0, server.requestCount); assertEquals(1, db.opDao().pending().size)
        db.activityDao().upsert(db.activityDao().get("a1")!!.copy(serverId = 7))
        store.deleteAll(db.fileDao().get(db.attachmentDao().get(attachment)!!.fileUuid)!!.sha256)
        engine.run()
        assertEquals(0, server.requestCount)
        assertEquals("the file is no longer on this phone", db.opDao().dead().first().single().lastError)
    }
}
