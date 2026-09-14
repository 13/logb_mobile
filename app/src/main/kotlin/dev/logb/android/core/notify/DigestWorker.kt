package dev.logb.android.core.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.feature.reminders.DueListModel
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The daily reminder digest: reads the mirror at the chosen hour and posts one notification when
 * anything is due or comes due within seven days. Nothing is sent anywhere; the server's own
 * digest, if configured, is unaffected.
 */
@HiltWorker
class DigestWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val sessions: SessionRepository,
    private val accounts: ActiveAccount,
    private val notifier: ReminderNotifier,
    private val prefs: NotificationPrefs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!prefs.current().enabled) return Result.success()
        if (sessions.session.value is Session.Loading) sessions.restore()
        if (sessions.session.value !is Session.SignedIn) return Result.success()
        val items = DueListModel(accounts.db).items(withinDays = WITHIN_DAYS).first()
        val res = context.resources
        val text = Digest.text(
            items,
            dueWord = res.getString(R.string.notify_due),
            upcomingWord = { days -> res.getQuantityString(R.plurals.notify_in_days, days.toInt(), days) },
            more = { n -> res.getQuantityString(R.plurals.notify_more, n, n) },
        )
        if (text == null) notifier.cancel() else notifier.post(text)
        return Result.success()
    }

    companion object {
        const val WITHIN_DAYS = 7L
        private const val NAME = "logb-digest"

        /**
         * Once a day from the next `hour:minute`. `replace` when the person changed the settings:
         * `UPDATE` would keep the original enqueue time and so the old hour, and `KEEP` (app start)
         * leaves a run that is already due alone instead of pushing it to tomorrow.
         */
        fun schedule(context: Context, settings: NotificationSettings, replace: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!settings.enabled) { work.cancelUniqueWork(NAME); return }
            val delay = Digest.delayUntil(LocalDateTime.now(), settings.hour, settings.minute)
            val request = PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
            work.enqueueUniquePeriodicWork(NAME, policy, request)
        }
    }
}
