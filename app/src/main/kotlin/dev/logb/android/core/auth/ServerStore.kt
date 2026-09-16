package dev.logb.android.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What the app remembers about where it signs in. Nothing secret lives here; the token has its own store. */
data class ServerRecord(
    val serverUrl: String,
    val userId: Long? = null,
    val username: String? = null,
    val tokenId: Long? = null,
    val currency: String = "EUR",
    /** The last version `/api/health` reported; decides which version-gated features the app shows. */
    val serverVersion: String? = null,
    /** The last `features` list `/api/health` reported, e.g. `["pairing"]`; see [dev.logb.android.core.server.Capabilities]. */
    val features: List<String> = emptyList(),
)

interface ServerStore {
    suspend fun read(): ServerRecord?
    suspend fun write(record: ServerRecord)
    suspend fun clear()

    /** Writes only the version and features, and only while the stored server is still [serverUrl]. */
    suspend fun setVersion(serverUrl: String, version: String?, features: List<String>)
}

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server")

@Singleton
class DataStoreServerStore @Inject constructor(@ApplicationContext private val context: Context) : ServerStore {
    private val url = stringPreferencesKey("server_url")
    private val userId = longPreferencesKey("user_id")
    private val username = stringPreferencesKey("username")
    private val tokenId = longPreferencesKey("token_id")
    private val currency = stringPreferencesKey("currency")
    private val serverVersion = stringPreferencesKey("server_version")
    private val features = stringSetPreferencesKey("features")

    override suspend fun read(): ServerRecord? {
        val p = context.serverDataStore.data.first()
        val server = p[url] ?: return null
        return ServerRecord(server, p[userId], p[username], p[tokenId], p[currency] ?: "EUR", p[serverVersion], p[features]?.toList() ?: emptyList())
    }

    override suspend fun write(record: ServerRecord) {
        context.serverDataStore.edit { p ->
            p[url] = record.serverUrl
            record.userId?.let { p[userId] = it } ?: p.remove(userId)
            record.username?.let { p[username] = it } ?: p.remove(username)
            record.tokenId?.let { p[tokenId] = it } ?: p.remove(tokenId)
            p[currency] = record.currency
            record.serverVersion?.let { p[serverVersion] = it } ?: p.remove(serverVersion)
            if (record.features.isNotEmpty()) p[features] = record.features.toSet() else p.remove(features)
        }
    }

    override suspend fun clear() {
        context.serverDataStore.edit { it.clear() }
    }

    override suspend fun setVersion(serverUrl: String, version: String?, features: List<String>) {
        context.serverDataStore.edit { p ->
            if (p[url] == serverUrl) {
                version?.let { p[serverVersion] = it } ?: p.remove(serverVersion)
                if (features.isNotEmpty()) p[this.features] = features.toSet() else p.remove(this.features)
            }
        }
    }
}
