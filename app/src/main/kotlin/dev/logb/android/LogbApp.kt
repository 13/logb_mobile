package dev.logb.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.blobs.BlobDownloader
import dev.logb.android.core.blobs.BlobFetcher
import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.notify.DigestWorker
import dev.logb.android.core.notify.NotificationPrefs
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import dagger.hilt.android.HiltAndroidApp
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncReason
import dev.logb.android.core.sync.SyncWorker
import javax.inject.Inject

@HiltAndroidApp
class LogbApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var accounts: ActiveAccount
    @Inject lateinit var blobStore: BlobStore
    @Inject lateinit var downloader: () -> BlobDownloader?
    @Inject lateinit var notificationPrefs: NotificationPrefs
    @Inject lateinit var appScope: kotlinx.coroutines.CoroutineScope

    /** Every image is a `BlobImage` served from the blob store, fetched from the server first when missing; plain URLs still work through the account's client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(BlobFetcher.Key)
                add(BlobFetcher.Factory(blobStore, downloader))
                add(OkHttpNetworkFetcherFactory(callFactory = { accounts.httpClient ?: OkHttpClient() }))
            }
            .crossfade(true)
            .build()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
        // Re-asserts the digest schedule (WorkManager keeps it across restarts; UPDATE is idempotent).
        appScope.launch { DigestWorker.schedule(this@LogbApp, notificationPrefs.current()) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncManager.requestSync(SyncReason.Foreground)
            }
        })
    }
}
