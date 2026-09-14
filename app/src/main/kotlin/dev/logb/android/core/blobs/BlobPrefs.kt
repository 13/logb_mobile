package dev.logb.android.core.blobs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class BlobSettings(val budgetBytes: Long = DEFAULT_BUDGET, val originalsUnmeteredOnly: Boolean = true) {
    companion object { const val DEFAULT_BUDGET: Long = 4L * 1024 * 1024 * 1024 }
}

private val Context.blobDataStore: DataStore<Preferences> by preferencesDataStore(name = "blobs")

/** How much of the originals to keep, and on which connections to fetch them. Thumbnails are not a choice. */
@Singleton
class BlobPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val budget = longPreferencesKey("budget_bytes")
    private val unmetered = booleanPreferencesKey("originals_unmetered_only")

    val settings: Flow<BlobSettings> = context.blobDataStore.data.map { p ->
        BlobSettings(p[budget] ?: BlobSettings.DEFAULT_BUDGET, p[unmetered] ?: true)
    }

    suspend fun current(): BlobSettings = settings.first()
    suspend fun setBudget(bytes: Long) { context.blobDataStore.edit { it[budget] = bytes } }
    suspend fun setOriginalsUnmeteredOnly(on: Boolean) { context.blobDataStore.edit { it[unmetered] = on } }
}
