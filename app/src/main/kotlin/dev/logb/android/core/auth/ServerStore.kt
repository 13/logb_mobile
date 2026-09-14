package dev.logb.android.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
)

interface ServerStore {
    suspend fun read(): ServerRecord?
    suspend fun write(record: ServerRecord)
    suspend fun clear()
}

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server")

@Singleton
class DataStoreServerStore @Inject constructor(@ApplicationContext private val context: Context) : ServerStore {
    private val url = stringPreferencesKey("server_url")
    private val userId = longPreferencesKey("user_id")
    private val username = stringPreferencesKey("username")
    private val tokenId = longPreferencesKey("token_id")
    private val currency = stringPreferencesKey("currency")

    override suspend fun read(): ServerRecord? {
        val p = context.serverDataStore.data.first()
        val server = p[url] ?: return null
        return ServerRecord(server, p[userId], p[username], p[tokenId], p[currency] ?: "EUR")
    }

    override suspend fun write(record: ServerRecord) {
        context.serverDataStore.edit { p ->
            p[url] = record.serverUrl
            record.userId?.let { p[userId] = it } ?: p.remove(userId)
            record.username?.let { p[username] = it } ?: p.remove(username)
            record.tokenId?.let { p[tokenId] = it } ?: p.remove(tokenId)
            p[currency] = record.currency
        }
    }

    override suspend fun clear() {
        context.serverDataStore.edit { it.clear() }
    }
}
