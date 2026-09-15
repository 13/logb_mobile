package dev.logb.android.feature.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.logb.android.core.notify.Digest
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * A once-a-day safety net for the widget: a write, a sync or the digest each refresh it already,
 * but the day rolling over past midnight changes what's "due" without any of those firing. Needs
 * no network -- it only reads what is already on the phone.
 */
@HiltWorker
class WidgetMidnightWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "logb-widget-midnight"

        /** Once a day from the next 00:05 local; an existing schedule is kept as-is. */
        fun schedule(context: Context) {
            val delay = Digest.delayUntil(LocalDateTime.now(), 0, 5)
            val request = PeriodicWorkRequestBuilder<WidgetMidnightWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
