package dev.logb.android.feature.pairing

import android.content.Intent
import android.net.Uri
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.PairError
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.feature.share.ShareInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `logb://pair` deep link must never sign this phone in silently -- see
 * [PairingConfirmViewModel]'s own doc. These tests cover the confirmation itself, not the redeem
 * mechanics ([dev.logb.android.core.auth.SessionRepositoryTest] already covers those).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PairingConfirmViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val server = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })
    private val capabilities = ServerCapabilities(serverStore)
    private val shareInbox = ShareInbox()

    // Built in setUp(), not as a field initializer: the constructor's init block launches a
    // collector on viewModelScope right away, which needs Dispatchers.Main already pointed at
    // the test dispatcher -- exactly what setUp() below arranges first.
    private lateinit var viewModel: PairingConfirmViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.start()
        viewModel = PairingConfirmViewModel(shareInbox, sessions, capabilities)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun pairIntent(server: String = this.server.url("/").toString(), code: String = "abc123") =
        Intent(Intent.ACTION_VIEW, Uri.parse("logb://pair?server=${java.net.URLEncoder.encode(server, "UTF-8")}&code=$code"))

    private fun TestScope.drain() {
        advanceUntilIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * `confirm()`'s redeem runs real OkHttp/MockWebServer I/O on its own thread pool -- once a
     * suspend call resumes on that background thread, `UnconfinedTestDispatcher` keeps running
     * the rest of the coroutine right there rather than queuing it for `advanceUntilIdle()` to
     * pump, so a single `drain()` right after `confirm()` can observe the redeem still in flight.
     * This polls with short real sleeps until [check] holds (or the timeout elapses, so a genuine
     * bug still fails with a clear assertion rather than hanging).
     */
    private fun TestScope.awaitUntil(timeoutMs: Long = 5_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            drain()
            if (check()) return
            if (System.currentTimeMillis() >= deadline) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `a deep link arriving signed out produces a sign-in prompt, not a sign-in`() = runTest(dispatcher) {
        shareInbox.offer(pairIntent())
        drain()

        val prompt = assertIs<PairingPrompt.SignIn>(viewModel.state.value.prompt)
        assertEquals(server.url("/").host + ":" + server.url("/").port, prompt.host)
        assertEquals(0, server.requestCount, "asking must not itself touch the server")
    }

    @Test
    fun `cancelling takes the link out of the inbox`() = runTest(dispatcher) {
        shareInbox.offer(pairIntent())
        drain()

        viewModel.cancel()
        drain()

        assertNull(shareInbox.pendingPairing.value)
    }

    @Test
    fun `cancelling drops the link -- nothing is redeemed`() = runTest(dispatcher) {
        shareInbox.offer(pairIntent())
        drain()

        viewModel.cancel()
        drain()

        assertEquals(null, viewModel.state.value.prompt)
        assertEquals(0, server.requestCount)
        assertIs<Session.Loading>(sessions.session.value)
    }

    @Test
    fun `confirming signed out redeems the code and signs in`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":1,"username":"ben","is_admin":false,"lang":"en"}""")) // me
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")) // settings
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")) // health (version)

        shareInbox.offer(pairIntent())
        drain()
        viewModel.confirm()
        awaitUntil { !viewModel.state.value.busy }

        assertEquals(null, viewModel.state.value.prompt)
        assertEquals(false, viewModel.state.value.busy)
        assertIs<Session.SignedIn>(sessions.session.value)
        assertEquals("logb_pat_paired", tokenStore.read())
        assertTrue(capabilities.current.value.pairing, "capabilities.load() ran after a successful pairing")
    }

    @Test
    fun `a deep link arriving already signed in produces a replace prompt naming both hosts`() = runTest(dispatcher) {
        val oldBase = "https://old.example.org/"
        serverStore.write(ServerRecord(oldBase, 1, "ben", 9))
        tokenStore.write("logb_pat_existing")
        sessions.restore()

        shareInbox.offer(pairIntent())
        drain()

        val prompt = assertIs<PairingPrompt.Replace>(viewModel.state.value.prompt)
        assertEquals("old.example.org", prompt.fromHost)
        assertEquals(server.url("/").host + ":" + server.url("/").port, prompt.toHost)
    }

    @Test
    fun `confirming while signed in signs out first, then pairs`() = runTest(dispatcher) {
        val oldServer = MockWebServer()
        oldServer.start()
        serverStore.write(ServerRecord(oldServer.url("/").toString(), 1, "ben", 9))
        tokenStore.write("logb_pat_existing")
        sessions.restore()
        oldServer.enqueue(json("", code = 204)) // signOut()'s revoke-by-id, against the OLD server

        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":22,"user":{"id":2,"username":"ann","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":2,"username":"ann","is_admin":false,"lang":"en"}""")) // me
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")) // settings
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")) // health (version)

        shareInbox.offer(pairIntent())
        drain()
        viewModel.confirm()
        awaitUntil { !viewModel.state.value.busy }

        assertEquals("/api/auth/tokens/9", oldServer.takeRequest().url.encodedPath, "signOut() revoked the old token before the new sign-in")
        assertEquals(5, server.requestCount, "the new sign-in's own five requests, none of them the old server's revoke")
        val signedIn = assertIs<Session.SignedIn>(sessions.session.value)
        assertEquals("ann", signedIn.user.username)
        assertEquals(server.url("/").toString(), signedIn.serverUrl)

        oldServer.close()
    }

    @Test
    fun `an unsupported server reports pair_unsupported and leaves the prompt closed`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"not_found","message":"no such route"}""", 404))

        shareInbox.offer(pairIntent())
        drain()
        viewModel.confirm()
        awaitUntil { viewModel.state.value.error != null }

        assertEquals(PairError.Unsupported, viewModel.state.value.error)
        assertEquals(null, viewModel.state.value.prompt)
    }

    @Test
    fun `an invalid code reports pair_invalid`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"unauthorized","message":"invalid or expired code"}""", 401))

        shareInbox.offer(pairIntent())
        drain()
        viewModel.confirm()
        awaitUntil { viewModel.state.value.error != null }

        assertEquals(PairError.Invalid, viewModel.state.value.error)
    }

    @Test
    fun `hostOf drops a default port but keeps a non-default one`() {
        assertEquals("logb.example.org", PairingConfirmViewModel.hostOf("https://logb.example.org/"))
        assertEquals("192.168.1.5:8080", PairingConfirmViewModel.hostOf("http://192.168.1.5:8080/"))
        assertEquals("localhost:8090", PairingConfirmViewModel.hostOf("http://localhost:8090/"))
    }
}
