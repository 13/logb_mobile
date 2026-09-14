package dev.logb.android.core.auth

import dev.logb.android.core.network.dto.User

sealed interface Session {
    /** Not yet known: stores are still being read at start-up. */
    data object Loading : Session

    /** No server remembered: first run. */
    data object NeedsServer : Session

    /** A server is known but there is no usable token. */
    data class SignedOut(val serverUrl: String, val username: String?, val reason: String? = null) : Session

    data class SignedIn(val serverUrl: String, val user: User, val token: String, val currency: String) : Session
}
