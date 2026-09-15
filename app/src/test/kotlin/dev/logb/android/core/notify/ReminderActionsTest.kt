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
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ReminderActionsTest {
    private val db = TestDatabase.inMemory()
    private val writer = LocalWriter(db)
    private val reminders = ReminderRepository(db, writer)
    private val cancelled = mutableListOf<String>()
    private val actions = ReminderActions(
        ensureSignedIn = { true },
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

    @Test fun `signed out -- nothing to write, so the notification is just cleared`() = runBlocking {
        val objectUuid = ObjectRepository(db, writer).create(ObjectDraft(name = "Golf"))
        val r = reminders.create(objectUuid, ReminderDraft(title = "Oil", dueDate = "2026-09-01"), LocalDate.parse("2026-09-15"))
        val signedOut = ReminderActions(
            ensureSignedIn = { false },
            done = { reminders.done(it, null) },
            snooze = { reminders.snooze(it, 7) },
            cancel = { uuid -> cancelled += uuid },
        )

        signedOut.done(r)

        assertNull(db.reminderDao().get(r)!!.doneAt)
        assertEquals(listOf(r), cancelled)
    }

    @Test fun `a write that throws keeps the notification and does not throw itself`() = runBlocking {
        val failing = ReminderActions(
            ensureSignedIn = { true },
            done = { throw IllegalStateException("boom") },
            snooze = { throw IllegalStateException("boom") },
            cancel = { uuid -> cancelled += uuid },
        )

        failing.done("r-1") // must not throw

        assertEquals(emptyList(), cancelled)
    }

    @Test fun `ensureSignedIn throwing (a store read failing) keeps the notification and does not throw itself`() = runBlocking {
        val failing = ReminderActions(
            ensureSignedIn = { throw IllegalStateException("store read failed") },
            done = { reminders.done(it, null) },
            snooze = { reminders.snooze(it, 7) },
            cancel = { uuid -> cancelled += uuid },
        )

        failing.done("r-1") // must not throw

        assertEquals(emptyList(), cancelled)
    }

    @Test fun `loading resolves through restore before the write happens`() = runBlocking {
        val objectUuid = ObjectRepository(db, writer).create(ObjectDraft(name = "Golf"))
        val r = reminders.create(objectUuid, ReminderDraft(title = "Oil", dueDate = "2026-09-01"), LocalDate.parse("2026-09-15"))
        var state = "loading"
        var restored = false
        val loading = ReminderActions(
            ensureSignedIn = {
                if (state == "loading") { restored = true; state = "signed-in" }
                state == "signed-in"
            },
            done = { reminders.done(it, null) },
            snooze = { reminders.snooze(it, 7) },
            cancel = { uuid -> cancelled += uuid },
        )

        loading.done(r)

        assertEquals(true, restored)
        assertNotNull(db.reminderDao().get(r)!!.doneAt)
        assertEquals(listOf(r), cancelled)
    }

    @Test fun `done refreshes the widget inside the action, after the write and the cancel`() = runBlocking {
        val objectUuid = ObjectRepository(db, writer).create(ObjectDraft(name = "Golf"))
        val r = reminders.create(objectUuid, ReminderDraft(title = "Oil", dueDate = "2026-09-01"), LocalDate.parse("2026-09-15"))
        val order = mutableListOf<String>()
        val acting = ReminderActions(
            ensureSignedIn = { true },
            done = { uuid -> reminders.done(uuid, null); order += "write" },
            snooze = { reminders.snooze(it, 7) },
            cancel = { order += "cancel" },
            refreshWidget = { order += "refresh" },
        )

        acting.done(r)

        assertEquals(listOf("write", "cancel", "refresh"), order)
    }

    @Test fun `snooze refreshes the widget too`() = runBlocking {
        val order = mutableListOf<String>()
        val acting = ReminderActions(
            ensureSignedIn = { true },
            done = {},
            snooze = { order += "write" },
            cancel = { order += "cancel" },
            refreshWidget = { order += "refresh" },
        )

        acting.snooze("r-1")

        assertEquals(listOf("write", "cancel", "refresh"), order)
    }

    @Test fun `signed out still refreshes, so a stale row cannot survive the tap`() = runBlocking {
        val order = mutableListOf<String>()
        val acting = ReminderActions(
            ensureSignedIn = { false },
            done = { order += "write" },
            snooze = {},
            cancel = { order += "cancel" },
            refreshWidget = { order += "refresh" },
        )

        acting.done("r-1")

        assertEquals(listOf("cancel", "refresh"), order)
    }

    @Test fun `a failed write refreshes nothing, leaving the notification and the widget as they were`() = runBlocking {
        val order = mutableListOf<String>()
        val acting = ReminderActions(
            ensureSignedIn = { true },
            done = { throw IllegalStateException("boom") },
            snooze = {},
            cancel = { order += "cancel" },
            refreshWidget = { order += "refresh" },
        )

        acting.done("r-1")

        assertEquals(emptyList(), order)
    }
}
