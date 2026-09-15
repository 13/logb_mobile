package dev.logb.android.core.notify

/** Done and snooze from outside the app (notification, widget): the same writes the app makes, then the notification goes. */
class ReminderActions(
    private val done: suspend (String) -> Unit,
    private val snooze: suspend (String) -> Unit,
    private val cancel: (String) -> Unit,
) {
    suspend fun done(reminderUuid: String) { done.invoke(reminderUuid); cancel(reminderUuid) }
    suspend fun snooze(reminderUuid: String) { snooze.invoke(reminderUuid); cancel(reminderUuid) }
}
