package dev.logb.android.feature.onboarding

import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.PairError
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.server.ServerCapabilities
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
 * The in-app "Scan QR code" flow ([ServerViewModel.onScanned]) -- unlike a `logb://pair` deep
 * link ([dev.logb.android.feature.pairing.PairingConfirmViewModel]), this one redeems and signs
 * in directly: the person just tapped the button themselves, on this, the signed-out server
 * screen, so it needs no extra confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val server = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })
    private val capabilities = ServerCapabilities(serverStore)
    private val viewModel = ServerViewModel(sessions, capabilities)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun link(code: String = "abc123", base: String = server.url("/").toString()) =
        "logb://pair?server=${java.net.URLEncoder.encode(base, "UTF-8")}&code=$code"

    /** Pumps both the coroutine test scheduler and Robolectric's shadowed main looper: `viewModelScope` posts through the latter here, not just the former. */
    private fun TestScope.drain() {
        advanceUntilIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * `onScanned`'s redeem runs real OkHttp/MockWebServer I/O on its own thread pool -- once a
     * suspend call resumes on that background thread, `UnconfinedTestDispatcher` keeps running
     * the rest of the coroutine right there rather than queuing it for `advanceUntilIdle()` to
     * pump, so a single `drain()` right after `onScanned()` can observe the redeem still in
     * flight. This polls with short real sleeps until [check] holds (or the timeout elapses, so a
     * genuine bug still fails with a clear assertion rather than hanging).
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
    fun `a scanned text that is not a pairing link reports pair_not_a_code and touches no server`() = runTest(dispatcher) {
        viewModel.onScanned("not a QR code at all")
        drain()

        assertEquals(PairError.NotACode, viewModel.state.value.pairError)
        assertEquals(false, viewModel.state.value.pairing)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a scanned code whose http address is refused reports pair_unsafe_address, not pair_not_a_code, and touches no server`() = runTest(dispatcher) {
        val uri = "logb://pair?server=" + java.net.URLEncoder.encode("http://example.org", "UTF-8") + "&code=abc123"

        viewModel.onScanned(uri)
        drain()

        assertEquals(dev.logb.android.core.auth.PairError.UnsafeAddress, viewModel.state.value.pairError)
        assertEquals(false, viewModel.state.value.pairing)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 404 from redeem reports pair_unsupported`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"not_found","message":"no such route"}""", 404))

        viewModel.onScanned(link())
        awaitUntil { viewModel.state.value.pairError != null }

        assertEquals(PairError.Unsupported, viewModel.state.value.pairError)
        assertEquals(false, viewModel.state.value.pairing)
    }

    @Test
    fun `a 401 from redeem reports pair_invalid`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"unauthorized","message":"invalid or expired code"}""", 401))

        viewModel.onScanned(link())
        awaitUntil { viewModel.state.value.pairError != null }

        assertEquals(PairError.Invalid, viewModel.state.value.pairError)
    }

    @Test
    fun `a 400 from redeem reports the server's own rejection message`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"bad_request","message":"device_name must not be empty"}""", 400))

        viewModel.onScanned(link())
        awaitUntil { viewModel.state.value.pairError != null }

        val error = assertIs<PairError.Rejected>(viewModel.state.value.pairError)
        assertEquals("device_name must not be empty", error.message)
    }

    @Test
    fun `a 429 from redeem reports pair_rate_limited`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"too_many_requests","message":"too many requests"}""", 429))

        viewModel.onScanned(link())
        awaitUntil { viewModel.state.value.pairError != null }

        assertEquals(PairError.RateLimited, viewModel.state.value.pairError)
    }

    @Test
    fun `a network failure reports the unreachable outcome`() = runTest(dispatcher) {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.close()

        viewModel.onScanned(link(base = deadUrl))
        awaitUntil { viewModel.state.value.pairError != null }

        assertEquals(PairError.Unreachable, viewModel.state.value.pairError)
    }

    @Test
    fun `success clears pairing and error, stores the session, and refreshes capabilities`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":1,"username":"ben","is_admin":false,"lang":"en"}""")) // me
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")) // settings
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")) // health (version)

        viewModel.onScanned(link())
        awaitUntil { !viewModel.state.value.pairing }

        assertEquals(false, viewModel.state.value.pairing)
        assertNull(viewModel.state.value.pairError)
        assertEquals("logb_pat_paired", tokenStore.read())
        // capabilities.load() ran after the redeem: the freshly-stored features already show up.
        assertTrue(capabilities.current.value.pairing)
    }

    @Test
    fun `a denied camera permission reports pair_camera_permission_denied and touches no server`() = runTest(dispatcher) {
        viewModel.onCameraPermissionDenied()
        drain()

        assertEquals(PairError.CameraPermissionDenied, viewModel.state.value.pairError)
        assertEquals(false, viewModel.state.value.pairing)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `submit is ignored while a scan is redeeming, so it cannot overwrite the fresh record`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health (scan)
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":1,"username":"ben","is_admin":false,"lang":"en"}""")) // me
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")) // settings
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")) // health (version)

        viewModel.onUrlChange(server.url("/").toString())
        viewModel.onScanned(link())
        viewModel.submit() // must be a no-op: state.pairing is already true
        awaitUntil { !viewModel.state.value.pairing }

        assertEquals(false, viewModel.state.value.checking, "submit() must never have started checkServer() while a scan was redeeming")
        assertEquals(5, server.requestCount, "only the scan's own five requests -- none from submit()'s checkServer health call")
    }

    @Test
    fun `a second scan while one is already redeeming is ignored`() = runTest(dispatcher) {
        server.enqueue(json("{}")) // health -- a second dispatch here would prove the guard failed
        server.enqueue(json("""{"error":"unauthorized","message":"invalid or expired code"}""", 401))

        viewModel.onScanned(link("first"))
        viewModel.onScanned(link("second")) // must be a no-op: `pairing` is already true
        awaitUntil { !viewModel.state.value.pairing }

        assertEquals(2, server.requestCount, "only the first scan's two requests (health, redeem) should have gone out")
    }
}
