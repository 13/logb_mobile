package dev.logb.android.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun api(token: String? = "logb_pat_x") = ApiClient.create(server.url("/").toString(), { token })

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    @Test
    fun `every request carries the bearer token, the api prefix and a user agent`() = runTest {
        server.enqueue(json("""{"id":1,"username":"ben","is_admin":true,"lang":"en"}"""))
        val me = api().me()
        assertEquals("ben", me.username)
        val r = server.takeRequest()
        assertEquals("/api/auth/me", r.url.encodedPath)
        assertEquals("Bearer logb_pat_x", r.headers["Authorization"])
        assertTrue(r.headers["User-Agent"]!!.startsWith(ApiClient.USER_AGENT_PREFIX))
    }

    @Test
    fun `no token means no authorization header`() = runTest {
        server.enqueue(json("""{"id":1,"username":"ben"}"""))
        api(token = null).me()
        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an error body becomes an ApiException with the servers code and message`() = runTest {
        server.enqueue(json("""{"error":"bad_request","message":"name is required"}""", code = 400))
        val e = assertFailsWith<ApiException> { api().me() }
        assertEquals(400, e.status)
        assertEquals("bad_request", e.code)
        assertEquals("name is required", e.message)
    }

    @Test
    fun `a 401 is its own exception and a 410 too`() = runTest {
        server.enqueue(json("""{"error":"unauthorized","message":"authentication required"}""", code = 401))
        assertFailsWith<UnauthorizedException> { api().me() }
        server.enqueue(json("""{"error":"gone","message":"re-bootstrap"}""", code = 410))
        assertFailsWith<GoneException> { api().pull(since = 5, epoch = "e", limit = 10) }
    }

    @Test
    fun `an error without a json body still names the status`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())
        val e = assertFailsWith<ApiException> { api().me() }
        assertEquals(502, e.status)
        assertEquals("http_502", e.code)
    }

    @Test
    fun `pull parses entity_id and keeps the double-encoded value verbatim`() = runTest {
        server.enqueue(
            json(
                """{"changes":[{"seq":7,"entity":"object","entity_uuid":"u","op":"set","field":"name",
                "value":"\"Golf VII\"","edited_at":"2026-09-01T00:00:00.000Z","device_id":"rest","entity_id":42}],
                "next_seq":7,"complete":true,"server_time":"2026-09-01T00:00:01.000Z","epoch":"e"}""",
            ),
        )
        val p = api().pull(0, null, 500)
        val r = server.takeRequest()
        assertEquals("/api/sync/pull?since=0&limit=500", r.url.encodedPath + "?" + r.url.encodedQuery)
        assertEquals(42, p.changes[0].entityId)
        assertEquals("\"Golf VII\"", p.changes[0].value)
        assertEquals("e", p.epoch)
    }

    @Test
    fun `unknown fields from a newer server are ignored`() = runTest {
        server.enqueue(json("""{"id":1,"username":"ben","is_admin":false,"lang":"de","new_field":123}"""))
        assertEquals("de", api().me().lang)
    }

    @Test
    fun `base urls are normalised to https and one trailing slash`() {
        assertEquals("https://logb.example/", ApiClient.normalizeBaseUrl("logb.example"))
        assertEquals("https://logb.example/", ApiClient.normalizeBaseUrl(" https://logb.example// "))
        assertEquals("http://localhost:8080/", ApiClient.normalizeBaseUrl("http://localhost:8080"))
    }
}
