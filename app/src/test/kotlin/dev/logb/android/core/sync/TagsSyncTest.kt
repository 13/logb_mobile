package dev.logb.android.core.sync

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.domain.ActivityDraft
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.Tags
import dev.logb.android.core.domain.TagCount
import dev.logb.android.core.network.LogbJson
import dev.logb.android.core.network.dto.BootstrapResult
import dev.logb.android.feature.entries.ActivityRepository
import dev.logb.android.feature.objects.ObjectRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TagsSyncTest {
    private val db = TestDatabase.inMemory()
    private fun row(json: String): JsonObject = LogbJson.parseToJsonElement(json).jsonObject

    @Test fun `bootstrap stores tags as the server sends them`() = runBlocking {
        val snapshot = BootstrapResult(
            objects = listOf(row("""{"id":1,"client_uuid":"o1","name":"Golf","type":"car","description":"","created_at":"t","updated_at":"t","tags":"[\"Winter\",\"Lease\"]"}""")),
            activities = listOf(row("""{"id":2,"client_uuid":"a1","object_id":1,"date":"2026-01-01","category":"repair","title":"Brakes","notes":"","created_at":"t","updated_at":"t","tags":"[\"Winter\"]"}""")),
            reminders = emptyList(), attachments = emptyList(), files = emptyList(), seq = 1, serverTime = "2026-01-01T00:00:00.000Z", epoch = "e",
        )
        Bootstrap(db).apply(snapshot, deviceId = "d")
        assertEquals(listOf("Winter", "Lease"), Tags.fromJson(db.objectDao().get("o1")!!.tags))
        assertEquals(listOf("Winter"), Tags.fromJson(db.activityDao().get("a1")!!.tags))
    }

    @Test fun `a pulled tags set is written to the row`() = runBlocking {
        val repo = ObjectRepository(db, LocalWriter(db))
        val uuid = repo.create(ObjectDraft(name = "Golf"))
        FieldWriter.write(db, "object", uuid, "tags", """["Winter"]""", "2026-01-02T00:00:00.000Z")
        assertEquals("""["Winter"]""", db.objectDao().get(uuid)!!.tags)
    }

    @Test fun `editing tags queues one set op carrying the JSON text`() = runBlocking {
        val writer = LocalWriter(db)
        val repo = ObjectRepository(db, writer)
        val uuid = repo.create(ObjectDraft(name = "Golf"))
        db.opDao().pending().forEach { db.opDao().delete(it.id) } // pretend the create was pushed
        db.objectDao().upsert(db.objectDao().get(uuid)!!.copy(serverId = 5))
        repo.update(uuid, ObjectDraft(name = "Golf", tags = listOf("Winter")))
        val op = db.opDao().pending().single()
        assertEquals("tags", op.field)
        assertEquals(LogbJson.encodeToString(kotlinx.serialization.serializer<String>(), """["Winter"]"""), op.valueJson)
    }

    @Test fun `tag columns feed the counts`() = runBlocking {
        val repo = ObjectRepository(db, LocalWriter(db))
        repo.create(ObjectDraft(name = "A", tags = listOf("Winter")))
        repo.create(ObjectDraft(name = "B", tags = listOf("winter", "Lease")))
        assertEquals(listOf(TagCount("Winter", 2), TagCount("Lease", 1)), Tags.count(db.objectDao().tagColumns().first()))
    }

    @Test fun `editing an object with a draft that carries no tags keeps its tags and queues no tags op`() = runBlocking {
        val repo = ObjectRepository(db, LocalWriter(db))
        val uuid = repo.create(ObjectDraft(name = "Golf", tags = listOf("Winter")))
        db.opDao().pending().forEach { db.opDao().delete(it.id) }
        db.objectDao().upsert(db.objectDao().get(uuid)!!.copy(serverId = 5))
        repo.update(uuid, ObjectDraft(name = "Golf GTI"))
        assertEquals("""["Winter"]""", db.objectDao().get(uuid)!!.tags)
        assertEquals(listOf("name"), db.opDao().pending().map { it.field })
    }

    @Test fun `editing an entry with a draft that carries no tags keeps its tags`() = runBlocking {
        val objects = ObjectRepository(db, LocalWriter(db))
        val activities = ActivityRepository(db, LocalWriter(db))
        val objectUuid = objects.create(ObjectDraft(name = "Golf"))
        val uuid = activities.create(objectUuid, ActivityDraft(date = "2026-01-01", category = "repair", title = "Brakes", tags = listOf("Winter")))
        db.opDao().pending().forEach { db.opDao().delete(it.id) }
        db.activityDao().upsert(db.activityDao().get(uuid)!!.copy(serverId = 6))
        activities.update(uuid, ActivityDraft(date = "2026-01-01", category = "repair", title = "Brake pads"))
        assertEquals("""["Winter"]""", db.activityDao().get(uuid)!!.tags)
        assertEquals(listOf("title"), db.opDao().pending().map { it.field })
    }
}
