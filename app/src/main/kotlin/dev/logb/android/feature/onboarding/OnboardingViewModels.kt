package dev.logb.android.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerUiState(val url: String = "", val checking: Boolean = false, val error: String? = null)

@HiltViewModel
class ServerViewModel @Inject constructor(private val sessions: SessionRepository) : ViewModel() {
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
            _state.update { it.copy(busy = false, password = if (result.isSuccess) "" else it.password, error = result.exceptionOrNull()?.message) }
        }
    }
}
