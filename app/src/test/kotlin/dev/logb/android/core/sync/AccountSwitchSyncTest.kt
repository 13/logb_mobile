package dev.logb.android.core.sync

import dev.logb.android.core.auth.AccountGate
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.PairingLink
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.auth.TokenStore
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.UnauthorizedException
import dev.logb.android.core.network.dto.PushBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A switch of account (a pairing confirmed while signed in) against a sync that is already
 * running: the switch stops and joins the sync first, and no request of the old account's pass
 * ever carries the new account's token -- on the same server or another.
 */
class AccountSwitchSyncTest {
    private class FakeConnectivity : ConnectivityMonitor {
        override val isOnline = MutableStateFlow(true)
        override val isUnmetered = true
    }

    private val servers = mutableListOf<MockWebServer>()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After fun stop() = servers.forEach { it.close() }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    /** Answers every call of the switch and of the fake sync; `me()` answers per bearer, 401 without one. */
    private fun server(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val auth = request.headers["Authorization"]
                return when (request.url.encodedPath) {
                    "/api/health" -> json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")
                    "/api/auth/pair/redeem" -> json("""{"token":"$NEW_TOKEN","token_id":22,"user":{"id":2,"username":"ann","lang":"en"}}""")
                    "/api/auth/me" -> when (auth) {
                        "Bearer $NEW_TOKEN" -> json("""{"id":2,"username":"ann","lang":"en"}""")
                        "Bearer $OLD_TOKEN" -> json("""{"id":1,"username":"ben","lang":"en"}""")
                        else -> json("""{"error":"unauthorized","message":"no token"}""", code = 401)
                    }
                    "/api/settings" -> json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")
                    "/api/sync/push" -> json("""{"results":[],"server_time":"2026-09-16T10:00:00.000Z"}""")
                    else -> json("{}", code = 204) // the old account's revoke
                }
            }
        }
        start()
        servers += this
    }

    private val serverStore = FakeServerStore()
    private val tokens = FakeTokenStore()
    private val recordingTokens = object : TokenStore {
        override suspend fun read(): String? = tokens.read()
        override suspend fun write(token: String) { events += "token written"; tokens.write(token) }
        override suspend fun clear() { events += "token cleared"; tokens.clear() }
    }
    private val gate = AccountGate()
    private val sessions = SessionRepository(serverStore, recordingTokens, ApiFactory { b, t, j -> ApiClient.create(b, t, j) }, accountGate = gate)

    private suspend fun signInOld(base: String) {
        serverStore.write(ServerRecord(base, 1, "ben", 9, "CHF"))
        tokens.write(OLD_TOKEN)
        sessions.restore()
    }

    /** Like SyncModule's: one client for the pass, its token bound to the account it was built for. */
    private fun runnerFactory(body: suspend (push: suspend () -> Unit) -> Unit): () -> SyncRunner? = {
        (sessions.session.value as? Session.SignedIn)?.let { s ->
            val api = ApiClient.create(s.serverUrl, { sessions.tokenFor(s.serverUrl, s.user.id) })
            SyncRunner {
                try {
                    body { api.push(PushBody(emptyList())) }
                } finally {
                    events += "sync stopped (${s.user.username})"
                }
            }
        }
    }

    private fun pushes() = requests.filter { it.url.encodedPath == "/api/sync/push" }

    private fun switchesWhileASyncIsSuspendedMidPush(sameServer: Boolean) = runTest {
        val old = server()
        val new = if (sameServer) old else server()
        signInOld(old.url("/").toString())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = SyncManager(sessions, FakeConnectivity(), runnerFactory { push -> started.complete(Unit); release.await(); push() }, backgroundScope, gate = gate)

        val sync = async { manager.syncNow() }
        started.await()
        val switched = sessions.signInWithPairing(PairingLink(new.url("/").toString(), "abc123"), "Pixel 8")
        assertTrue(switched.isSuccess, switched.toString())

        // The switch cancelled the sync and waited for it before writing the new token.
        assertIs<SyncInterrupted>(sync.await().exceptionOrNull())
        assertEquals(listOf("sync stopped (ben)", "token cleared", "token written"), events.toList())
        assertTrue(pushes().isEmpty(), "the old account's push never went out: ${pushes().map { it.headers["Authorization"] }}")
        assertEquals("ann", assertIs<Session.SignedIn>(sessions.session.value).user.username)

        // Syncs run again afterwards -- for the new account, with its token, on its server.
        release.complete(Unit)
        assertTrue(manager.syncNow().isSuccess)
        val push = pushes().single()
        assertEquals("Bearer $NEW_TOKEN", push.headers["Authorization"])
        assertEquals(new.port, push.url.port)
    }

    @Test fun `a switch on the same server cancels a sync suspended mid-push and waits for it`() = switchesWhileASyncIsSuspendedMidPush(sameServer = true)

    @Test fun `a switch to another server cancels a sync suspended mid-push and waits for it`() = switchesWhileASyncIsSuspendedMidPush(sameServer = false)

    private fun switchWaitsForASyncThatWillNotStop(sameServer: Boolean) = runTest {
        val old = server()
        val new = if (sameServer) old else server()
        signInOld(old.url("/").toString())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A pass that ignores cancellation until its push is done: the switch has to wait it out.
        val manager = SyncManager(
            sessions, FakeConnectivity(),
            runnerFactory { push -> withContext(NonCancellable) { started.complete(Unit); release.await(); push() } },
            backgroundScope, gate = gate,
        )

        val sync = async { manager.syncNow() }
        started.await()
        val switch = async { sessions.signInWithPairing(PairingLink(new.url("/").toString(), "abc123"), "Pixel 8") }
        gate.changing.first { it } // the switch has cancelled the pass and is waiting on the gate's lock

        // Deterministic, not time-based: whileChanging's lock.withLock cannot have acquired the
        // lock yet -- the (NonCancellable) pass still holds it, since release has not been
        // completed -- so the switch cannot have written anything or finished, no matter how long
        // this test waits.
        try {
            assertFalse(switch.isCompleted, "the switch cannot finish while the sync still holds the gate")
            assertEquals(OLD_TOKEN, tokens.read(), "nothing is swapped while the old account's sync still runs")
            assertEquals("ben", assertIs<Session.SignedIn>(sessions.session.value).user.username)
        } finally {
            release.complete(Unit) // or a failed assertion leaves the non-cancellable pass hanging
        }
        assertTrue(switch.await().isSuccess)
        sync.await()

        // The push went out while the old account was still signed in: its own token, its own server.
        val push = pushes().single()
        assertEquals("Bearer $OLD_TOKEN", push.headers["Authorization"])
        assertEquals(old.port, push.url.port)
        assertEquals(listOf("sync stopped (ben)", "token cleared", "token written"), events.toList())
        assertEquals(NEW_TOKEN, tokens.read())
    }

    @Test fun `a switch on the same server waits for a sync that finishes its push`() = switchWaitsForASyncThatWillNotStop(sameServer = true)

    @Test fun `a switch to another server waits for a sync that finishes its push`() = switchWaitsForASyncThatWillNotStop(sameServer = false)

    @Test fun `a stale client gets no token after a switch, and its 401 leaves the new account signed in`() = runTest {
        val old = server()
        val new = server()
        val oldBase = old.url("/").toString()
        signInOld(oldBase)
        val stale = ApiClient.create(oldBase, { sessions.tokenFor(oldBase, 1) })
        assertEquals(OLD_TOKEN, sessions.tokenFor(oldBase, 1))

        assertTrue(sessions.signInWithPairing(PairingLink(new.url("/").toString(), "abc123"), "Pixel 8").isSuccess)
        assertNull(sessions.tokenFor(oldBase, 1))
        assertNull(sessions.tokenFor(new.url("/").toString(), 1), "same user id, other server: still not this client's account")

        val refused = runCatching { stale.me() }.exceptionOrNull()
        val unauthorized = assertIs<UnauthorizedException>(refused)
        assertNull(unauthorized.token)
        val staleRequest = requests.last { it.url.port == old.port }
        assertNull(staleRequest.headers["Authorization"], "the stale client sent no bearer at all")

        assertFalse(sessions.onUnauthorized(unauthorized.token))
        assertFalse(sessions.onUnauthorized(OLD_TOKEN), "the old account's token is not the stored one any more")
        val s = assertIs<Session.SignedIn>(sessions.session.value)
        assertEquals("ann", s.user.username)
        assertEquals(NEW_TOKEN, tokens.read())
        assertEquals(2L, serverStore.read()?.userId)

        assertTrue(sessions.onUnauthorized(NEW_TOKEN), "a 401 for the current token still signs out")
        assertIs<Session.SignedOut>(sessions.session.value)
    }

    @Test fun `a sync requested during a switch waits and then runs for the new account`() = runTest {
        val old = server()
        val new = server()
        signInOld(old.url("/").toString())
        val holdSwap = CompletableDeferred<Unit>()
        val inSwap = CompletableDeferred<Unit>()
        val manager = SyncManager(sessions, FakeConnectivity(), runnerFactory { push -> push() }, backgroundScope, gate = gate)

        val change = async { gate.whileChanging { inSwap.complete(Unit); holdSwap.await() } }
        inSwap.await()
        val sync = async { manager.syncNow() }
        runCurrent() // the sync is now waiting for the change to finish
        assertTrue(gate.changing.value)
        assertFalse(sync.isCompleted)
        // Commit a new account while the sync waits (the same thing a switch does inside the gate).
        serverStore.write(ServerRecord(new.url("/").toString(), 2, "ann", 22, "CHF"))
        tokens.write(NEW_TOKEN)
        sessions.restore()
        holdSwap.complete(Unit)
        change.await()

        assertTrue(sync.await().isSuccess)
        val push = pushes().single()
        assertEquals("Bearer $NEW_TOKEN", push.headers["Authorization"])
        assertEquals(new.port, push.url.port)
    }

    @Test
    fun `a sign-out holding the mutex and waiting for the gate, and a sync's own real 401 for the current token, both finish without deadlock`() = runTest {
        val old = server()
        signInOld(old.url("/").toString())
        val started = CompletableDeferred<Unit>()
        val manager = SyncManager(sessions, FakeConnectivity(), runnerFactory { push -> started.complete(Unit); CompletableDeferred<Unit>().await(); push() }, backgroundScope, gate = gate)

        val syncA = async { manager.syncNow() }
        started.await()
        val signOut = async { sessions.signOut() }
        gate.changing.first { it } // sign-out has cancelled the paused pass and is waiting on the gate's lock

        // A [different, already-finished] sync's own real 401, for the token that is -- at this
        // exact moment -- still the stored one. This is exactly what SyncManager.syncNow() calls
        // after its own gate.runBound returns, entirely outside the gate, so it is legitimate to
        // call it directly here: it lands while sign-out still holds the session mutex, having
        // itself already taken and being about to release the gate.
        val onUnauthorized = async { sessions.onUnauthorized(OLD_TOKEN) }

        // Off the test scheduler: signOut's revoke hops to a real Dispatchers.IO, which the
        // virtual clock cannot see, so a withTimeout still tied to virtual time would fire the
        // instant nothing else is virtual-time-tracked -- long before that real work returns.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                assertIs<SyncInterrupted>(syncA.await().exceptionOrNull())
                signOut.await()
                onUnauthorized.await()
            }
        }

        // No deadlock, and the end state agrees with itself, whichever of the two actually cleared
        // the token: signed out, nothing left to revoke twice, and the interrupted pass reported
        // through the status the same way any other interruption does.
        assertIs<Session.SignedOut>(sessions.session.value)
        assertNull(tokens.read())
        assertNull(serverStore.read()?.tokenId)
        assertEquals(SyncStatus.None, manager.status.value)
    }

    /** Like [server], but also answers the requests a password change and its re-sign-in make. */
    private fun passwordChangeServer(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val auth = request.headers["Authorization"]
                val path = request.url.encodedPath
                return when {
                    path == "/api/sync/push" -> json("""{"results":[],"server_time":"2026-09-16T10:00:00.000Z"}""")
                    path == "/api/users/1" && request.method == "PATCH" -> json("{}")
                    path == "/api/auth/login" ->
                        MockResponse.Builder().code(200).addHeader("content-type", "application/json")
                            .addHeader("Set-Cookie", "logb_session=abc; Path=/; HttpOnly")
                            .body("""{"id":1,"username":"ben","lang":"en"}""").build()
                    path == "/api/auth/tokens" && request.method == "POST" ->
                        json("""{"id":30,"name":"LogB Android","prefix":"logb_pat_ch","created_at":"x","last_used_at":null,"token":"$CHANGED_TOKEN"}""", code = 201)
                    path == "/api/auth/logout" -> json("{}")
                    path == "/api/auth/me" -> when (auth) {
                        "Bearer $OLD_TOKEN" -> json("""{"id":1,"username":"ben","lang":"en"}""")
                        "Bearer $CHANGED_TOKEN" -> json("""{"id":1,"username":"ben","lang":"en"}""")
                        else -> json("""{"error":"unauthorized","message":"no token"}""", code = 401)
                    }
                    path == "/api/settings" -> json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")
                    path == "/api/health" -> json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")
                    else -> json("{}", code = 204) // the paused pass's own revoke, if any
                }
            }
        }
        start()
        servers += this
    }

    @Test
    fun `changePassword while a sync hits a 401 with the old token ends signed in with the right status`() = runTest {
        val old = passwordChangeServer()
        signInOld(old.url("/").toString())
        val started = CompletableDeferred<Unit>()
        val manager = SyncManager(sessions, FakeConnectivity(), runnerFactory { push -> started.complete(Unit); CompletableDeferred<Unit>().await(); push() }, backgroundScope, gate = gate)

        val syncA = async { manager.syncNow() }
        started.await()
        // changePassword re-signs the same account in, exactly like a switch: it stops and joins
        // any running sync first (SessionRepository.signInLocked -> accountGate.whileChanging).
        val changePassword = async { sessions.changePassword("correct horse battery") }
        gate.changing.first { it } // the re-sign-in has cancelled the paused pass and is waiting on the gate's lock

        // A sync's own real 401 for OLD_TOKEN -- still the current token at this exact moment,
        // since commitSignIn has not run yet -- lands while changePassword still holds the mutex.
        val onUnauthorized = async { sessions.onUnauthorized(OLD_TOKEN) }

        withTimeout(5_000) {
            assertIs<SyncInterrupted>(syncA.await().exceptionOrNull())
            assertTrue(changePassword.await().isSuccess, "changePassword must not be tripped up by the concurrent 401")
            assertFalse(onUnauthorized.await(), "the old token is no longer the current one by the time this runs")
        }

        val signedIn = assertIs<Session.SignedIn>(sessions.session.value)
        assertEquals("ben", signedIn.user.username)
        assertNotEquals(OLD_TOKEN, signedIn.token, "the account is signed in with its freshly changed token")
        assertEquals(CHANGED_TOKEN, tokens.read())
        assertEquals(SyncStatus.None, manager.status.value)
    }

    @Test fun `sign out stops a running sync before it clears the token`() = runTest {
        val old = server()
        signInOld(old.url("/").toString())
        val started = CompletableDeferred<Unit>()
        val manager = SyncManager(sessions, FakeConnectivity(), runnerFactory { push -> started.complete(Unit); CompletableDeferred<Unit>().await(); push() }, backgroundScope, gate = gate)

        val sync = async { manager.syncNow() }
        started.await()
        sessions.signOut()

        assertIs<SyncInterrupted>(sync.await().exceptionOrNull())
        assertEquals(listOf("sync stopped (ben)", "token cleared"), events.toList())
        assertTrue(pushes().isEmpty())
        // Nobody is signed in now: a new pass has no runner.
        assertTrue(manager.syncNow().isFailure)
        assertIs<SyncStatus.SignedOut>(manager.status.value)
    }

    private companion object {
        const val OLD_TOKEN = "logb_pat_old"
        const val NEW_TOKEN = "logb_pat_new"
        const val CHANGED_TOKEN = "logb_pat_changed"
    }
}
