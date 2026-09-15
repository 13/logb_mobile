package dev.logb.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.LockPolicy
import dev.logb.android.core.auth.LockPrefs
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.entity.SyncStateEntity
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
class RootViewModel @Inject constructor(private val sessions: SessionRepository, private val accounts: ActiveAccount, prefs: AppearancePrefs, private val lockPrefs: LockPrefs, private val capabilities: dev.logb.android.core.server.ServerCapabilities, private val updateAutoCheck: dev.logb.android.feature.update.UpdateAutoCheck) : ViewModel() {
    val session: StateFlow<Session> = sessions.session
    val appearance: StateFlow<Appearance> = prefs.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

    /**
     * Whether the lock is on, kept in memory from the moment this `ViewModel` exists rather than
     * read fresh (async) on every resume: [onForeground] needs an answer *before* anything async
     * can run, so a stale "unlocked" belief never gets one extra frame on screen. Null only until
     * [LockPrefs]'s first read lands.
     */
    private val lockEnabled: StateFlow<Boolean?> = lockPrefs.isEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the lock screen covers the logbook. A cold start with the lock on begins locked;
     * afterwards it re-engages when the app was in the background longer than [LockPolicy.GRACE_MS].
     * Null while the preference has not been read, so nothing flashes before the answer is known.
     * Nothing ever sets this to `false` except a successful [unlock], or the async settle below
     * finding the lock genuinely off or the grace period not yet elapsed.
     */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked
    private var backgroundedAt: Long? = null

    /**
     * Resuming to foreground -- from Recents, the launcher icon, a notification tap, a share-in,
     * a picker round trip, anything. The logbook must not stay on screen a moment past the grace
     * period while the real answer is fetched, so the elapsed-time decision is made synchronously
     * here, first, off the in-memory [lockEnabled] (never blocking on [LockPrefs]'s DataStore):
     * only when currently believed unlocked *and* [LockPolicy.shouldLock] says that belief cannot
     * be trusted does this drop straight to "checking" (null), which shows nothing. "Not yet
     * known" (`lockEnabled.value == null`) is passed through as `true` -- assume it might be on --
     * never as `false`. The async read afterwards is unchanged: it settles the real answer, and is
     * still what actually ever sets `false` (besides [unlock]) once the setting is confirmed off
     * or the grace period is confirmed not yet elapsed.
     */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (_locked.value == false && LockPolicy.shouldLock(lockEnabled.value ?: true, backgroundedAt, now)) _locked.value = null
        viewModelScope.launch {
            val enabled = lockPrefs.current()
            if (_locked.value != true) _locked.value = LockPolicy.shouldLock(enabled, backgroundedAt, System.currentTimeMillis())
            backgroundedAt = null
        }
    }

    fun onBackground() { backgroundedAt = System.currentTimeMillis() }

    fun unlock() { _locked.value = false }

    /**
     * Null while unknown; true only until this account's mirror has completed its *first*
     * bootstrap ever. A later background re-bootstrap -- an import
     * ([dev.logb.android.feature.settings.data.DataViewModel.confirmImport]), a placeholder
     * [dev.logb.android.core.sync.ChangeApplier] has to heal, the 0.8.0 migration -- sets
     * [SyncStateEntity.bootstrapNeeded] again to bring the mirror back in step, but that must run
     * quietly behind the normal UI, not show this full-screen first-run state a second time.
     * [SyncStateEntity.epoch] is written only once a bootstrap actually lands
     * ([dev.logb.android.core.sync.Bootstrap.apply]) and nothing else ever clears it, so it being
     * null is the one reliable "never happened" signal -- unlike the mutable `bootstrap_needed`
     * flag itself, which [dev.logb.android.core.sync.PullEngine] keeps honouring for the re-fetch.
     */
    val showFirstRunBootstrap: StateFlow<Boolean?> = sessions.session
        .flatMapLatest { s ->
            if (s is Session.SignedIn) accounts.db.syncStateDao().observe().map { firstRunPending(it) } else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { sessions.restore(); capabilities.load() }
        // Independent of the restore above, so a slow GitHub never delays it.
        viewModelScope.launch { updateAutoCheck.runIfDue() }
    }

    fun signOut() = viewModelScope.launch { sessions.signOut() }

    companion object {
        fun firstRunPending(state: SyncStateEntity?): Boolean = state == null || state.epoch == null
    }
}
