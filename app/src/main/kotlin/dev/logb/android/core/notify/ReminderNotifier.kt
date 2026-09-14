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
import dev.logb.android.feature.share.LaunchTarget
import javax.inject.Inject
import javax.inject.Singleton

/** One notification on one channel; tapping it opens the due list. */
@Singleton
class ReminderNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager get() = NotificationManagerCompat.from(context)

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_channel_body)
        manager.createNotificationChannel(channel)
    }

    fun post(text: DigestText) {
        if (!canPost()) return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java).setAction(LaunchTarget.ACTION).putExtra(LaunchTarget.EXTRA, LaunchTarget.Due.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text.title)
            .apply { text.body?.let { setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun cancel() { manager.cancel(ID) }

    companion object {
        const val CHANNEL = "reminders"
        const val ID = 1
    }
}
