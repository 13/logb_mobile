package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.blobs.BlobDownloader
import dev.logb.android.core.blobs.BlobPrefs
import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.core.sync.Connectivity
import dev.logb.android.core.sync.ConnectivityMonitor
import dev.logb.android.core.sync.PullEngine
import dev.logb.android.core.sync.PushEngine
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncRunner
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun connectivity(impl: Connectivity): ConnectivityMonitor = impl

    /** The downloader for whoever is signed in, for on-demand fetches from the image loader. */
    @Provides
    @Singleton
    fun downloaderProvider(accounts: ActiveAccount, connectivity: ConnectivityMonitor, blobs: BlobStore, blobPrefs: BlobPrefs): () -> BlobDownloader? = {
        accounts.signedIn?.let { BlobDownloader(accounts.db, accounts.api, blobs, connectivity) { blobPrefs.current() } }
    }

    @Provides
    @Singleton
    fun syncManager(
        sessions: SessionRepository,
        connectivity: ConnectivityMonitor,
        accounts: ActiveAccount,
        scope: CoroutineScope,
        blobs: BlobStore,
        blobPrefs: BlobPrefs,
        capabilities: ServerCapabilities,
        widgetRefresher: WidgetRefresher,
    ): SyncManager =
        SyncManager(
            sessions = sessions,
            connectivity = connectivity,
            runnerFactory = {
                accounts.signedIn?.let {
                    SyncRunner {
                        val db = accounts.db
                        // Refreshes the stored version so the app knows what the server supports; the
                        // bootstrap that brings existing tags and own types into the mirror arrives with
                        // the Room v2 migration in release 0.8.0.
                        capabilities.refresh { runCatching { accounts.api.healthInfo().version }.getOrNull()?.takeIf { it.isNotBlank() } }
                        val deviceId = db.syncStateDao().get()?.deviceId ?: UUID.randomUUID().toString()
                        PushEngine(db, accounts.api, blobs, capabilities.current.value).run()
                        PullEngine(db, accounts.api, deviceId).run()
                        BlobDownloader(db, accounts.api, blobs, connectivity) { blobPrefs.current() }.runAfterPull()
                    }
                }
            },
            scope = scope,
            pendingCount = { accounts.signedIn?.let { accounts.db.opDao().pending().size } ?: 0 },
            widgetRefresh = { widgetRefresher.requestRefresh() },
        )
}
