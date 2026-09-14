package dev.logb.android.feature.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.statsDataStore: DataStore<Preferences> by preferencesDataStore(name = "stats")

/** Per device and not synced, as on the web: whether to count purchase prices, or an object's contents, is a way of looking, not data. */
@Singleton
class StatsPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val purchases = booleanPreferencesKey("include_purchases")
    private val contents = booleanPreferencesKey("include_contents")

    val includePurchases: Flow<Boolean> = context.statsDataStore.data.map { it[purchases] ?: false }
    val includeContents: Flow<Boolean> = context.statsDataStore.data.map { it[contents] ?: false }

    suspend fun setIncludePurchases(on: Boolean) { context.statsDataStore.edit { it[purchases] = on } }
    suspend fun setIncludeContents(on: Boolean) { context.statsDataStore.edit { it[contents] = on } }
}
