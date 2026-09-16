package dev.logb.android.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.logb.android.MainActivity
import dev.logb.android.R
import dev.logb.android.core.alerts.ReminderNotificationsClearer
import dev.logb.android.feature.share.LaunchTarget
import javax.inject.Inject
import javax.inject.Singleton

/** One notification per due reminder, grouped under a summary; tapping a child opens that reminder, the summary the due list. */
@Singleton
class ReminderNotifier @Inject constructor(@ApplicationContext private val context: Context) : ReminderNotificationsClearer {
    private val manager get() = NotificationManagerCompat.from(context)
    private val systemManager get() = context.getSystemService(NotificationManager::class.java)

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_channel_body)
        manager.createNotificationChannel(channel)
    }

    /**
     * Every child the plan names, grouped under a summary when there is more than one; children
     * from a previous, larger plan that are not named this time are cancelled, and an empty plan
     * cancels everything. Permission is checked twice, as `post(DigestText)` used to: once to
     * bail out early, once right before posting in case it was revoked in between.
     *
     * [lockOn] is LogB's own app lock: when on, every notification is `VISIBILITY_SECRET`, so it
     * does not appear on a locked screen at all -- whatever the device's own sensitive-content
     * setting is -- and Done and Snooze need the device unlocked (API 31+). Log reading opens the
     * app, which asks anyway. Unlocking the phone shows the notifications as normal.
     */
    fun post(plan: DigestNotifications, lockOn: Boolean = false) {
        // Cancellation needs no permission, so it happens even when POST_NOTIFICATIONS is denied
        // or revoked -- a digest that has shrunk to nothing must not leave a stale notification.
        if (plan.children.isEmpty()) { cancelAll(); return }
        if (!canPost()) return
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val keep = plan.children.map { it.id }.toSet()
        activeChildIds().filterNot { it in keep }.forEach { manager.cancel(it) }
        plan.children.forEach { postChild(it, lockOn) }
        val summary = plan.summary
        if (summary != null) postSummary(summary, lockOn) else manager.cancel(DigestPlan.SUMMARY_ID)
    }

    /** Cancels one reminder's own notification, and the summary too once no children are left. */
    fun cancel(reminderUuid: String) {
        manager.cancel(DigestPlan.idFor(reminderUuid))
        if (activeChildIds().isEmpty()) manager.cancel(DigestPlan.SUMMARY_ID)
    }

    override fun clearAll() = cancelAll()

    fun cancelAll() {
        activeChildIds().forEach { manager.cancel(it) }
        manager.cancel(DigestPlan.SUMMARY_ID)
    }

    /** SECRET hides the notification entirely on a locked screen, whatever the device's own sensitive-content setting is. */
    private fun NotificationCompat.Builder.applyPrivacy(lockOn: Boolean): NotificationCompat.Builder = apply {
        if (lockOn) setVisibility(NotificationCompat.VISIBILITY_SECRET)
    }

    private fun postChild(child: ChildNotification, lockOn: Boolean) {
        val tap = activityIntent(LaunchTarget.Reminders, child.objectUuid, requestCode(child.id, TAP_OFFSET))
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(child.title)
            .setContentText(child.text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(GROUP)
            .applyPrivacy(lockOn)
        child.actions.forEach { action ->
            // Writes from a locked phone need it unlocked first; opening the app (Log reading) is gated by the app itself.
            val needsUnlock = lockOn && action != NotificationAction.LogReading
            builder.addAction(
                NotificationCompat.Action.Builder(0, actionLabel(action), actionIntent(child, action))
                    .setAuthenticationRequired(needsUnlock)
                    .build(),
            )
        }
        notify(child.id, builder.build())
    }

    private fun postSummary(text: DigestText, lockOn: Boolean) {
        val open = Intent(context, MainActivity::class.java).setAction(LaunchTarget.ACTION).putExtra(LaunchTarget.EXTRA, LaunchTarget.Due.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, SUMMARY_REQUEST_CODE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text.title)
            .apply { text.body?.let { setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .applyPrivacy(lockOn)
            .build()
        notify(DigestPlan.SUMMARY_ID, notification)
    }

    /** The action button's `PendingIntent`: Done/Snooze write through the receiver, Log reading opens the reading form. */
    private fun actionIntent(child: ChildNotification, action: NotificationAction): PendingIntent = when (action) {
        NotificationAction.Done -> broadcastIntent(ReminderActionReceiver.ACTION_DONE, child)
        NotificationAction.Snooze -> broadcastIntent(ReminderActionReceiver.ACTION_SNOOZE, child)
        NotificationAction.LogReading -> activityIntent(LaunchTarget.Reading, child.objectUuid, requestCode(child.id, NotificationAction.LogReading.ordinal))
    }

    private fun broadcastIntent(action: String, child: ChildNotification): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).setAction(action).putExtra(ReminderActionReceiver.EXTRA_REMINDER, child.reminderUuid)
        val actionOrdinal = if (action == ReminderActionReceiver.ACTION_DONE) NotificationAction.Done.ordinal else NotificationAction.Snooze.ordinal
        return PendingIntent.getBroadcast(context, requestCode(child.id, actionOrdinal), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun activityIntent(target: LaunchTarget, objectUuid: String, code: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java).setAction(LaunchTarget.ACTION).putExtra(LaunchTarget.EXTRA, target.name).putExtra(LaunchTarget.EXTRA_OBJECT, objectUuid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionLabel(action: NotificationAction): String = context.getString(
        when (action) {
            NotificationAction.Done -> R.string.notify_action_done
            NotificationAction.Snooze -> R.string.notify_action_snooze
            NotificationAction.LogReading -> R.string.notify_action_log_reading
        },
    )

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: nothing to post, nothing to crash.
        }
    }

    /** Ids of the children currently posted, read back from the system rather than kept in prefs: it is always right, even after a process death. */
    private fun activeChildIds(): List<Int> =
        systemManager?.activeNotifications?.filter { it.notification.group == GROUP && it.id != DigestPlan.SUMMARY_ID }?.map { it.id } ?: emptyList()

    companion object {
        const val CHANNEL = "reminders"
        const val ID = DigestPlan.SUMMARY_ID
        const val GROUP = "dev.logb.android.reminders"

        /**
         * A child's own request code, offset by an action's ordinal (0, 1, ... one per
         * `NotificationAction`) or by [TAP_OFFSET] for its content tap, so every `PendingIntent`
         * belonging to one child is distinct -- FLAG_UPDATE_CURRENT overwrites another's extras
         * when two collide. The tap and the Log reading action never belong to the same child
         * (a reading reminder's only action is Log reading), so [TAP_OFFSET] never collides with
         * `NotificationAction.LogReading.ordinal`. `child.id` spans the full positive Int range
         * (see `DigestPlan.idFor`), so the multiply is done in Long and folded back with
         * `hashCode()` rather than risking Int overflow.
         */
        private const val TAP_OFFSET = 9
        private const val SUMMARY_REQUEST_CODE = 0
        private fun requestCode(childId: Int, n: Int): Int = (childId.toLong() * 10 + n).hashCode()
    }
}
