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
     * Asks the server for its version and stores it. True when tags or own types became
     * available with this call: rows the server already had carry those fields, and only a
     * bootstrap brings them into the mirror. Pairing needs no data, so it never asks for one.
     */
    suspend fun refresh(fetchVersion: suspend () -> String?): Boolean {
        val record = serverStore.read() ?: return false
        val fetched = fetchVersion() ?: return false
        val before = Capabilities.of(record.serverVersion)
        if (fetched != record.serverVersion) serverStore.write(record.copy(serverVersion = fetched))
        set(fetched)
        val after = Capabilities.of(fetched)
        return (after.tags && !before.tags) || (after.ownTypes && !before.ownTypes)
    }

    private fun set(version: String?) {
        _version.value = version
        _current.value = Capabilities.of(version)
    }
}
