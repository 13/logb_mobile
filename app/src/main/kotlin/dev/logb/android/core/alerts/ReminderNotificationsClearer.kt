package dev.logb.android.core.alerts

/**
 * Takes every LogB reminder notification down. Lives in `core` (not `core.notify`, which reads
 * the due list) so `core.auth` can clear notifications on sign-out and on the app lock turning on
 * without depending on the notification code itself -- the same arrangement as `WidgetRefresher`.
 */
fun interface ReminderNotificationsClearer {
    fun clearAll()
}

/** Does nothing: the default for constructors, and for tests that don't care about notifications. */
object NoopReminderNotificationsClearer : ReminderNotificationsClearer {
    override fun clearAll() {}
}
