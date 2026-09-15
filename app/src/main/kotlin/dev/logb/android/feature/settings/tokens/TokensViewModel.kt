package dev.logb.android.feature.settings.tokens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.PasswordSession
import dev.logb.android.core.auth.ServerStore
import dev.logb.android.core.network.dto.ApiToken
import dev.logb.android.core.network.dto.NewToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TokenRow(val token: ApiToken, val isThisPhone: Boolean)

object TokenRows {
    fun of(tokens: List<ApiToken>, phoneTokenId: Long?): List<TokenRow> = tokens.map { TokenRow(it, it.id == phoneTokenId) }

    fun validName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= 64 }
}

sealed interface PasswordAction {
    data class Create(val name: String) : PasswordAction
    data class Revoke(val token: ApiToken) : PasswordAction
}

data class TokensUiState(
    val rows: List<TokenRow> = emptyList(),
    val loaded: Boolean = false,
    val offline: Boolean = false,
    val name: String = "",
    /** The plaintext of the token just created: memory only, gone when the screen is. */
    val fresh: String? = null,
    val asking: PasswordAction? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TokensViewModel @Inject constructor(
    private val accounts: ActiveAccount,
    private val serverStore: ServerStore,
    private val passwordSession: PasswordSession,
) : ViewModel() {
    private val _state = MutableStateFlow(TokensUiState())
    val state: StateFlow<TokensUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        runCatching { TokenRows.of(accounts.api.listTokens(), serverStore.read()?.tokenId) }
            .onSuccess { rows -> _state.update { it.copy(rows = rows, loaded = true, offline = false, error = null) } }
            .onFailure { e -> _state.update { it.copy(loaded = true, offline = e is java.io.IOException, error = e.message.takeUnless { e is java.io.IOException }) } }
    }

    fun onName(v: String) = _state.update { it.copy(name = v) }

    fun askCreate() { TokenRows.validName(_state.value.name)?.let { n -> _state.update { it.copy(asking = PasswordAction.Create(n), error = null) } } }

    fun askRevoke(token: ApiToken) = _state.update { it.copy(asking = PasswordAction.Revoke(token), error = null) }

    fun dismiss() = _state.update { it.copy(asking = null) }

    fun confirm(password: String) = viewModelScope.launch {
        val action = _state.value.asking ?: return@launch
        _state.update { it.copy(busy = true, error = null) }
        val result = passwordSession.run(password) { api ->
            when (action) {
                is PasswordAction.Create -> api.createToken(NewToken(action.name)).token
                is PasswordAction.Revoke -> { api.revokeToken(action.token.id); null }
            }
        }
        result.onSuccess { plaintext ->
            _state.update { it.copy(busy = false, asking = null, fresh = plaintext ?: it.fresh, name = if (plaintext != null) "" else it.name) }
            load()
        }.onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
    }
}
