package dev.logb.android.feature.onboarding

import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.server.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * `MainActivity` has no `NavHost`, so this ViewModel is not recreated when the top-level screen
 * flips between sign-in and change-server -- it must pick up a later server change on its own by
 * collecting the session, not just reading it once at construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SignInViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val serverB = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })
    private val capabilities = ServerCapabilities(serverStore)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        serverB.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        serverB.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    /** Pumps both the coroutine test scheduler and Robolectric's shadowed main looper: `viewModelScope` posts through the latter here, not just the former. */
    private fun TestScope.drain() {
        advanceUntilIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `the session changing from one SignedOut server to another refreshes the sign-in screen`() = runTest(dispatcher) {
        serverStore.write(ServerRecord("http://a.example/", username = "ben"))
        sessions.restore()
        val vm = SignInViewModel(sessions, capabilities)
        drain()
        assertEquals("http://a.example/", vm.state.value.serverUrl)
        assertEquals("ben", vm.state.value.username)

        // The "Change" flow: a new server is remembered (as `checkServer` does, without signing
        // in), and the session moves straight from SignedOut(A) to SignedOut(B).
        val baseB = serverB.url("/").toString()
        serverStore.write(ServerRecord(baseB))
        sessions.restore()
        drain()

        assertEquals(baseB, vm.state.value.serverUrl, "the already-built ViewModel must pick up the new server")
        assertEquals("", vm.state.value.username, "no username is known yet on the new server")

        // And sign-in targets the new server, not the one the ViewModel was first built with.
        vm.onUsernameChange("carol")
        vm.onPasswordChange("secret")
        serverB.enqueue(json("""{"error":"unauthorized","message":"wrong username or password"}""", 401))
        vm.submit()
        drain()

        assertEquals("/api/auth/login", serverB.takeRequest().url.encodedPath)
    }

    /**
     * A submitted sign-in against A can still be in flight when the session moves to a
     * different SignedOut server (a race the disabled "Change" button closes at the UI level,
     * but `submit()` guards against independently, in case the session changes some other way
     * while a request is outstanding).
     */
    @Test
    fun `A's sign-in outcome, arriving after the session already moved to B, does not touch B's screen`() = runTest(dispatcher) {
        val serverA = MockWebServer()
        serverA.start()
        val releaseA = java.util.concurrent.CountDownLatch(1)
        serverA.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                releaseA.await()
                return json("""{"error":"unauthorized","message":"wrong username or password"}""", 401)
            }
        }

        serverStore.write(ServerRecord(serverA.url("/").toString(), username = "ben"))
        sessions.restore()
        val vm = SignInViewModel(sessions, capabilities)
        drain()
        vm.onUsernameChange("ben")
        vm.onPasswordChange("secret")
        vm.submit()
        drain()
        assertEquals(true, vm.state.value.busy, "the request against A is still in flight")

        // The session moves to a different server while A's request is outstanding.
        val baseB = serverB.url("/").toString()
        serverStore.write(ServerRecord(baseB))
        sessions.restore()
        drain()
        assertEquals(baseB, vm.state.value.serverUrl)
        assertEquals(false, vm.state.value.busy, "a session moving to a different server is a clean screen")
        assertEquals(null, vm.state.value.error)

        // Now A's request is allowed to finish (with a failure).
        releaseA.countDown()
        Thread.sleep(500)
        drain()

        assertEquals(baseB, vm.state.value.serverUrl, "still on B")
        assertEquals(false, vm.state.value.busy, "A's outcome must not leave B's screen stuck busy")
        assertEquals(null, vm.state.value.error, "A's error must not appear on B's screen")

        serverA.close()
    }
}
