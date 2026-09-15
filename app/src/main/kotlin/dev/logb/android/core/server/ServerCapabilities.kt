package dev.logb.android.core.server

import dev.logb.android.core.auth.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    suspend fun load() = set(serverStore.read()?.serverVersion)

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
        set(fetched)
        val after = Capabilities.of(fetched)
        return (after.tags && !before.tags) || (after.ownTypes && !before.ownTypes)
    }

    private fun set(version: String?) {
        _version.value = version
        _current.value = Capabilities.of(version)
    }
}
