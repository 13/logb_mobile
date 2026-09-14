package dev.logb.android.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The periodic pass that keeps the mirror current when the app has not been opened. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = syncManager.syncNow()
        return when {
            outcome.isSuccess -> Result.success()
            outcome.exceptionOrNull() is IOException -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val NAME = "logb-sync"
        private const val ONCE = "logb-sync-once"

        /** After a write: run as soon as there is a connection, even if the app is gone by then. */
        fun runWhenConnected(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
