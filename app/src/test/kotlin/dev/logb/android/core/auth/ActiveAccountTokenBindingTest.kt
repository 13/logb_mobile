package dev.logb.android.core.auth

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.network.ApiClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/** A client [ActiveAccount] built for one account never carries another account's token. */
@RunWith(RobolectricTestRunner::class)
class ActiveAccountTokenBindingTest {
    private val server = MockWebServer().apply { start() }
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
    private val providers = mutableListOf<() -> String?>()
    private val accounts = ActiveAccount(
        sessions,
        DatabaseProvider(ApplicationProvider.getApplicationContext()),
        ApiFactory { b, t, j -> providers += t; ApiClient.create(b, t, j) },
    )

    @After fun stop() = server.close()

    private fun signIn(base: String, userId: Long, token: String) = runBlocking {
        serverStore.write(ServerRecord(base, userId, "user$userId", 9))
        tokenStore.write(token)
        sessions.restore()
    }

    private fun sendWith(http: okhttp3.OkHttpClient): String? {
        server.enqueue(MockResponse.Builder().code(204).build())
        http.newCall(Request.Builder().url(server.url("/api/files/1")).build()).execute().close()
        return server.takeRequest().headers["Authorization"]
    }

    private fun switchesAccount(nextBase: String, nextUserId: Long) {
        val base = server.url("/").toString()
        signIn(base, 1, "logb_pat_a")
        val stale = accounts.bound()!!
        val staleProvider = providers.single()
        val staleHttp = accounts.httpClient!!
        assertEquals("logb_pat_a", staleProvider())
        assertEquals("Bearer logb_pat_a", sendWith(staleHttp))

        signIn(nextBase, nextUserId, "logb_pat_b")

        assertNull(staleProvider(), "the old account's client gets no token once another account is signed in")
        assertNull(sendWith(staleHttp), "the old account's image client sends no bearer either")
        val fresh = accounts.bound()!!
        assertNotSame(stale.api, fresh.api)
        assertEquals("logb_pat_b", providers.last()())
    }

    @Test fun `a stale client asking for a token after a switch on the same server gets null`() =
        switchesAccount(server.url("/").toString(), nextUserId = 2)

    @Test fun `a stale client asking for a token after a switch to another server gets null`() =
        switchesAccount("https://other.example/", nextUserId = 1)

    @Test fun `a stale client asking for a token after sign-out gets null`() {
        signIn(server.url("/").toString(), 1, "logb_pat_a")
        accounts.bound()
        val provider = providers.single()
        runBlocking { sessions.onUnauthorized("logb_pat_a") }
        assertNull(provider())
    }
}
