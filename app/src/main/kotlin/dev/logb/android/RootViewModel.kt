package dev.logb.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.prefs.Appearance
import dev.logb.android.core.prefs.AppearancePrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restores the session once at start-up and exposes it, plus whether the mirror still needs its first snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RootViewModel @Inject constructor(private val sessions: SessionRepository, private val accounts: ActiveAccount, prefs: AppearancePrefs) : ViewModel() {
    val session: StateFlow<Session> = sessions.session
    val appearance: StateFlow<Appearance> = prefs.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

    /** Null while unknown; true until the first bootstrap has landed for this account. */
    val bootstrapNeeded: StateFlow<Boolean?> = sessions.session
        .flatMapLatest { s ->
            if (s is Session.SignedIn) accounts.db.syncStateDao().observe().map { it?.bootstrapNeeded ?: true } else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { sessions.restore() }
    }

    fun signOut() = viewModelScope.launch { sessions.signOut() }
}
