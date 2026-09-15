package dev.logb.android.core.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import dev.logb.android.feature.share.LaunchTarget
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ReminderNotifierTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val notifier = ReminderNotifier(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun grantPermission() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** `id` mirrors `DigestPlan.idFor(uuid)`, as a real plan's children always do -- `cancel(uuid)` relies on it. */
    private fun child(uuid: String, actions: List<NotificationAction> = listOf(NotificationAction.Done, NotificationAction.Snooze)) =
        ChildNotification(DigestPlan.idFor(uuid), uuid, "obj-$uuid", "Golf: Oil change", "due", actions)

    @Test fun `three children and a summary are posted with the right actions`() {
        val golf = child("golf")
        val house = child("house")
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))
        val plan = DigestNotifications(DigestText("3 reminders", null), listOf(golf, house, reading))

        notifier.post(plan)

        val active = manager.activeNotifications.associateBy { it.id }
        assertEquals(setOf(golf.id, house.id, reading.id, DigestPlan.SUMMARY_ID), active.keys)
        for (c in listOf(golf, house)) {
            val labels = active.getValue(c.id).notification.actions.map { it.title.toString() }
            assertEquals(listOf("Done", "Snooze 7 days"), labels)
        }
        val summary = active.getValue(DigestPlan.SUMMARY_ID).notification
        assertTrue((summary.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0)
    }

    @Test fun `a reading child has only the Log reading action`() {
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))

        notifier.post(DigestNotifications(null, listOf(reading)))

        val notification = manager.activeNotifications.first { it.id == reading.id }.notification
        assertEquals(listOf("Log reading"), notification.actions.map { it.title.toString() })
    }

    @Test fun `a second post with fewer reminders cancels the stale children`() {
        val golf = child("golf")
        val house = child("house")
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))
        notifier.post(DigestNotifications(DigestText("3 reminders", null), listOf(golf, house, reading)))
        assertEquals(4, manager.activeNotifications.size)

        notifier.post(DigestNotifications(null, listOf(golf)))

        val active = manager.activeNotifications.map { it.id }.toSet()
        assertEquals(setOf(golf.id), active)
    }

    @Test fun `an empty plan cancels everything, including the summary`() {
        val golf = child("golf")
        val house = child("house")
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))
        notifier.post(DigestNotifications(DigestText("3 reminders", null), listOf(golf, house, reading)))

        notifier.post(DigestNotifications(null, emptyList()))

        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun `cancel drops one reminder and takes the summary with it once none are left`() {
        val golf = child("golf")
        notifier.post(DigestNotifications(null, listOf(golf)))
        assertEquals(1, manager.activeNotifications.size)

        notifier.cancel("golf")

        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun `Done and Snooze broadcast the right action and the reminder uuid`() {
        val golf = child("golf")
        notifier.post(DigestNotifications(null, listOf(golf)))

        val notification = manager.activeNotifications.first { it.id == golf.id }.notification
        val (doneAction, snoozeAction) = notification.actions.toList()

        val done = shadowOf(doneAction.actionIntent).savedIntent
        assertEquals(ReminderActionReceiver.ACTION_DONE, done.action)
        assertEquals("golf", done.getStringExtra(ReminderActionReceiver.EXTRA_REMINDER))

        val snooze = shadowOf(snoozeAction.actionIntent).savedIntent
        assertEquals(ReminderActionReceiver.ACTION_SNOOZE, snooze.action)
        assertEquals("golf", snooze.getStringExtra(ReminderActionReceiver.EXTRA_REMINDER))
    }

    @Test fun `the content tap opens Reminders for the child's object`() {
        val golf = child("golf")
        notifier.post(DigestNotifications(null, listOf(golf)))

        val notification = manager.activeNotifications.first { it.id == golf.id }.notification
        val tap = shadowOf(notification.contentIntent).savedIntent

        assertEquals(LaunchTarget.Reminders.name, tap.getStringExtra(LaunchTarget.EXTRA))
        assertEquals("obj-golf", tap.getStringExtra(LaunchTarget.EXTRA_OBJECT))
    }

    @Test fun `Log reading opens the Reading form for the child's object`() {
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))
        notifier.post(DigestNotifications(null, listOf(reading)))

        val notification = manager.activeNotifications.first { it.id == reading.id }.notification
        val logReading = shadowOf(notification.actions.single().actionIntent).savedIntent

        assertEquals(LaunchTarget.Reading.name, logReading.getStringExtra(LaunchTarget.EXTRA))
        assertEquals("obj-reading", logReading.getStringExtra(LaunchTarget.EXTRA_OBJECT))
        // The Log reading child's own content tap is unaffected: it still opens Reminders, not Reading.
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(LaunchTarget.Reminders.name, tap.getStringExtra(LaunchTarget.EXTRA))
    }

    private fun extrasText(n: android.app.Notification): String =
        listOf(android.app.Notification.EXTRA_TITLE, android.app.Notification.EXTRA_TEXT, android.app.Notification.EXTRA_BIG_TEXT)
            .joinToString(" ") { n.extras.getCharSequence(it)?.toString().orEmpty() }

    @Test fun `with the app lock on, every notification is private with a public version that names nothing`() {
        val golf = child("golf")
        val house = child("house")
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))
        notifier.post(DigestNotifications(DigestText("Golf: Oil change due · 2 more", null), listOf(golf, house, reading), total = 3), lockOn = true)

        val active = manager.activeNotifications
        assertEquals(4, active.size)
        for (sbn in active) {
            val n = sbn.notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
            val public = assertNotNull(n.publicVersion, "id ${sbn.id} has no public version")
            assertEquals("LogB", public.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            assertEquals("3 reminders due", public.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertFalse(extrasText(public).contains("Golf"), "public version of ${sbn.id} leaks a name: ${extrasText(public)}")
            assertFalse(extrasText(public).contains("Oil change"))
        }
    }

    @Test fun `with the app lock on, Done and Snooze need the device unlocked, Log reading does not`() {
        val golf = child("golf")
        val reading = child("reading", actions = listOf(NotificationAction.LogReading))
        notifier.post(DigestNotifications(null, listOf(golf, reading)), lockOn = true)

        val byId = manager.activeNotifications.associateBy { it.id }
        val actions = byId.getValue(golf.id).notification.actions.toList()
        assertEquals(listOf("Done", "Snooze 7 days"), actions.map { it.title.toString() })
        assertTrue(actions.all { it.isAuthenticationRequired })
        assertFalse(byId.getValue(reading.id).notification.actions.single().isAuthenticationRequired)
    }

    @Test fun `with the app lock off, notifications keep the default visibility, no public version and no unlock requirement`() {
        val golf = child("golf")
        val house = child("house")
        notifier.post(DigestNotifications(DigestText("2 reminders", null), listOf(golf, house)), lockOn = false)

        for (sbn in manager.activeNotifications) {
            val n = sbn.notification
            assertNull(n.publicVersion)
            assertEquals(android.app.Notification().visibility, n.visibility)
            assertTrue(n.actions.orEmpty().none { it.isAuthenticationRequired })
        }
    }

    @Test fun `clearAll takes every reminder notification down`() {
        notifier.post(DigestNotifications(DigestText("2", null), listOf(child("golf"), child("house"))))
        (notifier as dev.logb.android.core.alerts.ReminderNotificationsClearer).clearAll()
        assertEquals(0, manager.activeNotifications.size)
    }
}
