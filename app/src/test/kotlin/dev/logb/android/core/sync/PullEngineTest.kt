package dev.logb.android.core.sync

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.UnauthorizedException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PullEngineTest {
    private val server = MockWebServer()
    private val db = TestDatabase.inMemory()
    private lateinit var engine: PullEngine

    @Before
    fun start() {
        server.start()
        engine = PullEngine(db, ApiClient.create(server.url("/").toString(), { "t" }), deviceId = "dev-1", pageSize = 2)
    }

    @After
    fun stop() {
        server.close()
        db.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private val emptyBootstrap = """{"objects":[],"activities":[],"reminders":[],"attachments":[],"files":[],
        "seq":5,"server_time":"2026-09-01T00:00:00.000Z","epoch":"e1"}"""

    private fun page(seqs: List<Long>, next: Long, complete: Boolean, epoch: String = "e1") = json(
        """{"changes":[${seqs.joinToString(",") { """{"seq":$it,"entity":"object","entity_uuid":"u$it","op":"create","edited_at":"2026-09-01T00:00:00.000Z","device_id":"rest","entity_id":$it}""" }}],
        "next_seq":$next,"complete":$complete,"server_time":"2026-09-01T00:00:10.000Z","epoch":"$epoch"}""",
    )

    @Test
    fun `no state means bootstrap first, then pages until complete, advancing the cursor`() = runTest {
        server.enqueue(json(emptyBootstrap))
        server.enqueue(page(listOf(6, 7), next = 7, complete = false))
        server.enqueue(page(listOf(8), next = 8, complete = true))
        engine.run()
        assertEquals("/api/sync/bootstrap", server.takeRequest().url.encodedPath)
        val first = server.takeRequest().url
        assertEquals("5", first.queryParameter("since"))
        assertEquals("e1", first.queryParameter("epoch"))
        assertEquals("7", server.takeRequest().url.queryParameter("since"))
        val s = assertNotNull(db.syncStateDao().get())
        assertEquals(8, s.cursorSeq)
        assertEquals("e1", s.epoch)
        assertFalse(s.bootstrapNeeded)
        assertNotNull(db.objectDao().get("u8"))
    }

    @Test
    fun `a 410 re-bootstraps and resumes from the new cursor`() = runTest {
        db.syncStateDao().upsert(SyncStateEntity(cursorSeq = 99, epoch = "old", deviceId = "dev-1", bootstrapNeeded = false))
        server.enqueue(json("""{"error":"gone","message":"re-bootstrap"}""", code = 410))
        server.enqueue(json(emptyBootstrap))
        server.enqueue(page(listOf(6), next = 6, complete = true))
        engine.run()
        assertEquals("99", server.takeRequest().url.queryParameter("since"))
        assertEquals("/api/sync/bootstrap", server.takeRequest().url.encodedPath)
        assertEquals("5", server.takeRequest().url.queryParameter("since"))
        assertEquals(6, db.syncStateDao().get()!!.cursorSeq)
    }

    @Test
    fun `bootstrap_needed in the state forces a bootstrap before pulling`() = runTest {
        db.syncStateDao().upsert(SyncStateEntity(cursorSeq = 3, epoch = "e1", deviceId = "dev-1", bootstrapNeeded = true))
        server.enqueue(json(emptyBootstrap))
        server.enqueue(page(emptyList(), next = 5, complete = true))
        engine.run()
        assertEquals("/api/sync/bootstrap", server.takeRequest().url.encodedPath)
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/sync/pull"))
    }

    @Test
    fun `a bare create from a REST client is healed by a bootstrap in the same run`() = runTest {
        // A browser-made reminder reaches the feed as a `create` with no values; the applier can only
        // placeholder it and ask for a bootstrap. That request must survive the cursor write and be
        // served before the run ends, not fifteen minutes later.
        val fixture = javaClass.getResource("/fixtures/bootstrap.json")!!.readText()
        val uuid = "9e9e9e9e-0000-4000-8000-000000000042"
        val reminder = """{"client_uuid":"$uuid","created_at":"2026-09-14T13:06:02Z","deleted_at":null,"done_activity_id":null,"done_at":null,"due_counter":null,"due_date":"2026-09-16","every_n":null,"every_unit":null,"id":42,"kind":"service","notes":"","object_id":4,"repeat_counter":null,"repeat_months":null,"snoozed_until":null,"title":"Tyre pressure","updated_at":"2026-09-14T13:06:02Z"}"""
        val withReminder = fixture.replace("\"reminders\": [", "\"reminders\": [$reminder,").replace("\"seq\": 16", "\"seq\": 24")
        db.syncStateDao().upsert(SyncStateEntity(cursorSeq = 16, epoch = "c992a0a377fe349ac07becb5fa0bbee5", deviceId = "dev-1", bootstrapNeeded = false))
        server.enqueue(json("""{"changes":[{"seq":24,"entity":"reminder","entity_uuid":"$uuid","op":"create","field":null,"value":null,"edited_at":"2026-09-14T13:06:02.279Z","device_id":"rest","entity_id":42}],
            "next_seq":24,"complete":true,"server_time":"2026-09-14T13:06:10.000Z","epoch":"c992a0a377fe349ac07becb5fa0bbee5"}"""))
        server.enqueue(json(withReminder))
        engine.run()
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/sync/pull"))
        assertEquals("/api/sync/bootstrap", server.takeRequest().url.encodedPath)
        val row = assertNotNull(db.reminderDao().get(uuid))
        assertEquals("Tyre pressure", row.title)
        assertEquals(db.objectDao().uuidForServerId(4), row.objectUuid)
        val s = assertNotNull(db.syncStateDao().get())
        assertFalse(s.bootstrapNeeded)
        assertEquals(24, s.cursorSeq)
    }

    @Test
    fun `a 401 propagates untouched`() = runTest {
        db.syncStateDao().upsert(SyncStateEntity(cursorSeq = 3, epoch = "e1", deviceId = "dev-1", bootstrapNeeded = false))
        server.enqueue(json("""{"error":"unauthorized","message":"x"}""", code = 401))
        assertFailsWith<UnauthorizedException> { engine.run() }
    }
}
