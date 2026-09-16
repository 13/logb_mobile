package dev.logb.android.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.DeviceName
import dev.logb.android.core.auth.PairError
import dev.logb.android.core.auth.PairingLinks
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.auth.classifyPairingError
import dev.logb.android.core.server.ServerCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerUiState(
    val url: String = "",
    val checking: Boolean = false,
    val error: String? = null,
    /** Redeeming a scanned code; see [ServerViewModel.onScanned]. Independent of [checking]: the two never run at once, but nothing enforces that beyond the UI disabling both controls while either is true. */
    val pairing: Boolean = false,
    val pairError: PairError? = null,
)

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val capabilities: ServerCapabilities,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ServerUiState(url = (sessions.session.value as? Session.SignedOut)?.serverUrl ?: ""),
    )
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    fun onUrlChange(url: String) = _state.update { it.copy(url = url, error = null) }

    fun submit() {
        val url = _state.value.url.trim()
        if (url.isBlank() || _state.value.checking) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val result = sessions.checkServer(url)
            _state.update { it.copy(checking = false, error = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }) }
        }
    }

    /**
     * A code just scanned with the in-app "Scan QR code" button -- the person tapped it
     * themselves, on this, the signed-out server screen, so unlike a `logb://pair` deep link
     * ([dev.logb.android.feature.pairing.PairingConfirmViewModel]) it needs no extra confirmation.
     */
    fun onScanned(raw: String) {
        if (_state.value.pairing) return
        val link = PairingLinks.parse(raw)
        if (link == null) {
            _state.update { it.copy(pairError = PairError.NotACode) }
            return
        }
        _state.update { it.copy(pairing = true, pairError = null) }
        viewModelScope.launch {
            val result = sessions.signInWithPairing(link, DeviceName.current())
            if (result.isSuccess) {
                capabilities.load()
                _state.update { it.copy(pairing = false) }
            } else {
                _state.update { it.copy(pairing = false, pairError = classifyPairingError(result.exceptionOrNull())) }
            }
        }
    }
}

data class SignInUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(private val sessions: SessionRepository, private val capabilities: dev.logb.android.core.server.ServerCapabilities) : ViewModel() {
    private val _state = MutableStateFlow(
        (sessions.session.value as? Session.SignedOut).let { s ->
            SignInUiState(serverUrl = s?.serverUrl ?: "", username = s?.username ?: "", error = s?.reason?.let { "unauthorized" })
        },
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    init {
        // The Activity has no NavHost, so this ViewModel is not recreated when the top-level
        // screen flips between sign-in and change-server -- collect the session so a later
        // server change (sign out, then a different address) reaches an already-built instance.
        viewModelScope.launch {
            sessions.session.collect { s ->
                // A fresh SignInUiState, not a copy: a session moving to a different SignedOut
                // server is a clean screen, not a continuation of whatever was in flight for the
                // old one (busy/password from a stale submit() must not carry over).
                if (s is Session.SignedOut) _state.update { SignInUiState(serverUrl = s.serverUrl, username = s.username ?: "", error = s.reason?.let { "unauthorized" }) }
            }
        }
    }

    fun onUsernameChange(v: String) = _state.update { it.copy(username = v, error = null) }

    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }

    fun changeServer() = viewModelScope.launch { sessions.forgetServer() }

    fun submit() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank() || s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = sessions.signIn(s.serverUrl, s.username.trim(), s.password)
            if (result.isSuccess) capabilities.load()
            // The server may have changed (sign-in against A, then switched to B) while this
            // request was in flight: A's outcome must not touch B's screen.
            if (_state.value.serverUrl == s.serverUrl) {
                _state.update { it.copy(busy = false, password = if (result.isSuccess) "" else it.password, error = result.exceptionOrNull()?.message) }
            }
        }
    }
}
