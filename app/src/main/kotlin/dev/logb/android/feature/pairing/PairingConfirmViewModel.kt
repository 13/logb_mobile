package dev.logb.android.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.DeviceName
import dev.logb.android.core.auth.PairError
import dev.logb.android.core.auth.PairingLink
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.auth.classifyPairingError
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.feature.share.ShareInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * What a `logb://pair` deep link needs asked before it is ever acted on -- see [PairingConfirmViewModel]'s
 * own doc for why this is unconditional. Only the server's host is shown, never the code.
 */
sealed interface PairingPrompt {
    /** Signed out: nothing to lose, but still not silent -- any app can fire this intent. */
    data class SignIn(val host: String) : PairingPrompt

    /** Already signed in: confirming signs out of [fromHost] first, then pairs with [toHost]. */
    data class Replace(val fromHost: String, val toHost: String) : PairingPrompt
}

data class PairingConfirmUiState(val prompt: PairingPrompt? = null, val busy: Boolean = false, val error: PairError? = null)

/**
 * Guards every `logb://pair` deep link behind a confirmation dialog -- unlike the in-app "Scan QR
 * code" button ([dev.logb.android.feature.onboarding.ServerViewModel.onScanned]), which the person
 * just tapped themselves. A deep link can be fired by any other app or a browser with no user
 * intent behind it at all, so it must never sign this phone in -- or switch its account -- without
 * asking first, whether the phone is signed out or already signed in.
 *
 * Composed only once the app is actually usable (not [dev.logb.android.feature.lock.LockScreen],
 * not the bootstrap screen): see `MainActivity`, which mounts this only alongside the server
 * screen, the sign-in screen, and the signed-in, unlocked nav host. [ShareInbox.pendingPairing]
 * holds a link that arrives earlier -- during the lock screen, say -- until then, exactly the way
 * [ShareInbox.target] already holds a launch target for the nav host.
 */
@HiltViewModel
class PairingConfirmViewModel @Inject constructor(
    private val shareInbox: ShareInbox,
    private val sessions: SessionRepository,
    private val capabilities: ServerCapabilities,
) : ViewModel() {
    private val _state = MutableStateFlow(PairingConfirmUiState())
    val state: StateFlow<PairingConfirmUiState> = _state.asStateFlow()

    private var link: PairingLink? = null

    // Mirrors ShareInbox.pendingPairing into this ViewModel's own state, but never "takes" it
    // (clears it back to null) from inside this collector: MutableStateFlow does not support a
    // collector mutating the very flow it is collecting -- confirm()/cancel() do that instead,
    // once the person actually responds.
    init {
        viewModelScope.launch {
            shareInbox.pendingPairing.collect { pending ->
                if (pending == null) return@collect
                link = pending
                val toHost = hostOf(pending.serverUrl)
                val current = sessions.session.value
                _state.value = if (current is Session.SignedIn) {
                    PairingConfirmUiState(prompt = PairingPrompt.Replace(hostOf(current.serverUrl), toHost))
                } else {
                    PairingConfirmUiState(prompt = PairingPrompt.SignIn(toHost))
                }
            }
        }
    }

    /** Signs out first when already signed in (keeping the mirror -- see [SessionRepository.signOut]), then redeems the code. */
    fun confirm() {
        val current = link ?: return
        shareInbox.takePendingPairing()
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            if (sessions.session.value is Session.SignedIn) sessions.signOut()
            val result = sessions.signInWithPairing(current, DeviceName.current())
            link = null
            _state.value = if (result.isSuccess) {
                capabilities.load()
                PairingConfirmUiState()
            } else {
                // A sign-out that already happened is not undone on failure: the person ends up
                // signed out on the server screen with the error shown, same as any other failed
                // sign-in attempt there -- see the release plan's Task B3 notes.
                PairingConfirmUiState(error = classifyPairingError(result.exceptionOrNull()))
            }
        }
    }

    /** The link is dropped; nothing on the server or this phone changes. */
    fun cancel() {
        link = null
        shareInbox.takePendingPairing()
        _state.value = PairingConfirmUiState()
    }

    fun dismissError() {
        _state.value = PairingConfirmUiState()
    }

    companion object {
        /** Just the host, e.g. `logb.example.org` or `192.168.1.5:8080` -- a default port is dropped, never shown. */
        fun hostOf(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url
            val defaultPort = (parsed.scheme == "https" && parsed.port == 443) || (parsed.scheme == "http" && parsed.port == 80)
            return if (defaultPort) parsed.host else "${parsed.host}:${parsed.port}"
        }
    }
}
