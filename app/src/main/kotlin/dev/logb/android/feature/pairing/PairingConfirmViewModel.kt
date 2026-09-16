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
import dev.logb.android.feature.share.PendingPairing
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
    // once the person actually responds. The prompt is derived from both the link and
    // sessions.session -- read together, right here, rather than from just the link -- so it
    // starts out correct; confirm() below is what catches the session changing *after* that,
    // while the dialog is already showing.
    init {
        viewModelScope.launch {
            shareInbox.pendingPairing.collect { pending ->
                // Busy means confirm() is mid-redeem for a different (already-taken) link. A
                // link arriving now is left exactly where it is in ShareInbox -- shown only once
                // that attempt settles (see settle()), never replacing this prompt or resetting
                // busy out from under it.
                if (pending == null || _state.value.busy) return@collect
                showOrDrop(pending, sessions.session.value)
            }
        }
    }

    /** Signs out first when already signed in (keeping the mirror -- see [SessionRepository.signOut]), then redeems the code. */
    fun confirm() {
        if (_state.value.busy) return
        val current = link ?: return
        val session = sessions.session.value
        // The dialog on screen may have been built for a different session than the one that
        // exists right now (signed out then, signed in since, say) -- confirm() must never act
        // on a stale prompt. Recomputed here, right before anything irreversible happens: if it
        // no longer matches what's showing, this tap is refused and the corrected dialog takes
        // its place instead of proceeding on the old wording.
        val recomputed = promptFor(current, session)
        if (recomputed != _state.value.prompt) {
            _state.value = PairingConfirmUiState(prompt = recomputed)
            return
        }
        // Captures the link and clears ShareInbox immediately, before any suspend: a second
        // confirm() call (or a new link arriving) must never see this one still sitting there.
        shareInbox.takePendingPairing()
        link = null
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            if (session is Session.SignedIn) sessions.signOut()
            // If this coroutine is torn down right here -- the activity finishing mid-confirm,
            // say, which cancels viewModelScope -- signOut() above has already completed and the
            // phone is left signed out with no error dialog ever shown for it: there is nothing
            // after this point that runs to say so. Accepted: it is the same risk any other
            // sign-out in this app already carries if the app is killed a moment too soon, and
            // there is no server-side state left dangling by it either way.
            val result = sessions.signInWithPairing(current, DeviceName.current())
            if (result.isSuccess) capabilities.load()
            settle(if (result.isSuccess) null else classifyPairingError(result.exceptionOrNull()))
        }
    }

    /**
     * What confirm()'s just-finished attempt leaves on screen: a link that arrived while busy
     * (re-read directly from ShareInbox, not via the collector above, which ignored it while
     * busy) takes priority over [error] -- shown fresh, or dropped unshown if it has already
     * outlived [MAX_PENDING_AGE_MS]. Otherwise [error] is shown (or nothing, on success).
     */
    private fun settle(error: PairError?) {
        val pending = shareInbox.pendingPairing.value
        if (pending != null && !isExpired(pending)) {
            showOrDrop(pending, sessions.session.value)
        } else {
            if (pending != null) shareInbox.takePendingPairing()
            link = null
            _state.value = PairingConfirmUiState(error = error)
        }
    }

    /** A link older than [MAX_PENDING_AGE_MS] -- the server's own code lifetime -- is dropped unshown; otherwise its prompt (for [session]) is shown. */
    private fun showOrDrop(pending: PendingPairing, session: Session) {
        if (isExpired(pending)) {
            shareInbox.takePendingPairing()
            link = null
            _state.value = PairingConfirmUiState()
            return
        }
        link = pending.link
        _state.value = PairingConfirmUiState(prompt = promptFor(pending.link, session))
    }

    private fun isExpired(pending: PendingPairing, nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - pending.arrivedAtMs > MAX_PENDING_AGE_MS

    private fun promptFor(link: PairingLink, session: Session): PairingPrompt {
        val toHost = hostOf(link.serverUrl)
        return if (session is Session.SignedIn) PairingPrompt.Replace(hostOf(session.serverUrl), toHost) else PairingPrompt.SignIn(toHost)
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
        /** The server's own pairing-code lifetime (see the release plan's Task B3 notes); a link this stale is dropped unshown rather than confirmed against a code that can no longer redeem. */
        const val MAX_PENDING_AGE_MS = 5 * 60 * 1000L

        /** Just the host, e.g. `logb.example.org`, `192.168.1.5:8080` or `[::1]:8080` -- a default port is dropped, never shown; an IPv6 literal is bracketed the way a URL would show it. */
        fun hostOf(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url
            val defaultPort = (parsed.scheme == "https" && parsed.port == 443) || (parsed.scheme == "http" && parsed.port == 80)
            val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
            return if (defaultPort) host else "$host:${parsed.port}"
        }
    }
}
