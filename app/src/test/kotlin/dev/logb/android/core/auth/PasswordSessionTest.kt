package dev.logb.android.core.auth

import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.dto.NewToken
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
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
}
