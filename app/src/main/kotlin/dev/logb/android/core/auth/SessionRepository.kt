package dev.logb.android.core.auth

import android.os.Build
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.dto.UserPatch
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.network.dto.Credentials
import dev.logb.android.core.network.dto.NewToken
import dev.logb.android.core.network.dto.User
import dev.logb.android.core.alerts.NoopReminderNotificationsClearer
import dev.logb.android.core.alerts.ReminderNotificationsClearer
import dev.logb.android.core.widget.NoopWidgetRefresher
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Builds a client for a server; injectable so tests can point one at a mock server. */
fun interface ApiFactory {
    fun create(baseUrl: String, tokenProvider: () -> String?, cookieJar: okhttp3.CookieJar?): LogbApi
}

/**
 * Who is signed in, and how that changes. Signing in is one online exchange: a password buys a
 * session cookie, the cookie mints a personal access token, the cookie session is ended, and
 * from then on only the token is used. The mirror is never touched here.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val serverStore: ServerStore,
    private val tokenStore: TokenStore,
    private val apiFactory: ApiFactory,
    private val widgetRefresher: WidgetRefresher = NoopWidgetRefresher,
    private val notifications: ReminderNotificationsClearer = NoopReminderNotificationsClearer,
) {
    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session: StateFlow<Session> = _session.asStateFlow()

    /** What the stores say. Called once at start-up; no network. */
    suspend fun restore() {
        val record = serverStore.read()
        val token = tokenStore.read()
        _session.value = when {
            record == null -> Session.NeedsServer
            token == null || record.userId == null -> Session.SignedOut(record.serverUrl, record.username)
            else -> Session.SignedIn(record.serverUrl, User(record.userId, record.username ?: ""), token, record.currency)
        }
    }

    /** `GET /health` against a typed address; on success the address is remembered. */
    suspend fun checkServer(rawUrl: String): Result<String> = runCatching {
        val base = ApiClient.normalizeBaseUrl(rawUrl)
        apiFactory.create(base, { null }, null).health()
        val existing = serverStore.read()
        serverStore.write(if (existing?.serverUrl == base) existing else ServerRecord(base))
        if (_session.value !is Session.SignedIn) _session.value = Session.SignedOut(base, existing?.username?.takeIf { existing.serverUrl == base })
        base
    }

    suspend fun signIn(rawUrl: String, username: String, password: String): Result<Unit> = runCatching {
        val base = ApiClient.normalizeBaseUrl(rawUrl)
        val jar = InMemoryCookieJar()
        val cookieApi = apiFactory.create(base, { null }, jar)
        cookieApi.login(Credentials(username, password))
        val minted = cookieApi.createToken(NewToken("LogB Android · ${Build.MODEL}"))
        runCatching { cookieApi.logout() } // best effort: the token is what matters
        val bearerApi = apiFactory.create(base, { minted.token }, null)
        val me = bearerApi.me()
        val currency = runCatching { bearerApi.settings().currency }.getOrDefault("EUR")
        val fetchedVersion = runCatching { bearerApi.healthInfo().version }.getOrNull()?.takeIf { it.isNotBlank() }
        val serverVersion = fetchedVersion ?: serverStore.read()?.takeIf { it.serverUrl == base }?.serverVersion
        tokenStore.write(minted.token)
        serverStore.write(ServerRecord(base, me.id, me.username, minted.id, currency, serverVersion))
        _session.value = Session.SignedIn(base, me, minted.token, currency)
    }.recoverCatching { e ->
        // The server's own words for a wrong password; anything else is a connection problem.
        throw if (e is ApiException) IllegalStateException(e.message, e) else e
    }

    /** Revokes the token (best effort) and forgets it. The mirror is the caller's business. */
    suspend fun signOut() {
        val current = _session.value
        val record = serverStore.read()
        if (current is Session.SignedIn && record?.tokenId != null) {
            runCatching { apiFactory.create(current.serverUrl, { current.token }, null).revokeToken(record.tokenId) }
        }
        tokenStore.clear()
        if (record != null) serverStore.write(record.copy(tokenId = null))
        _session.value = Session.SignedOut(record?.serverUrl ?: (current as? Session.SignedIn)?.serverUrl ?: "", record?.username)
        // Neither a placed widget nor a posted notification may keep showing the account's data
        // past the moment it signs out.
        widgetRefresher.requestImmediateRefresh()
        notifications.clearAll()
    }

    /**
     * One's own password, through the users endpoint; the server's own words on refusal. The
     * server revokes every session and API token of the account with it -- this phone's included
     * -- so a fresh token is minted with the new password at once, and the mirror stays.
     */
    suspend fun changePassword(newPassword: String): Result<Unit> = runCatching {
        val current = _session.value as? Session.SignedIn ?: error("signed out")
        apiFactory.create(current.serverUrl, { current.token }, null).updateUser(current.user.id, UserPatch(newPassword))
        signIn(current.serverUrl, current.user.username, newPassword).getOrThrow()
        Unit
    }.recoverCatching { e -> throw if (e is ApiException) IllegalStateException(e.message, e) else e }

    /** Ends every browser session on the server (best effort), then signs this phone out; the mirror stays. */
    suspend fun signOutEverywhere() {
        val current = _session.value
        if (current is Session.SignedIn) runCatching { apiFactory.create(current.serverUrl, { current.token }, null).logoutAll() }
        signOut()
    }

    /** The server answered 401: the token is gone. Keep the server and the name, drop the token. */
    suspend fun onUnauthorized() {
        val record = serverStore.read()
        tokenStore.clear()
        if (record != null) serverStore.write(record.copy(tokenId = null))
        _session.value = Session.SignedOut(record?.serverUrl ?: "", record?.username, reason = "unauthorized")
        widgetRefresher.requestImmediateRefresh()
        notifications.clearAll()
    }

    /** Forget the server too: back to the first-run screen. */
    suspend fun forgetServer() {
        tokenStore.clear()
        serverStore.clear()
        _session.value = Session.NeedsServer
        notifications.clearAll()
    }
}
