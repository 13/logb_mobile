package dev.logb.android.core.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
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
}
