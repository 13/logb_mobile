package dev.logb.android.feature.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Whether to check automatically, when it last did, and the newer version it found (if any). */
data class UpdateSettings(val autoCheck: Boolean = true, val lastCheckedAt: Long = 0L, val available: String? = null)

interface UpdatePrefsStore {
    val settings: Flow<UpdateSettings>
    suspend fun current(): UpdateSettings
    suspend fun setAutoCheck(enabled: Boolean)
    suspend fun recordCheck(at: Long, available: String?)
}

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update")

@Singleton
class UpdatePrefs @Inject constructor(@ApplicationContext private val context: Context) : UpdatePrefsStore {
    private val autoCheck = booleanPreferencesKey("auto_check")
    private val lastCheckedAt = longPreferencesKey("last_checked_at")
    private val available = stringPreferencesKey("available")

    override val settings: Flow<UpdateSettings> = context.updateDataStore.data.map { p ->
        UpdateSettings(p[autoCheck] ?: true, p[lastCheckedAt] ?: 0L, p[available])
    }

    override suspend fun current(): UpdateSettings = settings.first()

    override suspend fun setAutoCheck(enabled: Boolean) { context.updateDataStore.edit { it[autoCheck] = enabled } }

    override suspend fun recordCheck(at: Long, available: String?) {
        context.updateDataStore.edit { p ->
            p[lastCheckedAt] = at
            if (available != null) p[this.available] = available else p.remove(this.available)
        }
    }
}

/** Records a completed check (what it found, and when); a failed one is not recorded. True when recorded. */
suspend fun UpdatePrefsStore.record(result: UpdateCheck, at: Long): Boolean = when (result) {
    is UpdateCheck.Available -> { recordCheck(at, result.version.toString()); true }
    UpdateCheck.UpToDate -> { recordCheck(at, null); true }
    is UpdateCheck.Failed -> false
}
