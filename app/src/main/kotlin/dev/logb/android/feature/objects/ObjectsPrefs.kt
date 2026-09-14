package dev.logb.android.feature.objects

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.objectsDataStore: DataStore<Preferences> by preferencesDataStore(name = "objects")

/** The remembered sort of the Objects list: per device, like the web's `logb.objects.sort`. */
@Singleton
class ObjectsPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val sortKey = stringPreferencesKey("sort")

    val sort: Flow<SortKey> = context.objectsDataStore.data.map { SortKey.parse(it[sortKey]) ?: SortKey.Name }

    suspend fun setSort(key: SortKey) { context.objectsDataStore.edit { it[sortKey] = key.key } }
}
