package dev.logb.android.feature.settings

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.network.ApiClient
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
import kotlin.test.assertTrue

/** Same idiom as `TokensViewModelTest`: a real `SessionRepository`/`ActiveAccount` against a MockWebServer. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServerDigestViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val server = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val factory = ApiFactory { base, tok, jar -> ApiClient.create(base, tok, jar) }
    private val sessions = SessionRepository(serverStore, tokenStore, factory)
    private val accounts = ActiveAccount(sessions, DatabaseProvider(ApplicationProvider.getApplicationContext()), factory)

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        server.start()
    }

    @After fun stop() {
        Dispatchers.resetMain()
        runCatching { server.close() }
    }

    private suspend fun signedIn() {
        serverStore.write(ServerRecord(server.url("/").toString(), userId = 1, username = "ben", tokenId = 9))
        tokenStore.write("logb_pat_phone0000000")
        sessions.restore()
    }

    private fun TestScope.drain() {
        advanceUntilIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * `load()`'s network call is real I/O against [server], on a real thread outside the test
     * scheduler's virtual time -- `drain()` alone can return before the response has actually
     * arrived. Polls with a short real sleep between each `drain()` until `loaded` flips.
     */
    private fun TestScope.awaitLoaded(vm: ServerDigestViewModel, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!vm.state.value.loaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            drain()
        }
    }

    @Test fun `a malformed response body shows an error instead of crashing, and is not offline`() = runTest(dispatcher) {
        signedIn()
        server.enqueue(MockResponse.Builder().code(200).addHeader("content-type", "text/html").body("<html>not json</html>").build())
        val vm = ServerDigestViewModel(accounts, sessions)
        awaitLoaded(vm)
        assertEquals(false, vm.state.value.offline)
        assertTrue(vm.state.value.error != null, "an unclassified failure must still surface a message")
    }
}
