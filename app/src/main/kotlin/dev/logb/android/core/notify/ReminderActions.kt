package dev.logb.android.core.notify

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Done and snooze from outside the app (notification, widget): the same writes the app makes,
 * then the notification goes. A process started just to handle the broadcast may not have
 * restored its session yet -- [ensureSignedIn] does that, as `DigestWorker` does before its own
 * first read -- and a write must never crash the process it was handed: whatever goes wrong is
 * logged and the notification is left standing so the person can retry from it.
 */
class ReminderActions(
    private val ensureSignedIn: suspend () -> Boolean,
    private val done: suspend (String) -> Unit,
    private val snooze: suspend (String) -> Unit,
    private val cancel: (String) -> Unit,
) {
    suspend fun done(reminderUuid: String) = act(reminderUuid, done)

    suspend fun snooze(reminderUuid: String) = act(reminderUuid, snooze)

    private suspend fun act(reminderUuid: String, write: suspend (String) -> Unit) {
        // Signed out (or a server not even chosen yet): nothing to write, so there is nothing
        // this notification can still do -- it is cleared rather than left dangling.
        if (!ensureSignedIn()) { cancel(reminderUuid); return }
        try {
            write(reminderUuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "reminder action failed for $reminderUuid", e)
            return
        }
        cancel(reminderUuid)
    }

    companion object {
        private const val TAG = "LogB"
    }
}
