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

    /** Thumbnails come from the server with the bearer token, through the account's own client. Phase 3 adds the blob store in front. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { accounts.httpClient ?: OkHttpClient() })) }
            .crossfade(true)
            .build()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncManager.requestSync(SyncReason.Foreground)
            }
        })
    }
}
