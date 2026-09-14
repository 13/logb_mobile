package dev.logb.android.core.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Off until the person turns it on: a notification nobody asked for is the first thing people disable. */
data class NotificationSettings(val enabled: Boolean = false, val hour: Int = 8, val minute: Int = 0) {
    val time: String get() = "%02d:%02d".format(hour, minute)
}

private val Context.notifyDataStore: DataStore<Preferences> by preferencesDataStore(name = "notifications")

@Singleton
class NotificationPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val enabled = booleanPreferencesKey("enabled")
    private val hour = intPreferencesKey("hour")
    private val minute = intPreferencesKey("minute")

    val settings: Flow<NotificationSettings> = context.notifyDataStore.data.map { p ->
        NotificationSettings(p[enabled] ?: false, p[hour] ?: 8, p[minute] ?: 0)
    }

    suspend fun current(): NotificationSettings = settings.first()
    suspend fun setEnabled(on: Boolean) { context.notifyDataStore.edit { it[enabled] = on } }
    suspend fun setTime(h: Int, m: Int) { context.notifyDataStore.edit { it[hour] = h; it[minute] = m } }
}
