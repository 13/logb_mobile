package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.sync.Connectivity
import dev.logb.android.core.sync.ConnectivityMonitor
import dev.logb.android.core.sync.PullEngine
import dev.logb.android.core.sync.PushEngine
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncRunner
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

    @Provides
    @Singleton
    fun syncManager(sessions: SessionRepository, connectivity: ConnectivityMonitor, accounts: ActiveAccount, scope: CoroutineScope): SyncManager =
        SyncManager(
            sessions = sessions,
            connectivity = connectivity,
            runnerFactory = {
                accounts.signedIn?.let {
                    SyncRunner {
                        val db = accounts.db
                        val deviceId = db.syncStateDao().get()?.deviceId ?: UUID.randomUUID().toString()
                        PushEngine(db, accounts.api).run()
                        PullEngine(db, accounts.api, deviceId).run()
                    }
                }
            },
            scope = scope,
            pendingCount = { accounts.signedIn?.let { accounts.db.opDao().pending().size } ?: 0 },
        )
}
