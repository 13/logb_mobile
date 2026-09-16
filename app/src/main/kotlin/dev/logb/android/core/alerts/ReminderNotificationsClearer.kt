package dev.logb.android.core.alerts

/**
 * Takes every LogB reminder notification down. Lives in `core` (not `core.notify`, which reads
 * the due list) so `core.auth` can clear notifications on sign-out and on the app lock turning on
 * without depending on the notification code itself -- the same arrangement as `WidgetRefresher`.
 *
 * `suspend` so the real implementation can hop off the caller's dispatcher: `ReminderNotifier`'s
 * work is Binder IPC to `NotificationManager`, and callers include `viewModelScope` (Main).
 */
fun interface ReminderNotificationsClearer {
    suspend fun clearAll()
}

/** Does nothing: the default for constructors, and for tests that don't care about notifications. */
object NoopReminderNotificationsClearer : ReminderNotificationsClearer {
    override suspend fun clearAll() {}
}
