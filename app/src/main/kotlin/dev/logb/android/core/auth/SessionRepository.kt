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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    // Shared with SyncManager: a change of account first stops and joins any running sync, and
    // holds new ones off until the new session is committed. See AccountGate.
    private val accountGate: AccountGate = AccountGate(),
) {
    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session: StateFlow<Session> = _session.asStateFlow()

    // Serialises signIn/signInWithPairing/signOut/onUnauthorized/forgetServer so two flows -- a password sign-in racing a
    // scanned code's redeem, say -- can never interleave their token and record writes. Only
    // these three public entry points ever acquire it; each delegates to a private "*Locked"
    // twin that does the actual work, and signInWithPairing()'s own call to sign the old account
    // out goes straight to signOutLocked() rather than back through the public signOut() --
    // kotlinx.coroutines' Mutex is not reentrant, so acquiring it a second time from inside a
    // coroutine that already holds it would suspend forever. changePassword() and
    // signOutEverywhere() call the public signIn()/signOut() from outside any lock they hold
    // themselves, so they still serialise normally through it.
    private val mutex = Mutex()

    /**
     * The whole budget for one best-effort token revoke. Both revokes run while [mutex] (and, for
     * a switch or sign-out, the sync gate) is held; with OkHttp's own 15 s connect + 60 s read
     * timeouts a server that never answers would otherwise hold everything up for ~75 s.
     */
    internal var revokeTimeoutMs: Long = 5_000

    /** Everything [fetchSignInData] learns about a token, before anything is written down. */
    private data class SignInData(val me: User, val currency: String, val serverVersion: String?, val features: List<String>)

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
        val data = fetchSignInData(base, minted.token)
        // A password sign-in normally happens signed out (no sync can run then), but
        // changePassword() re-signs the same account in while a sync may be running: stop it
        // first, exactly as a switch does.
        withContext(NonCancellable) { accountGate.whileChanging { commitSignIn(base, minted.token, minted.id, data) } }
    }.recoverCatching { e ->
        // The server's own words for a wrong password; anything else is a connection problem.
        throw if (e is ApiException) IllegalStateException(e.message, e) else e
    }

    /**
     * Redeems a `logb://pair` code scanned from a QR code (or opened as a deep link) for a token,
     * and signs in with it -- leaving exactly the state [signIn] leaves. No password or session
     * cookie is involved: the code itself, freshly minted by a signed-in browser, is the proof.
     *
     * Health and redeem run first, with no local change at all. Once redemption has actually
     * succeeded -- a fresh token already minted on the server -- its data is fetched
     * ([fetchSignInData]), still with no local change: only once *that* has also succeeded is it
     * safe to touch anything on this phone, because only then is it known the new account can
     * actually be signed in. If an account was already signed in, it is signed out at that point
     * (exactly [signOut]'s own cleanup: revoke attempt, widget refresh, notifications cleared,
     * capabilities cleared), then the new account is stored ([commitSignIn]). A failed redeem, or
     * a failed fetch of the new token's own data, therefore both leave whatever was signed in
     * before -- its session, its token, its stored record -- completely untouched; there is
     * nothing to undo.
     *
     * The 404/401/400/429 -> unsupported/invalid/rejected/rate-limited mapping below applies only
     * to the redeem call itself. If [fetchSignInData] then fails -- say `me()` hits a 401, or a
     * 5xx -- that is an ordinary error, reported the same way [signIn]'s own post-token failures
     * are: this phone's redeemed token is already live on the server at that point, exactly as a
     * freshly minted password token would be if the same call failed there. Unlike a plain
     * [signIn] failing there, nothing on this phone has been touched yet at that point even while
     * replacing an existing account -- the old account's sign-out only ever runs after the fetch
     * has already succeeded.
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
        val data = try {
            fetchSignInData(base, redeemed.token)
        } catch (e: Exception) {
            // Nothing has been touched locally yet -- whatever was signed in before (if anything)
            // stays exactly as it was. Only the freshly redeemed, now-orphaned token needs
            // cleaning up.
            revokeRedeemedToken(base, redeemed.token, redeemed.tokenId)
            throw e
        }
        // From here on nothing may fail partway: either the old account (if any) ends up signed
        // out and the new one stored, or -- if cancelled -- both complete anyway. signOutLocked(),
        // not the public signOut(): this coroutine already holds mutex, and Mutex is not
        // reentrant -- acquiring it again here would deadlock. The swap itself runs inside
        // accountGate.whileChanging: a sync still running for the old account is cancelled and
        // joined first, and no new one starts until the new account is committed, so no sync
        // ever sees half of each account.
        withContext(NonCancellable) {
            accountGate.whileChanging {
                if (_session.value is Session.SignedIn) signOutLocked()
                try {
                    commitSignIn(base, redeemed.token, redeemed.tokenId, data)
                } catch (e: Exception) {
                    // Not stored after all (a failing store): the redeemed token is orphaned too.
                    revokeRedeemedToken(base, redeemed.token, redeemed.tokenId)
                    throw e
                }
            }
        }
    }.recoverCatching { e ->
        // The server's own words for anything that isn't PairingUnsupported/PairingInvalid/
        // PairingRejected/PairingRateLimited -- the same mapping signIn() applies to its own
        // post-token failures.
        throw if (e is ApiException) IllegalStateException(e.message, e) else e
    }

    /**
     * Best-effort cleanup for a redeem that succeeded but whose follow-up (`me()`, a network
     * error, a 5xx) then failed: the token is already live on the server with
     * nothing stored on the phone to show for it. Revokes it with its own bearer, exactly the
     * call [signOutLocked] makes for a token it already knows about -- this is the same
     * `DELETE /api/auth/tokens/{id}` call, just against the token that never made it past
     * [fetchSignInData]. `runCatching` so an older server's refusal (still cookie-only) never
     * surfaces here or changes the error already being reported to the caller; `NonCancellable` so
     * a cancelled sign-in flow still attempts it. Called from inside [signInWithPairingLocked],
     * which already holds [mutex] -- this only ever talks to the network, never back into another
     * guarded function, so there is nothing here that could deadlock on it.
     */
    private suspend fun revokeRedeemedToken(base: String, token: String, tokenId: Long) = withContext(NonCancellable) {
        bestEffortRevoke(base, token, tokenId)
    }

    /**
     * `DELETE /api/auth/tokens/{tokenId}` with [token]'s own bearer; any failure is swallowed, and
     * the call is cancelled -- the OkHttp call itself, via Retrofit's suspend support -- once
     * [revokeTimeoutMs] has passed. Timed on [Dispatchers.IO], i.e. in real time, whatever
     * dispatcher the caller runs on.
     */
    private suspend fun bestEffortRevoke(base: String, token: String, tokenId: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(revokeTimeoutMs) { apiFactory.create(base, { token }, null).revokeToken(tokenId) }
            }
        }
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
     * The network half of what [signIn] and [signInWithPairing] both need once a token is in
     * hand: who it belongs to, and what the server supports. Touches no store and no [session] --
     * a caller can find out whether a token actually works before disturbing anything already
     * signed in on this phone. `me()` (the account's own identity) must succeed, same as a failed
     * token mint would; `settings()` (the account's currency) and `healthInfo()` (server version
     * and announced features) are both best-effort, same as `healthInfo()` always has been -- a
     * momentarily flaky or old server simply falls back (`"EUR"` for currency; whatever this
     * phone already knew, or null/empty for a brand new one, for the server version/features),
     * never fails the sign-in over either.
     */
    private suspend fun fetchSignInData(base: String, token: String): SignInData {
        val bearerApi = apiFactory.create(base, { token }, null)
        val me = bearerApi.me()
        val currency = runCatching { bearerApi.settings().currency }.getOrDefault("EUR")
        val fetchedHealth = runCatching { bearerApi.healthInfo() }.getOrNull()?.takeIf { it.version.isNotBlank() }
        val existing = serverStore.read()?.takeIf { it.serverUrl == base }
        val serverVersion = fetchedHealth?.version ?: existing?.serverVersion
        val features = fetchedHealth?.features ?: existing?.features ?: emptyList()
        return SignInData(me, currency, serverVersion, features)
    }

    /**
     * The store half: write the token and record down, and flip [session] to signed in. Both
     * writes must land together or not at all: a cancellation landing between them (the caller's
     * coroutine scope going away mid-sign-in, e.g. a screen rotation racing the network call) must
     * never leave a token on the phone with no server record to use it with, or a server record
     * with no token stored for it.
     *
     * A failing write is handled the same way: if the token write fails, nothing new was stored;
     * if the record write fails after it, the new token is cleared again -- it must never sit
     * beside the previous account's record, where [restore] would pair the two -- and [session]
     * is re-read from what the stores now hold (signed out) before the error propagates.
     */
    private suspend fun commitSignIn(base: String, token: String, tokenId: Long, data: SignInData) {
        withContext(NonCancellable) {
            tokenStore.write(token)
            try {
                serverStore.write(ServerRecord(base, data.me.id, data.me.username, tokenId, data.currency, data.serverVersion, data.features))
            } catch (e: Exception) {
                runCatching { tokenStore.clear() }
                runCatching { restore() }
                throw e
            }
        }
        _session.value = Session.SignedIn(base, data.me, token, data.currency)
    }

    /**
     * Revokes the token (best effort) and forgets it. The mirror is the caller's business --
     * [afterSignOut] runs right after, still under the lock and with syncs held off, so deleting
     * the signed-out account's mirror there cannot race a sync or a new sign-in. A running sync is
     * stopped and joined first, and none starts again until the sign-out is done.
     *
     * [expected] is the session the person asked to sign out of (null: whoever is signed in). If
     * a switch has replaced it by the time this call gets the lock -- a tap queued behind a
     * pairing, say -- nothing happens and false is returned: the new account stays signed in.
     */
    suspend fun signOut(expected: Session? = null, afterSignOut: (suspend () -> Unit)? = null): Boolean = mutex.withLock {
        val current = _session.value
        if (expected != null && !sameAccount(expected, current)) {
            // A 401 (or another sign-out) may already have signed this very account out while the
            // confirm dialog waited for a tap: nothing left to sign out, but "remove local data" --
            // afterSignOut -- is still honoured for the account the person actually meant, found by
            // the stored record rather than the (now stale) session.
            if (expected is Session.SignedIn && current is Session.SignedOut) {
                val record = serverStore.read()
                if (record != null && record.serverUrl == expected.serverUrl && record.userId == expected.user.id) {
                    withContext(NonCancellable) { accountGate.whileChanging { afterSignOut?.invoke() } }
                }
            }
            return@withLock false
        }
        withContext(NonCancellable) {
            accountGate.whileChanging {
                signOutLocked()
                afterSignOut?.invoke()
            }
        }
        true
    }

    /** Same account: server and user for a signed-in session (a refreshed token is still the same account); plain equality otherwise. */
    private fun sameAccount(a: Session, b: Session): Boolean =
        if (a is Session.SignedIn && b is Session.SignedIn) a.serverUrl == b.serverUrl && a.user.id == b.user.id else a == b

    private suspend fun signOutLocked() {
        val current = _session.value
        val record = serverStore.read()
        if (current is Session.SignedIn && record?.tokenId != null) {
            bestEffortRevoke(current.serverUrl, current.token, record.tokenId)
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

    /**
     * Ends every browser session on the server (best effort), then signs this phone out; the
     * mirror stays. Like [signOut], does nothing when [expected] is no longer the signed-in account.
     */
    suspend fun signOutEverywhere(expected: Session? = null): Boolean {
        val current = _session.value
        if (expected != null && !sameAccount(expected, current)) return false
        if (current is Session.SignedIn) runCatching { apiFactory.create(current.serverUrl, { current.token }, null).logoutAll() }
        return signOut(expected = current)
    }

    /**
     * The token a client built for one account may send: the signed-in token while that account
     * (server and user) is still the one signed in, else null. A client that outlives its account
     * -- a sync that started before a switch, say -- can therefore never carry the next account's
     * token, on the same server or another.
     */
    fun tokenFor(serverUrl: String, userId: Long): String? =
        (_session.value as? Session.SignedIn)?.takeIf { it.serverUrl == serverUrl && it.user.id == userId }?.token

    /**
     * The server answered 401 to a request that carried [failedToken]: that token is gone. Keep
     * the server and the name, drop the token -- but only when [failedToken] is still the stored
     * token. A 401 for a token that has since been replaced (a switch, a password change) or for
     * no token at all says nothing about the current one, and is ignored. Serialised with sign-in
     * and sign-out, so it can never land between a switch's writes. Returns whether it signed out.
     */
    suspend fun onUnauthorized(failedToken: String?): Boolean = mutex.withLock {
        if (failedToken == null || failedToken != tokenStore.read()) return@withLock false
        val record = serverStore.read()
        tokenStore.clear()
        if (record != null) serverStore.write(record.copy(tokenId = null))
        _session.value = Session.SignedOut(record?.serverUrl ?: "", record?.username, reason = "unauthorized")
        widgetRefresher.requestImmediateRefresh()
        notifications.clearAll()
        capabilities.clear()
        true
    }

    /** Forget the server too: back to the first-run screen. Serialised with sign-in, so it never lands between a sign-in's writes. */
    suspend fun forgetServer() = mutex.withLock {
        tokenStore.clear()
        serverStore.clear()
        _session.value = Session.NeedsServer
        notifications.clearAll()
    }
}
