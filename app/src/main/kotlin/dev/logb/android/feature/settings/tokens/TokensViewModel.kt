package dev.logb.android.feature.settings.tokens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.PasswordSession
import dev.logb.android.core.auth.ServerStore
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.auth.TokenStore
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.UnauthorizedException
import dev.logb.android.core.network.dto.ApiToken
import dev.logb.android.core.network.dto.NewToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class TokenRow(val token: ApiToken, val isThisPhone: Boolean, val canRevoke: Boolean)

object TokenRows {
    /**
     * How much of the plaintext the server keeps alongside the hash (`token_prefix` in
     * `src/auth.rs`): `TOKEN_PREFIX.len()` (9, for `"logb_pat_"`) + 6.
     */
    const val SERVER_PREFIX_KEPT: Int = 15

    /**
     * The phone recognises its own row two ways: the token id `ServerStore` remembers, and --
     * when that is unknown -- the prefix computed from the phone's own decrypted token (never
     * the plaintext itself, see [TokensViewModel.load]). When *neither* is known, no row can be
     * trusted as "this phone", so every row's `canRevoke` is false rather than risk revoking the
     * token the app is using to ask the question.
     */
    fun of(tokens: List<ApiToken>, phoneTokenId: Long?, phonePrefix: String?): List<TokenRow> {
        val identifiable = phoneTokenId != null || phonePrefix != null
        return tokens.map { t ->
            val isThisPhone = t.id == phoneTokenId || (phonePrefix != null && t.prefix == phonePrefix)
            TokenRow(t, isThisPhone, canRevoke = identifiable && !isThisPhone)
        }
    }

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
    /** Same idea as [offline], but scoped to the password dialog: a connection failure mid-[confirm]
     * shows the "needs a connection" text there instead of the raw exception message. */
    val askOffline: Boolean = false,
)

@HiltViewModel
class TokensViewModel @Inject constructor(
    private val accounts: ActiveAccount,
    private val serverStore: ServerStore,
    private val tokenStore: TokenStore,
    private val sessions: SessionRepository,
    private val passwordSession: PasswordSession,
) : ViewModel() {
    private val _state = MutableStateFlow(TokensUiState())
    val state: StateFlow<TokensUiState> = _state.asStateFlow()

    init { load() }

    /**
     * Mirrors `SyncManager.syncNow()`'s three-way split: a 401 means this phone's own token was
     * revoked elsewhere, so the session is asked to sign out; any other server-shaped failure is
     * an [ApiException] whose message the screen can show; anything else is a real connectivity
     * failure. `UnauthorizedException` is checked first because it *is* an `ApiException`, which
     * *is* an `IOException`.
     */
    fun load() = viewModelScope.launch {
        try {
            // The plaintext is read once, held only in this local `val`, and never leaves this
            // function: only its prefix -- the same few characters the server itself keeps --
            // is passed on to `TokenRows.of`.
            val phonePrefix = tokenStore.read()?.take(TokenRows.SERVER_PREFIX_KEPT)
            val rows = TokenRows.of(accounts.api.listTokens(), serverStore.read()?.tokenId, phonePrefix)
            _state.update { it.copy(rows = rows, loaded = true, offline = false, error = null) }
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(loaded = true, offline = false, error = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(loaded = true, offline = false, error = e.message) }
        } catch (e: IOException) {
            _state.update { it.copy(loaded = true, offline = true, error = null) }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v) }

    fun askCreate() { TokenRows.validName(_state.value.name)?.let { n -> _state.update { it.copy(asking = PasswordAction.Create(n), error = null, askOffline = false) } } }

    fun askRevoke(token: ApiToken) = _state.update { it.copy(asking = PasswordAction.Revoke(token), error = null, askOffline = false) }

    fun dismiss() = _state.update { it.copy(asking = null) }

    /** A fast double tap must not run the block twice: `busy` is read and set in the same atomic update. */
    fun confirm(password: String) = viewModelScope.launch {
        var action: PasswordAction? = null
        _state.update { s ->
            val asking = s.asking
            if (s.busy || asking == null) return@update s
            action = asking
            s.copy(busy = true, error = null, askOffline = false)
        }
        val confirmed = action ?: return@launch
        val result = passwordSession.run(password) { api ->
            when (confirmed) {
                is PasswordAction.Create -> api.createToken(NewToken(confirmed.name)).token
                is PasswordAction.Revoke -> { api.revokeToken(confirmed.token.id); null }
            }
        }
        result.onSuccess { plaintext ->
            _state.update { it.copy(busy = false, asking = null, fresh = plaintext ?: it.fresh, name = if (plaintext != null) "" else it.name) }
            load()
        }.onFailure { e ->
            // A server-shaped failure (wrong password, an ApiException) keeps its own message; a
            // real connectivity failure -- the login itself never reached the server -- shows the
            // same offline outcome the rest of the screen uses, not the raw exception text.
            if (e is IOException && e !is ApiException) {
                _state.update { it.copy(busy = false, askOffline = true) }
            } else {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
}
