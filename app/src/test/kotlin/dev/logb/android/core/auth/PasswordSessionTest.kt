package dev.logb.android.core.auth

import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.dto.NewToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordSessionTest {
    private val server = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val factory = ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }
    private val sessions = SessionRepository(serverStore, tokenStore, factory)

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(body).build()

    private suspend fun signedIn() {
        serverStore.write(ServerRecord(server.url("/").toString(), userId = 1, username = "ben", tokenId = 9))
        tokenStore.write("logb_pat_phone")
        sessions.restore()
    }

    @Test fun `logs in with the password, runs the block on the cookie, and logs out`() = runTest {
        signedIn()
        server.enqueue(json("""{"id":1,"username":"ben"}""", headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":10,"name":"script","prefix":"logb_pat_xy","created_at":"t","token":"logb_pat_xyz"}""", code = 201))
        server.enqueue(json("{}"))

        val result = PasswordSession(sessions, factory).run("correct horse") { api -> api.createToken(NewToken("script")) }

        assertEquals("logb_pat_xyz", result.getOrThrow().token)
        val login = server.takeRequest()
        assertEquals("/api/auth/login", login.url.encodedPath)
        assertTrue(login.body!!.utf8().contains("\"username\":\"ben\""))
        val create = server.takeRequest()
        assertEquals("logb_session=abc", create.headers["Cookie"])
        assertEquals(null, create.headers["Authorization"], "the phone's token must not ride along")
        assertEquals("/api/auth/logout", server.takeRequest().url.encodedPath)
        assertEquals("logb_pat_phone", tokenStore.read(), "the phone's own token is untouched")
    }

    @Test fun `a wrong password fails without running the block`() = runTest {
        signedIn()
        server.enqueue(json("""{"error":"unauthorized","message":"wrong username or password"}""", code = 401))
        var ran = false
        val result = PasswordSession(sessions, factory).run("nope") { ran = true }
        assertTrue(result.isFailure)
        assertEquals(false, ran)
        assertEquals(1, server.requestCount)
    }

    /**
     * Old code ran the whole body through `kotlin.runCatching`, which also wraps a
     * [CancellationException] into a failed [Result] -- the coroutine never actually cancels. With
     * the fix, `run` rethrows it, and [caught] below only ever holds it because that `catch` block
     * ran at all. The [AtomicReference] is needed because the block's suspension and the server's
     * real HTTP handling happen on different threads, not the test dispatcher's virtual time.
     */
    @Test fun `cancelling the caller while the block is suspended rethrows CancellationException and still logs out`() = runTest {
        signedIn()
        server.enqueue(json("""{"id":1,"username":"ben"}""", headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("{}")) // the logout the NonCancellable finally sends once cancellation unwinds

        val entered = CompletableDeferred<Unit>()
        val caught = AtomicReference<Throwable>()
        val job = launch {
            try {
                PasswordSession(sessions, factory).run("correct horse") { _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: Throwable) {
                caught.set(e)
            }
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue(caught.get() is CancellationException, "run must rethrow cancellation rather than wrap it in a failed Result")
        assertEquals("/api/auth/login", server.takeRequest(2, TimeUnit.SECONDS)?.url?.encodedPath)
        val logout = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/auth/logout", logout?.url?.encodedPath, "logout must still run even though the caller was cancelled mid-call")
    }
}
