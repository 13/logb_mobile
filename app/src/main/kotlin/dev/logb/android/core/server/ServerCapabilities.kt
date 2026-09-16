package dev.logb.android.core.server

import dev.logb.android.core.auth.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which features the signed-in server supports. Persisted in [ServerStore] so the app knows
 * offline; refreshed at the start of every sync run.
 */
@Singleton
class ServerCapabilities @Inject constructor(private val serverStore: ServerStore) {
    private val _version = MutableStateFlow<String?>(null)
    val version: StateFlow<String?> = _version.asStateFlow()
    private val _current = MutableStateFlow(Capabilities.NONE)
    val current: StateFlow<Capabilities> = _current.asStateFlow()

    /**
     * URL of the server whose version [refresh] most recently wrote, if any. Guards against a
     * slower, concurrent [load] for that same server clobbering it with a stale read. The guard
     * is per-server, not sticky: signing into a different server makes [load] read the store
     * again, so a stale value from a previous server can never linger.
     */
    private val refreshedUrl = AtomicReference<String?>(null)

    /** What's persisted, unless a concurrent or earlier [refresh] for this same server already produced a fresher value. */
    suspend fun load() {
        val record = serverStore.read()
        if (record != null && record.serverUrl == refreshedUrl.get()) return
        set(record?.serverVersion)
    }

    /**
     * Asks the server for its version and stores it. The result tells whether tags or own types
     * became available with this call.
     */
    suspend fun refresh(fetchVersion: suspend () -> String?): Boolean {
        val record = serverStore.read() ?: return false
        val url = record.serverUrl
        val fetched = fetchVersion() ?: return false
        val stillSameServer = serverStore.read()?.serverUrl == url
        if (!stillSameServer) return false
        val before = Capabilities.of(record.serverVersion)
        if (fetched != record.serverVersion) serverStore.setVersion(url, fetched)
        refreshedUrl.set(url)
        set(fetched)
        val after = Capabilities.of(fetched)
        return (after.tags && !before.tags) || (after.ownTypes && !before.ownTypes)
    }

    private fun set(version: String?) {
        _version.value = version
        _current.value = Capabilities.of(version)
    }
}
