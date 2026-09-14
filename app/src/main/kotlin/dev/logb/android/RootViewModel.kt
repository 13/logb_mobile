package dev.logb.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.LockPolicy
import dev.logb.android.core.auth.LockPrefs
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.prefs.Appearance
import dev.logb.android.core.prefs.AppearancePrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class RootViewModel @Inject constructor(private val sessions: SessionRepository, private val accounts: ActiveAccount, prefs: AppearancePrefs, private val lockPrefs: LockPrefs, private val capabilities: dev.logb.android.core.server.ServerCapabilities) : ViewModel() {
    val session: StateFlow<Session> = sessions.session
    val appearance: StateFlow<Appearance> = prefs.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

    /**
     * Whether the lock screen covers the logbook. A cold start with the lock on begins locked;
     * afterwards it re-engages when the app was in the background longer than [LockPolicy.GRACE_MS].
     * Null while the preference has not been read, so nothing flashes before the answer is known.
     */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked
    private var backgroundedAt: Long? = null

    fun onForeground() = viewModelScope.launch {
        val enabled = lockPrefs.current()
        if (_locked.value != true) _locked.value = LockPolicy.shouldLock(enabled, backgroundedAt, System.currentTimeMillis())
        backgroundedAt = null
    }

    fun onBackground() { backgroundedAt = System.currentTimeMillis() }

    fun unlock() { _locked.value = false }

    /** Null while unknown; true until the first bootstrap has landed for this account. */
    val bootstrapNeeded: StateFlow<Boolean?> = sessions.session
        .flatMapLatest { s ->
            if (s is Session.SignedIn) accounts.db.syncStateDao().observe().map { it?.bootstrapNeeded ?: true } else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { sessions.restore(); capabilities.load() }
    }

    fun signOut() = viewModelScope.launch { sessions.signOut() }
}
