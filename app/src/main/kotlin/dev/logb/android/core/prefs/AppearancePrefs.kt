package dev.logb.android.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

data class Appearance(val theme: ThemeMode = ThemeMode.System, val dynamicColor: Boolean = false)

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

/** Theme and dynamic colour. Language is the system's per-app language setting, not a preference here. */
@Singleton
class AppearancePrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val dynamic = booleanPreferencesKey("dynamic_color")

    val appearance: Flow<Appearance> = context.appearanceDataStore.data.map { p ->
        Appearance(
            theme = p[theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = p[dynamic] ?: false,
        )
    }

    suspend fun setTheme(mode: ThemeMode) { context.appearanceDataStore.edit { it[theme] = mode.name } }

    suspend fun setDynamicColor(on: Boolean) { context.appearanceDataStore.edit { it[dynamic] = on } }
}
