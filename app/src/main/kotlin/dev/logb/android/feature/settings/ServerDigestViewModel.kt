package dev.logb.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.domain.Webhook
import dev.logb.android.core.domain.WebhookCheck
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.UnauthorizedException
import dev.logb.android.core.network.dto.NotificationTest
import dev.logb.android.core.network.dto.ServerNotificationsIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class ServerDigestUiState(
    val loaded: Boolean = false,
    val offline: Boolean = false,
    val url: String = "",
    val format: String = "text",
    val hour: Int = 8,
    val instanceWebhook: Boolean = false,
    val invalidUrl: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val test: NotificationTest? = null,
    val error: String? = null,
)

/** Settings › Notifications, the server half: the webhook digest `LogbApi.notifications` keeps. */
@HiltViewModel
class ServerDigestViewModel @Inject constructor(
    private val accounts: ActiveAccount,
    private val sessions: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerDigestUiState())
    val state: StateFlow<ServerDigestUiState> = _state.asStateFlow()

    init { load() }

    /** Same three-way split as `TokensViewModel.load()`: a 401 signs out, another `ApiException` keeps its message, anything else is offline. */
    fun load() = viewModelScope.launch {
        try {
            val n = accounts.api.notifications()
            _state.update {
                it.copy(loaded = true, offline = false, error = null, url = n.url.orEmpty(), format = n.format, hour = n.hour, instanceWebhook = n.instanceWebhook)
            }
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(loaded = true, offline = false, error = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(loaded = true, offline = false, error = e.message) }
        } catch (e: IOException) {
            _state.update { it.copy(loaded = true, offline = true, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything not shaped like a server or connectivity failure -- a malformed response
            // body, say -- still has to land somewhere rather than crash the screen.
            _state.update { it.copy(loaded = true, offline = false, error = e.message) }
        }
    }

    fun onUrl(v: String) = _state.update { it.copy(url = v, invalidUrl = false, saved = false) }

    fun onFormat(v: String) = _state.update { it.copy(format = v, saved = false) }

    /** A fast double tap must not send two saves: `busy` is read and set in the same atomic update. */
    fun save() = viewModelScope.launch {
        val check = Webhook.normalize(_state.value.url)
        if (check is WebhookCheck.Invalid) {
            _state.update { it.copy(invalidUrl = true) }
            return@launch
        }
        var proceed = false
        _state.update { s -> if (s.busy) return@update s; proceed = true; s.copy(busy = true, offline = false, error = null, saved = false, test = null) }
        if (!proceed) return@launch
        val url = (check as? WebhookCheck.Valid)?.url
        try {
            val n = accounts.api.saveNotifications(ServerNotificationsIn(url, _state.value.format))
            _state.update {
                it.copy(busy = false, saved = true, url = n.url.orEmpty(), format = n.format, hour = n.hour, instanceWebhook = n.instanceWebhook)
            }
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(busy = false) }
        } catch (e: ApiException) {
            _state.update { it.copy(busy = false, error = e.message) }
        } catch (e: IOException) {
            _state.update { it.copy(busy = false, offline = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }

    /** Same atomic busy guard as [save]: a fast double tap on "Send a test notification" must not send two. */
    fun test() = viewModelScope.launch {
        var proceed = false
        _state.update { s -> if (s.busy) return@update s; proceed = true; s.copy(busy = true, offline = false, error = null, saved = false, test = null) }
        if (!proceed) return@launch
        try {
            val t = accounts.api.testNotifications()
            _state.update { it.copy(busy = false, test = t) }
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(busy = false) }
        } catch (e: ApiException) {
            _state.update { it.copy(busy = false, error = e.message) }
        } catch (e: IOException) {
            _state.update { it.copy(busy = false, offline = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }
}
