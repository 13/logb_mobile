package dev.logb.android.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.logb.android.core.widget.NoopWidgetRefresher
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lockDataStore: DataStore<Preferences> by preferencesDataStore(name = "lock")

/** Whether the app asks for a biometric or the screen lock before showing the logbook. Off by default. */
@Singleton
class LockPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
    private val widgetRefresher: WidgetRefresher = NoopWidgetRefresher,
) {
    private val enabled = booleanPreferencesKey("enabled")

    val isEnabled: Flow<Boolean> = context.lockDataStore.data.map { it[enabled] ?: false }

    suspend fun current(): Boolean = isEnabled.first()

    /**
     * Persisted first, then a placed widget is told at once: it must never keep showing object
     * names and titles after the lock goes on, nor lag behind it going off.
     */
    suspend fun setEnabled(on: Boolean) {
        context.lockDataStore.edit { it[enabled] = on }
        widgetRefresher.requestImmediateRefresh()
    }
}
