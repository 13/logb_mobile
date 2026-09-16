package dev.logb.android.core.auth

import android.os.Build
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.dto.UserPatch
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.network.dto.Credentials
import dev.logb.android.core.network.dto.NewToken
import dev.logb.android.core.network.dto.PairRedeem
import dev.logb.android.core.network.dto.PairRedeemed
import dev.logb.android.core.network.dto.User
import dev.logb.android.core.alerts.NoopReminderNotificationsClearer
import dev.logb.android.core.alerts.ReminderNotificationsClearer
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.core.widget.NoopWidgetRefresher
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds a client for a server; injectable so tests can point one at a mock server. */
fun interface ApiFactory {
    fun create(baseUrl: String, tokenProvider: () -> String?, cookieJar: okhttp3.CookieJar?): LogbApi
}

/** [SessionRepository.signInWithPairing]: the server answered 404 -- it has no pairing endpoint. */
class PairingUnsupported(message: String) : Exception(message)

/** [SessionRepository.signInWithPairing]: the server answered 401 -- the code is unknown, expired or already used. */
class PairingInvalid(message: String) : Exception(message)

/** [SessionRepository.signInWithPairing]: the server answered 400 -- it refused the redeem outright (a malformed device name, say). */
class PairingRejected(message: String) : Exception(message)

/** [SessionRepository.signInWithPairing]: the server answered 429 -- too many redeem attempts from this IP, too quickly. */
class PairingRateLimited(message: String) : Exception(message)

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
    // Injected directly (not through a tiny interface like WidgetRefresher / ReminderNotificationsClearer
    // above) because ServerCapabilities is already a plain core.server singleton with no dependency
    // back on core.auth, so there is no cycle to break; those interfaces exist to keep core.auth from
    // depending on feature-layer, platform-backed implementations, which does not apply here.
    private val capabilities: ServerCapabilities = ServerCapabilities(serverStore),
) {
    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session: StateFlow<Session> = _session.asStateFlow()

    // Serialises signIn/signInWithPairing/signOut so two flows -- a password sign-in racing a
    // scanned code's redeem, say -- can never interleave their token and record writes. Only
    // these three public entry points ever acquire it; each delegates to a private "*Locked"
    // twin that does the actual work, and signInWithPairing()'s own call to sign the old account
    // out goes straight to signOutLocked() rather than back through the public signOut() --
    // kotlinx.coroutines' Mutex is not reentrant, so acquiring it a second time from inside a
    // coroutine that already holds it would suspend forever. changePassword() and
    // signOutEverywhere() call the public signIn()/signOut() from outside any lock they hold
    // themselves, so they still serialise normally through it.
    private val mutex = Mutex()

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

    suspend fun signIn(rawUrl: String, username: String, password: String): Result<Unit> = mutex.withLock { signInLocked(rawUrl, username, password) }

    private suspend fun signInLocked(rawUrl: String, username: String, password: String): Result<Unit> = runCatching {
        val base = ApiClient.normalizeBaseUrl(rawUrl)
        val jar = InMemoryCookieJar()
        val cookieApi = apiFactory.create(base, { null }, jar)
        cookieApi.login(Credentials(username, password))
        val minted = cookieApi.createToken(NewToken("LogB Android · ${Build.MODEL}"))
        runCatching { cookieApi.logout() } // best effort: the token is what matters
        finishSigningIn(base, minted.token, minted.id)
    }.recoverCatching { e ->
        // The server's own words for a wrong password; anything else is a connection problem.
        throw if (e is ApiException) IllegalStateException(e.message, e) else e
    }

    /**
     * Redeems a `logb://pair` code scanned from a QR code (or opened as a deep link) for a token,
     * and signs in with it -- leaving exactly the state [signIn] leaves. No password or session
     * cookie is involved: the code itself, freshly minted by a signed-in browser, is the proof.
     *
     * Health and redeem run first, with no local change at all. Only once redemption has actually
     * succeeded -- a fresh token already minted on the server -- is it safe to touch anything on
     * this phone: if an account was already signed in, it is signed out first (exactly
     * [signOut]'s own cleanup: revoke attempt, widget refresh, notifications cleared, capabilities
     * cleared), then the new account is stored via [finishSigningIn]. A failed redeem therefore
     * leaves whatever was signed in before -- its session, its token, its stored record --
     * completely untouched; there is nothing to undo.
     *
     * The 404/401/400/429 -> unsupported/invalid/rejected/rate-limited mapping below applies only
     * to the redeem call itself. If [finishSigningIn] then fails -- say `me()` hits a 401, or a
     * 5xx -- that is an ordinary error, reported the same way [signIn]'s own post-token failures
     * are: this phone's redeemed token is already live on the server at that point, exactly as a
     * freshly minted password token would be if the same call failed there. (When this happens
     * while replacing an existing account, that account has already been signed out by this
     * point, same as it would be if the network simply dropped right after a successful
     * password-based [signIn]'s own token mint.)
     *
     * Unlike [signIn], there is no cookie session here to end, so nothing on the server is ever
     * told this attempt failed except a best-effort self-revoke of the freshly redeemed token
     * (see [revokeRedeemedToken]): on a server with self-revoke (`DELETE /api/auth/tokens/{id}`
     * accepting the calling token's own bearer -- server commit e8ae22f) that actually revokes
     * it; on an older server the call simply fails and the token stays live, named after the
     * device ([SessionRepository.signIn]'s `NewToken` naming applies here too), so it can still be
     * found and removed from the web's API access page.
     */
    suspend fun signInWithPairing(link: PairingLink, deviceName: String): Result<Unit> = mutex.withLock { signInWithPairingLocked(link, deviceName) }

    private suspend fun signInWithPairingLocked(link: PairingLink, deviceName: String): Result<Unit> = runCatching {
        val base = ApiClient.normalizeBaseUrl(link.serverUrl)
        val anonApi = apiFactory.create(base, { null }, null)
        anonApi.health()
        val redeemed = redeemPairing(anonApi, link.code, deviceName)
        try {
            // signOutLocked(), not the public signOut(): this coroutine already holds mutex, and
            // Mutex is not reentrant -- acquiring it again here would deadlock.
            if (_session.value is Session.SignedIn) signOutLocked()
            finishSigningIn(base, redeemed.token, redeemed.tokenId)
        } catch (e: Exception) {
            revokeRedeemedToken(base, redeemed.token, redeemed.tokenId)
            throw e
        }
    }.recoverCatching { e ->
        // The server's own words for anything that isn't PairingUnsupported/PairingInvalid/
        // PairingRejected/PairingRateLimited -- the same mapping signIn() applies to its own
        // post-token failures.
        throw if (e is ApiException) IllegalStateException(e.message, e) else e
    }

    /**
     * Best-effort cleanup for a redeem that succeeded but whose follow-up (`me()`/`settings()`/
     * health, a network error, a 5xx) then failed: the token is already live on the server with
     * nothing stored on the phone to show for it. Revokes it with its own bearer, exactly the
     * call [signOutLocked] makes for a token it already knows about -- this is the same
     * `DELETE /api/auth/tokens/{id}` call, just against the token that never made it into
     * [finishSigningIn]. `runCatching` so an older server's refusal (still cookie-only) never
     * surfaces here or changes the error already being reported to the caller; `NonCancellable` so
     * a cancelled sign-in flow still attempts it. Called from inside [signInWithPairingLocked],
     * which already holds [mutex] -- this only ever talks to the network, never back into another
     * guarded function, so there is nothing here that could deadlock on it.
     */
    private suspend fun revokeRedeemedToken(base: String, token: String, tokenId: Long) = withContext(NonCancellable) {
        runCatching { apiFactory.create(base, { token }, null).revokeToken(tokenId) }
        Unit
    }

    /** Only the redeem call maps 404/401/400/429 to [PairingUnsupported]/[PairingInvalid]/[PairingRejected]/[PairingRateLimited]; a 500 here, or any failure past this point, is an ordinary error. */
    private suspend fun redeemPairing(api: LogbApi, code: String, deviceName: String): PairRedeemed = try {
        api.redeemPairing(PairRedeem(code, deviceName))
    } catch (e: ApiException) {
        throw when (e.status) {
            404 -> PairingUnsupported(e.message)
            401 -> PairingInvalid(e.message)
            400 -> PairingRejected(e.message)
            429 -> PairingRateLimited(e.message)
            else -> e
        }
    }

    /**
     * The tail [signIn] and [signInWithPairing] share once each has its own token in hand: fetch
     * who that token belongs to and what the server supports, then store everything and flip the
     * session to signed in.
     */
    private suspend fun finishSigningIn(base: String, token: String, tokenId: Long) {
        val bearerApi = apiFactory.create(base, { token }, null)
        val me = bearerApi.me()
        val currency = runCatching { bearerApi.settings().currency }.getOrDefault("EUR")
        val fetchedHealth = runCatching { bearerApi.healthInfo() }.getOrNull()?.takeIf { it.version.isNotBlank() }
        val existing = serverStore.read()?.takeIf { it.serverUrl == base }
        val serverVersion = fetchedHealth?.version ?: existing?.serverVersion
        val features = fetchedHealth?.features ?: existing?.features ?: emptyList()
        // Both writes must land together or not at all: a cancellation landing between them (the
        // caller's coroutine scope going away mid-sign-in, e.g. a screen rotation racing the
        // network call) must never leave a token on the phone with no server record to use it
        // with, or a server record with no token stored for it.
        withContext(NonCancellable) {
            tokenStore.write(token)
            serverStore.write(ServerRecord(base, me.id, me.username, tokenId, currency, serverVersion, features))
        }
        _session.value = Session.SignedIn(base, me, token, currency)
    }

    /** Revokes the token (best effort) and forgets it. The mirror is the caller's business. */
    suspend fun signOut() = mutex.withLock { signOutLocked() }

    private suspend fun signOutLocked() {
        val current = _session.value
        val record = serverStore.read()
        if (current is Session.SignedIn && record?.tokenId != null) {
            runCatching { apiFactory.create(current.serverUrl, { current.token }, null).revokeToken(record.tokenId) }
        }
        tokenStore.clear()
        if (record != null) serverStore.write(record.copy(tokenId = null))
        _session.value = Session.SignedOut(record?.serverUrl ?: (current as? Session.SignedIn)?.serverUrl ?: "", record?.username)
        // Neither a placed widget nor a posted notification may keep showing the account's data
        // past the moment it signs out, and a later account signing into this same server must
        // not inherit this one's server-capabilities guard or in-memory version either.
        widgetRefresher.requestImmediateRefresh()
        notifications.clearAll()
        capabilities.clear()
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
        capabilities.clear()
    }

    /** Forget the server too: back to the first-run screen. */
    suspend fun forgetServer() {
        tokenStore.clear()
        serverStore.clear()
        _session.value = Session.NeedsServer
        notifications.clearAll()
    }
}
