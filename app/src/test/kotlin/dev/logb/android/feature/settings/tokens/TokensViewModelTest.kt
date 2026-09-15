package dev.logb.android.feature.settings.tokens

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.PasswordSession
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.dto.ApiToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TokensViewModelTest {
    private fun token(id: Long, prefix: String = "logb_pat_$id") = ApiToken(id, "t$id", prefix, "2026-09-01T00:00:00Z", null)

    // --- TokenRows: pure, no coroutines/db/network involved. ---

    @Test fun `the phone's own token is marked by id and keeps the server's order`() {
        val rows = TokenRows.of(listOf(token(12), token(9), token(3)), phoneTokenId = 9, phonePrefix = null)
        assertEquals(listOf(12L, 9L, 3L), rows.map { it.token.id })
        assertEquals(listOf(false, true, false), rows.map { it.isThisPhone })
        assertEquals(listOf(true, false, true), rows.map { it.canRevoke }, "the phone's own row can never be revoked")
    }

    @Test fun `no known phone token marks nothing`() =
        assertEquals(listOf(false), TokenRows.of(listOf(token(1)), phoneTokenId = null, phonePrefix = null).map { it.isThisPhone })

    @Test fun `token names are trimmed and 1 to 64 characters`() {
        assertEquals(null, TokenRows.validName("   "))
        assertEquals("script", TokenRows.validName("  script "))
        assertEquals(null, TokenRows.validName("x".repeat(65)))
        assertEquals("é".repeat(64), TokenRows.validName("é".repeat(64)))
    }

    @Test fun `a row is matched by prefix when the id is not known`() {
        val rows = TokenRows.of(
            listOf(token(1, "logb_pat_aaaaaaaaaaaaaaa"), token(2, "logb_pat_bbbbbbbbbbbbbbb")),
            phoneTokenId = null,
            phonePrefix = "logb_pat_bbbbbbbbbbbbbbb",
        )
        assertEquals(listOf(false, true), rows.map { it.isThisPhone })
        assertEquals(listOf(true, false), rows.map { it.canRevoke })
    }

    @Test fun `when the id is known the prefix is not needed to mark that row`() {
        val rows = TokenRows.of(listOf(token(1), token(2)), phoneTokenId = 2, phonePrefix = "logb_pat_doesnotmatch")
        assertEquals(listOf(false, true), rows.map { it.isThisPhone })
        assertEquals(listOf(true, false), rows.map { it.canRevoke })
    }

    @Test fun `neither id nor prefix known means no row can be revoked`() {
        val rows = TokenRows.of(listOf(token(1), token(2)), phoneTokenId = null, phonePrefix = null)
        assertEquals(listOf(false, false), rows.map { it.isThisPhone })
        assertEquals(listOf(false, false), rows.map { it.canRevoke }, "unable to identify the phone's own row: hide Revoke everywhere")
    }

    // --- TokensViewModel: real SessionRepository/ActiveAccount/PasswordSession against a MockWebServer,
    // the same idiom SignInViewModelTest and PasswordSessionTest use -- no mocking library in this repo. ---

    private val dispatcher = UnconfinedTestDispatcher()
    private val server = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val factory = ApiFactory { base, tok, jar -> ApiClient.create(base, tok, jar) }
    private val sessions = SessionRepository(serverStore, tokenStore, factory)
    private val accounts = ActiveAccount(sessions, DatabaseProvider(ApplicationProvider.getApplicationContext()), factory)
    private val passwordSession = PasswordSession(sessions, factory)

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        server.start()
    }

    @After fun stop() {
        Dispatchers.resetMain()
        runCatching { server.close() }
    }

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(body).build()

    private suspend fun signedIn(tokenId: Long? = 9) {
        serverStore.write(ServerRecord(server.url("/").toString(), userId = 1, username = "ben", tokenId = tokenId))
        tokenStore.write("logb_pat_phone0000000")
        sessions.restore()
    }

    /** Pumps both the coroutine test scheduler and Robolectric's shadowed main looper. */
    private fun TestScope.drain() {
        advanceUntilIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * `load()`'s network call is real I/O against [server], on a real thread outside the test
     * scheduler's virtual time -- `drain()` alone can return before the response has actually
     * arrived. Polls with a short real sleep between each `drain()` until `loaded` flips.
     */
    private fun TestScope.awaitLoaded(vm: TokensViewModel, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!vm.state.value.loaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            drain()
        }
    }

    /** Same idea as [awaitLoaded], for [TokensViewModel.confirm]'s real round trip through [PasswordSession]. */
    private fun TestScope.awaitNotBusy(vm: TokensViewModel, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.state.value.busy && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            drain()
        }
    }

    @Test fun `an unauthorized response signs the phone out, not offline and no error text`() = runTest(dispatcher) {
        signedIn()
        server.enqueue(json("""{"error":"unauthorized","message":"gone"}""", code = 401))
        val vm = TokensViewModel(accounts, serverStore, tokenStore, sessions, passwordSession)
        awaitLoaded(vm)
        assertTrue(sessions.session.value is Session.SignedOut, "a 401 must sign this phone out, as SyncManager does")
        assertEquals(false, vm.state.value.offline)
        assertEquals(null, vm.state.value.error)
    }

    @Test fun `a non-auth api error surfaces its message and leaves the session signed in`() = runTest(dispatcher) {
        signedIn()
        server.enqueue(json("""{"error":"server_error","message":"boom"}""", code = 500))
        val vm = TokensViewModel(accounts, serverStore, tokenStore, sessions, passwordSession)
        awaitLoaded(vm)
        assertEquals(false, vm.state.value.offline)
        assertEquals("boom", vm.state.value.error)
        assertTrue(sessions.session.value is Session.SignedIn, "a server-side error is not a reason to sign out")
    }

    @Test fun `a real network failure shows offline, not an error message`() = runTest(dispatcher) {
        signedIn()
        server.close() // nothing is listening at this URL any more: a real IOException, no HTTP response at all
        val vm = TokensViewModel(accounts, serverStore, tokenStore, sessions, passwordSession)
        awaitLoaded(vm)
        assertEquals(true, vm.state.value.offline)
        assertEquals(null, vm.state.value.error)
    }

    @Test fun `a malformed response body shows an error instead of crashing, and is not offline`() = runTest(dispatcher) {
        signedIn()
        server.enqueue(MockResponse.Builder().code(200).addHeader("content-type", "text/html").body("<html>not json</html>").build())
        val vm = TokensViewModel(accounts, serverStore, tokenStore, sessions, passwordSession)
        awaitLoaded(vm)
        assertEquals(false, vm.state.value.offline)
        assertTrue(vm.state.value.error != null, "an unclassified failure must still surface a message")
        assertTrue(sessions.session.value is Session.SignedIn, "a parse failure is not a reason to sign out")
    }

    @Test fun `a connection failure on login shows the offline outcome in the password dialog, not raw text`() = runTest(dispatcher) {
        signedIn()
        server.enqueue(json("[]")) // the initial load()
        val vm = TokensViewModel(accounts, serverStore, tokenStore, sessions, passwordSession)
        awaitLoaded(vm)
        server.close() // nothing is listening at this URL any more: login itself never reaches the server

        vm.onName("script")
        vm.askCreate()
        vm.confirm("correct horse")
        awaitNotBusy(vm)

        assertEquals(false, vm.state.value.busy)
        assertEquals(true, vm.state.value.askOffline, "a connection failure must show the same offline outcome as the rest of the screen")
        assertEquals(null, vm.state.value.error, "not the raw exception text")
    }

    @Test fun `a fast double confirm runs the block once`() = runTest(dispatcher) {
        signedIn()
        // `started` proves the single legitimate request really reached the server (not a timing
        // fluke); `release` holds it there so a second `confirm()` is unambiguously "in flight".
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var createCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/auth/tokens" && request.method == "GET" -> json("[]") // the initial load()
                    path == "/api/auth/login" -> json("""{"id":1,"username":"ben"}""", headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly"))
                    path == "/api/auth/tokens" && request.method == "POST" -> {
                        createCount++
                        started.countDown()
                        release.await()
                        json("""{"id":10,"name":"script","prefix":"logb_pat_xy","created_at":"t","token":"logb_pat_xyz"}""", code = 201)
                    }
                    else -> json("{}")
                }
            }
        }

        val vm = TokensViewModel(accounts, serverStore, tokenStore, sessions, passwordSession)
        drain()
        vm.onName("script")
        vm.askCreate()
        vm.confirm("correct horse")
        vm.confirm("correct horse")
        drain()
        assertEquals(true, vm.state.value.busy, "the first confirm is still in flight")

        assertTrue(started.await(5, TimeUnit.SECONDS), "the one legitimate create-token request should reach the server")
        assertEquals(1, createCount, "a second tap while busy must not run the block again")
        // Give a would-be second request every chance to arrive before checking again: with the
        // guard in place there is structurally nothing left in flight to produce one.
        Thread.sleep(200)
        assertEquals(1, createCount, "still only the one request, after waiting a little longer")

        release.countDown()
        Thread.sleep(300)
        drain()

        assertEquals(false, vm.state.value.busy)
        assertEquals("logb_pat_xyz", vm.state.value.fresh)
        assertEquals(1, createCount, "still exactly one token was created")
    }
}
