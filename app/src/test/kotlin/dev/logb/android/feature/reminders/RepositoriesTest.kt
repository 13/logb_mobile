package dev.logb.android.feature.reminders

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import dev.logb.android.core.db.rem
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.sync.LocalWriter
import dev.logb.android.feature.objects.ObjectRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RepositoriesTest {
    private val db = TestDatabase.inMemory()
    private val writer = LocalWriter(db)
    private val today = LocalDate.parse("2026-09-14")
    private var writes = 0

    @After fun close() = db.close()

    @Test
    fun `done marks the reminder, links the entry, and creates the successor from today and the counter`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf", serverId = 4))
        db.activityDao().upsert(act("a1", "golf", "2026-09-10", counter = 86_000).copy(serverId = 1))
        db.reminderDao().upsert(rem("r1", "golf", title = "Oil", dueDate = "2026-09-01", dueCounter = 85_000).copy(repeatMonths = 12, repeatCounter = 15_000, serverId = 9))
        val repo = ReminderRepository(db, writer) { writes++ }
        val successor = repo.done("r1", "a1", today)
        val done = db.reminderDao().get("r1")!!
        assertNotNull(done.doneAt); assertEquals("a1", done.doneActivityUuid)
        val next = db.reminderDao().get(successor!!)!!
        assertEquals("2027-09-14", next.dueDate); assertEquals(101_000, next.dueCounter); assertEquals("Oil", next.title); assertNull(next.doneAt)
        val ops = db.opDao().pending()
        assertEquals(listOf("set", "set", "create"), ops.map { it.kind })
        assertEquals(listOf("done_at", "done_activity_id"), ops.take(2).map { it.field })
        assertEquals(2, writes, "the done and the successor's create each ask for a sync")
        assertNull(repo.done("r1", null, today), "done twice does nothing")
    }

    @Test
    fun `snooze lands after the later of today and the due date, unsnooze clears it`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf", serverId = 4))
        db.reminderDao().upsert(rem("r1", "golf", dueDate = "2026-06-01").copy(serverId = 9))
        val repo = ReminderRepository(db, writer)
        repo.snooze("r1", 7, today)
        assertEquals("2026-09-21", db.reminderDao().get("r1")!!.snoozedUntil)
        repo.unsnooze("r1")
        assertNull(db.reminderDao().get("r1")!!.snoozedUntil)
        assertEquals(listOf("\"2026-09-21\"", "null"), db.opDao().pending().map { it.valueJson })
    }

    @Test
    fun `creating an object with templates makes its reminders and logs the first reading`() = runTest {
        val repo = ObjectRepository(db, writer)
        val oil = ReminderDraft(title = "Oil change", dueDate = "2027-09-14", repeatMonths = 12, dueCounter = 95_000, repeatCounter = 15_000)
        val uuid = repo.create(ObjectDraft(name = "Golf", type = "car", counterUnit = "km"), templates = listOf(oil), currentReading = 80_000, today = today)
        assertEquals("Golf", db.objectDao().get(uuid)!!.name)
        assertEquals(80_000, db.objectDao().stats(uuid).currentCounter)
        assertEquals(listOf("Oil change"), db.reminderDao().forObject(uuid).first().map { it.title })
        assertEquals(listOf("create", "create", "create"), db.opDao().pending().map { it.kind })
    }

    @Test
    fun `update queues only the fields that changed and parent candidates exclude self and descendants`() = runTest {
        db.objectDao().upsert(obj("house", "House", type = "home", serverId = 1), obj("garage", "Garage", type = "home", parent = "house", serverId = 2), obj("golf", "Golf", serverId = 3))
        val repo = ObjectRepository(db, writer)
        repo.update("garage", ObjectDraft(name = "Double garage", type = "home", counterUnit = "km", parentUuid = "house"))
        assertEquals(listOf("name"), db.opDao().pending().map { it.field })
        assertEquals(listOf("Golf"), repo.candidatesForParent("house").map { it.name })
        assertEquals(setOf("House", "Double garage", "Golf"), repo.candidatesForParent(null).map { it.name }.toSet())
    }
}
