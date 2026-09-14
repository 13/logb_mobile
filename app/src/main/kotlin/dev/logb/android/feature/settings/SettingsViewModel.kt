package dev.logb.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.BuildConfig
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.entity.SyncStateEntity
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
    ) { session, appearance, sync, syncState -> SettingsUiState(session, appearance, sync, syncState) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { prefs.setDynamicColor(on) }

    fun syncNow() = viewModelScope.launch { syncManager.syncNow() }

    fun signOut(removeLocalData: Boolean) = viewModelScope.launch {
        val s = sessions.session.value as? Session.SignedIn
        sessions.signOut()
        if (removeLocalData && s != null) accounts.deleteLocalData(s.serverUrl, s.user.id)
    }
}
