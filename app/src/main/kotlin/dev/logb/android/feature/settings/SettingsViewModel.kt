package dev.logb.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.BuildConfig
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.db.inTransaction
import dev.logb.android.core.prefs.Appearance
import dev.logb.android.core.prefs.AppearancePrefs
import dev.logb.android.core.prefs.ThemeMode
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val session: Session = Session.Loading,
    val appearance: Appearance = Appearance(),
    val sync: SyncStatus = SyncStatus.None,
    val syncState: SyncStateEntity? = null,
    val deadOps: List<OpEntity> = emptyList(),
    val pending: Int = 0,
    val version: String = BuildConfig.VERSION_NAME,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val prefs: AppearancePrefs,
    private val syncManager: SyncManager,
    private val accounts: ActiveAccount,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        sessions.session, prefs.appearance, syncManager.status,
        sessions.session.flatMapLatest { s -> if (s is Session.SignedIn) accounts.db.syncStateDao().observe() else flowOf(null) },
        sessions.session.flatMapLatest { s -> if (s is Session.SignedIn) combine(accounts.db.opDao().dead(), accounts.db.opDao().pendingCount()) { d, p -> d to p } else flowOf(emptyList<OpEntity>() to 0) },
    ) { session, appearance, sync, syncState, ops -> SettingsUiState(session, appearance, sync, syncState, ops.first, ops.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { prefs.setDynamicColor(on) }

    fun syncNow() = viewModelScope.launch { syncManager.syncNow() }

    fun retry(opId: String) = viewModelScope.launch {
        accounts.db.opDao().revive(opId)
        syncManager.syncNow()
    }

    /**
     * Discarding a failed `set` reverts nothing -- the pull already carries the server's truth
     * for that field. Discarding a failed `create` removes the local row it would have made,
     * since the server refused it and nothing else references it.
     */
    fun discard(op: OpEntity) = viewModelScope.launch {
        val db = accounts.db
        db.inTransaction {
            if (op.kind == "create") {
                when (op.entity) {
                    "object" -> db.objectDao().hardDelete(listOf(op.entityUuid) + db.objectDao().descendantUuids(op.entityUuid))
                    "activity" -> db.activityDao().hardDelete(listOf(op.entityUuid))
                    "reminder" -> db.reminderDao().hardDelete(listOf(op.entityUuid))
                    "attachment" -> db.attachmentDao().hardDelete(listOf(op.entityUuid))
                }
            }
            db.opDao().delete(op.id)
        }
    }

    fun signOut(removeLocalData: Boolean) = viewModelScope.launch {
        val s = sessions.session.value as? Session.SignedIn
        sessions.signOut()
        if (removeLocalData && s != null) accounts.deleteLocalData(s.serverUrl, s.user.id)
    }
}
