package dev.logb.android.core.notify

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.sync.LocalWriter
import dev.logb.android.feature.objects.ObjectRepository
import dev.logb.android.feature.reminders.ReminderRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class ReminderActionsTest {
    private val db = TestDatabase.inMemory()
    private val writer = LocalWriter(db)
    private val reminders = ReminderRepository(db, writer)
    private val cancelled = mutableListOf<String>()
    private val actions = ReminderActions(
        done = { uuid -> reminders.done(uuid, null) },
        snooze = { uuid -> reminders.snooze(uuid, 7) },
        cancel = { uuid -> cancelled += uuid },
    )

    @Test fun `done marks the reminder done through the op queue and cancels its notification`() = runBlocking {
        val objectUuid = ObjectRepository(db, writer).create(ObjectDraft(name = "Golf"))
        val r = reminders.create(objectUuid, ReminderDraft(title = "Oil", dueDate = "2026-09-01"), LocalDate.parse("2026-09-15"))
        actions.done(r)
        assertNotNull(db.reminderDao().get(r)!!.doneAt)
        assertEquals(listOf(r), cancelled)
    }

    @Test fun `snooze moves it a week and cancels its notification`() = runBlocking {
        val objectUuid = ObjectRepository(db, writer).create(ObjectDraft(name = "Golf"))
        val r = reminders.create(objectUuid, ReminderDraft(title = "Oil", dueDate = "2026-09-01"), LocalDate.parse("2026-09-15"))
        actions.snooze(r)
        assertNotNull(db.reminderDao().get(r)!!.snoozedUntil)
        assertEquals(listOf(r), cancelled)
    }
}
