package dev.logb.android.core.notify

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Done and snooze from outside the app (notification, widget): the same writes the app makes,
 * then the notification goes. A process started just to handle the broadcast may not have
 * restored its session yet -- [ensureSignedIn] does that, as `DigestWorker` does before its own
 * first read -- and a write must never crash the process it was handed: whatever goes wrong is
 * logged and the notification is left standing so the person can retry from it.
 *
 * [refreshWidget] runs here, inside the action, rather than being left to the refresher's 500 ms
 * debounce: the process handling a notification tap may be cold and can be reclaimed the moment
 * the broadcast finishes, so a wait that short is not guaranteed to happen at all.
 */
class ReminderActions(
    private val ensureSignedIn: suspend () -> Boolean,
    private val done: suspend (String) -> Unit,
    private val snooze: suspend (String) -> Unit,
    private val cancel: (String) -> Unit,
    private val refreshWidget: suspend () -> Unit = {},
) {
    suspend fun done(reminderUuid: String) = act(reminderUuid, done)

    suspend fun snooze(reminderUuid: String) = act(reminderUuid, snooze)

    private suspend fun act(reminderUuid: String, write: suspend (String) -> Unit) {
        try {
            // Signed out (or a server not even chosen yet): nothing to write, so there is
            // nothing this notification can still do -- it is cleared rather than left dangling.
            if (!ensureSignedIn()) { cancel(reminderUuid); refreshWidget(); return }
            write(reminderUuid)
            cancel(reminderUuid)
            refreshWidget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Covers ensureSignedIn (a store read) failing too, not just the write: either way
            // nothing happened, so the notification is left up rather than silently cleared.
            Log.e(TAG, "reminder action failed for $reminderUuid", e)
        }
    }

    companion object {
        private const val TAG = "LogB"
    }
}
