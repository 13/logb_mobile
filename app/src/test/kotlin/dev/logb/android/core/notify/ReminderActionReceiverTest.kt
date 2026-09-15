package dev.logb.android.core.notify

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * `onReceive` needs a Hilt entry point to fetch [ReminderActions], which is not set up in this
 * plain Robolectric unit test -- so the dispatch by action string is tested directly against
 * [ReminderActionReceiver.dispatch], the pure piece `onReceive` delegates to. The one thing
 * `onReceive` itself does before touching Hilt -- bail out when `EXTRA_REMINDER` is missing --
 * is exercised through the real receiver, since that path returns before any Hilt lookup.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderActionReceiverTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun `the Done action calls done with the reminder uuid`() = runBlocking {
        val done = mutableListOf<String>()
        val snooze = mutableListOf<String>()
        val actions = ReminderActions(ensureSignedIn = { true }, done = { done += it }, snooze = { snooze += it }, cancel = {})

        ReminderActionReceiver.dispatch(actions, ReminderActionReceiver.ACTION_DONE, "r-1")

        assertEquals(listOf("r-1"), done)
        assertEquals(emptyList(), snooze)
    }

    @Test fun `the Snooze action calls snooze with the reminder uuid`() = runBlocking {
        val done = mutableListOf<String>()
        val snooze = mutableListOf<String>()
        val actions = ReminderActions(ensureSignedIn = { true }, done = { done += it }, snooze = { snooze += it }, cancel = {})

        ReminderActionReceiver.dispatch(actions, ReminderActionReceiver.ACTION_SNOOZE, "r-2")

        assertEquals(listOf("r-2"), snooze)
        assertEquals(emptyList(), done)
    }

    @Test fun `an unrecognised action does nothing`() = runBlocking {
        val done = mutableListOf<String>()
        val snooze = mutableListOf<String>()
        val actions = ReminderActions(ensureSignedIn = { true }, done = { done += it }, snooze = { snooze += it }, cancel = {})

        ReminderActionReceiver.dispatch(actions, "some.other.action", "r-3")

        assertEquals(emptyList(), done)
        assertEquals(emptyList(), snooze)
    }

    @Test fun `an intent without the reminder extra does nothing and does not crash`() {
        // No EXTRA_REMINDER: onReceive returns before it ever needs the Hilt entry point.
        val intent = Intent(ReminderActionReceiver.ACTION_DONE)

        ReminderActionReceiver().onReceive(context, intent)
    }
}
