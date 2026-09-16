package dev.logb.android.core.auth

import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.alerts.ReminderNotificationsClearer
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.core.widget.NoopWidgetRefresher
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRepositoryTest {
    private class FakeWidgetRefresher : WidgetRefresher {
        var debounced = 0
        var immediate = 0
        override fun requestRefresh() { debounced++ }
        override fun requestImmediateRefresh() { immediate++ }
    }

    private class FakeNotificationsClearer : ReminderNotificationsClearer {
        var cleared = 0
        override suspend fun clearAll() { cleared++ }
    }

    private val server = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(body).build()

    private val me = """{"id":1,"username":"ben","is_admin":true,"lang":"en"}"""

    @Test
    fun `sign in logs in with a cookie, mints a token, ends the cookie session, and loads me`() = runTest {
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":"0.7.1","migrations":12}"""))

        val result = repo.signIn(server.url("/").toString(), "ben", "correct horse")
        assertTrue(result.isSuccess, result.toString())

        val login = server.takeRequest()
        assertEquals("/api/auth/login", login.url.encodedPath)
        val mint = server.takeRequest()
        assertEquals("/api/auth/tokens", mint.url.encodedPath)
        assertEquals("logb_session=abc", mint.headers["Cookie"])
        assertNull(mint.headers["Authorization"])
        assertEquals("/api/auth/logout", server.takeRequest().url.encodedPath)
        val meReq = server.takeRequest()
        assertEquals("/api/auth/me", meReq.url.encodedPath)
        assertEquals("Bearer logb_pat_abcdef", meReq.headers["Authorization"])
        assertNull(meReq.headers["Cookie"])

        val s = assertIs<Session.SignedIn>(repo.session.value)
        assertEquals("ben", s.user.username)
        assertEquals("CHF", s.currency)
        assertEquals("logb_pat_abcdef", tokenStore.read())
        assertEquals(9, serverStore.read()!!.tokenId)
    }

    @Test
    fun `sign in remembers the server version`() = runTest {
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":"0.8.0","migrations":14}"""))

        assertTrue(repo.signIn(server.url("/").toString(), "ben", "correct horse").isSuccess)

        assertEquals("0.8.0", serverStore.read()!!.serverVersion)
    }

    @Test
    fun `sign in remembers the server's announced features`() = runTest {
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}"""))

        assertTrue(repo.signIn(server.url("/").toString(), "ben", "correct horse").isSuccess)

        assertEquals(listOf("pairing"), serverStore.read()!!.features)
    }

    @Test
    fun `sign in succeeds when health has no version`() = runTest {
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("", code = 500))

        assertTrue(repo.signIn(server.url("/").toString(), "ben", "correct horse").isSuccess)

        assertNull(serverStore.read()!!.serverVersion)
    }

    @Test
    fun `a failed health check at sign-in keeps the known server version`() = runTest {
        val base = server.url("/").toString()
        serverStore.write(ServerRecord(base, serverVersion = "0.8.0"))
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("", code = 500))

        assertTrue(repo.signIn(base, "ben", "correct horse").isSuccess)

        assertEquals("0.8.0", serverStore.read()!!.serverVersion)
    }

    @Test
    fun `a blank health version with no prior record stores null`() = runTest {
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":""}"""))

        assertTrue(repo.signIn(server.url("/").toString(), "ben", "correct horse").isSuccess)

        assertNull(serverStore.read()!!.serverVersion)
    }

    @Test
    fun `a blank health version at sign-in keeps the known version when a record already exists`() = runTest {
        val base = server.url("/").toString()
        serverStore.write(ServerRecord(base, serverVersion = "0.8.0"))
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":""}"""))

        assertTrue(repo.signIn(base, "ben", "correct horse").isSuccess)

        assertEquals("0.8.0", serverStore.read()!!.serverVersion)
    }

    @Test
    fun `a wrong password surfaces the servers message and stores nothing`() = runTest {
        server.enqueue(json("""{"error":"unauthorized","message":"wrong username or password"}""", code = 401))
        val r = repo.signIn(server.url("/").toString(), "ben", "nope")
        assertEquals("wrong username or password", r.exceptionOrNull()!!.message)
        assertNull(tokenStore.read())
    }

    @Test
    fun `signInWithPairing redeems the code and leaves the same state a password sign-in would`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}"""))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}"""))

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")
        assertTrue(result.isSuccess, result.toString())

        val health = server.takeRequest()
        assertEquals("/api/health", health.url.encodedPath)
        val redeem = server.takeRequest()
        assertEquals("/api/auth/pair/redeem", redeem.url.encodedPath)
        assertEquals("""{"code":"abc123","device_name":"Pixel 8"}""", redeem.body?.utf8())
        assertNull(redeem.headers["Authorization"])
        val meReq = server.takeRequest()
        assertEquals("/api/auth/me", meReq.url.encodedPath)
        assertEquals("Bearer logb_pat_paired", meReq.headers["Authorization"])
        server.takeRequest() // settings
        server.takeRequest() // health (version/features)

        val s = assertIs<Session.SignedIn>(repo.session.value)
        assertEquals("ben", s.user.username)
        assertEquals(1, s.user.id)
        assertEquals("CHF", s.currency)
        assertEquals("logb_pat_paired", tokenStore.read())

        val record = serverStore.read()!!
        assertEquals(server.url("/").toString(), record.serverUrl)
        assertEquals(1, record.userId)
        assertEquals("ben", record.username)
        assertEquals(11, record.tokenId)
        assertEquals("CHF", record.currency)
        assertEquals("0.11.0", record.serverVersion)
        assertEquals(listOf("pairing"), record.features)
    }

    @Test
    fun `signInWithPairing on a 404 reports PairingUnsupported and stores nothing`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"not_found","message":"no such route"}""", code = 404))

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertIs<PairingUnsupported>(result.exceptionOrNull())
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
        assertIs<Session.Loading>(repo.session.value)
    }

    @Test
    fun `signInWithPairing on a 401 reports PairingInvalid and stores nothing`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"unauthorized","message":"invalid or expired code"}""", code = 401))

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertIs<PairingInvalid>(result.exceptionOrNull())
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
        assertIs<Session.Loading>(repo.session.value)
    }

    @Test
    fun `me failing after a successful redeem leaves nothing stored and the session unchanged, and does not revoke through logout`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}""")) // redeem
        server.enqueue(json("", code = 500)) // me fails

        val before = repo.session.value
        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? PairingUnsupported)
        assertNull(result.exceptionOrNull() as? PairingInvalid)
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
        assertEquals(before, repo.session.value)
        // logb's POST /api/auth/logout only ends a cookie session; it never looks at the
        // Authorization header, so it cannot revoke this redeemed bearer token. Confirm nothing
        // beyond health/redeem/me was even attempted.
        assertEquals(3, server.requestCount)
        assertEquals("/api/health", server.takeRequest().url.encodedPath)
        assertEquals("/api/auth/pair/redeem", server.takeRequest().url.encodedPath)
        assertEquals("/api/auth/me", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `signInWithPairing on a 500 from redeem reports a generic error, not unsupported or invalid`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"server_error","message":"something broke"}""", code = 500))

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        val e = result.exceptionOrNull()!!
        assertNull(e as? PairingUnsupported)
        assertNull(e as? PairingInvalid)
        assertEquals("something broke", e.message)
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
    }

    @Test
    fun `a pre-existing signed-in record and session survive a failed pairing unchanged`() = runTest {
        val base = server.url("/").toString()
        val existingRecord = ServerRecord(base, 1, "ben", 9, "CHF", "0.11.0", listOf("pairing"))
        serverStore.write(existingRecord)
        tokenStore.write("logb_pat_existing")
        repo.restore()
        val before = repo.session.value
        assertIs<Session.SignedIn>(before)

        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"unauthorized","message":"invalid or expired code"}""", code = 401)) // redeem fails

        val link = PairingLink(base, "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertIs<PairingInvalid>(result.exceptionOrNull())
        assertEquals(before, repo.session.value)
        assertEquals("logb_pat_existing", tokenStore.read())
        assertEquals(existingRecord, serverStore.read())
    }

    @Test
    fun `signInWithPairing on a network failure stores nothing`() = runTest {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.close() // nothing is listening on this port any more

        val link = PairingLink(deadUrl, "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? PairingUnsupported)
        assertNull(result.exceptionOrNull() as? PairingInvalid)
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
        assertIs<Session.Loading>(repo.session.value)
    }

    @Test
    fun `restore reads the stores without touching the network`() = runTest {
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben", 9, "EUR"))
        tokenStore.write("logb_pat_x")
        repo.restore()
        val s = assertIs<Session.SignedIn>(repo.session.value)
        assertEquals(1, s.user.id)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `restore with a server but no token is signed out, with nothing is first run`() = runTest {
        repo.restore()
        assertIs<Session.NeedsServer>(repo.session.value)
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben"))
        repo.restore()
        val s = assertIs<Session.SignedOut>(repo.session.value)
        assertEquals("ben", s.username)
    }

    @Test
    fun `onUnauthorized drops the token but remembers the server and user`() = runTest {
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()
        repo.onUnauthorized()
        val s = assertIs<Session.SignedOut>(repo.session.value)
        assertEquals("https://logb.example/", s.serverUrl)
        assertEquals("unauthorized", s.reason)
        assertNull(tokenStore.read())
        assertEquals("ben", serverStore.read()!!.username)
    }

    @Test
    fun `onUnauthorized refreshes the widget immediately, after the session is signed out`() = runTest {
        val refresher = FakeWidgetRefresher()
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, refresher)
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()
        repo.onUnauthorized()
        assertIs<Session.SignedOut>(repo.session.value)
        assertEquals(1, refresher.immediate)
        assertEquals(0, refresher.debounced)
    }

    @Test
    fun `sign out revokes the token by id and keeps the server`() = runTest {
        serverStore.write(ServerRecord(server.url("/").toString(), 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()
        server.enqueue(json("{}", code = 204))
        repo.signOut()
        val revoke = server.takeRequest()
        assertEquals("/api/auth/tokens/9", revoke.url.encodedPath)
        assertEquals("DELETE", revoke.method)
        assertIs<Session.SignedOut>(repo.session.value)
        assertNull(tokenStore.read())
    }

    @Test
    fun `sign out refreshes the widget immediately, after the session is signed out`() = runTest {
        val refresher = FakeWidgetRefresher()
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, refresher)
        serverStore.write(ServerRecord(server.url("/").toString(), 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()
        server.enqueue(json("{}", code = 204))
        repo.signOut()
        assertIs<Session.SignedOut>(repo.session.value)
        assertEquals(1, refresher.immediate)
        assertEquals(0, refresher.debounced)
    }

    @Test
    fun `change password patches the own user and reports the server's refusal`() = runTest {
        signInQuietly()
        server.enqueue(json("{}"))
        enqueueSignIn(token = "logb_pat_fresh")
        assertTrue(repo.changePassword("correct horse battery").isSuccess)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/users/1", patch.url.encodedPath)
        assertEquals("""{"password":"correct horse battery"}""", patch.body?.utf8())
        // The server revoked every token with the change: the phone signs in again with the new password.
        val login = server.takeRequest()
        assertEquals("/api/auth/login", login.url.encodedPath)
        assertTrue(login.body!!.utf8().contains("correct horse battery"))
        repeat(5) { server.takeRequest() }
        assertEquals("logb_pat_fresh", tokenStore.read())
        server.enqueue(json("""{"error":"bad_request","message":"password too short"}""", code = 400))
        assertEquals("password too short", repo.changePassword("x").exceptionOrNull()?.message)
    }

    @Test
    fun `sign out everywhere ends browser sessions, then signs this phone out`() = runTest {
        signInQuietly()
        server.enqueue(json("", code = 204))
        server.enqueue(json("", code = 204))
        repo.signOutEverywhere()
        val all = server.takeRequest()
        assertEquals("POST", all.method); assertEquals("/api/auth/logout-all", all.url.encodedPath)
        assertEquals("/api/auth/tokens/9", server.takeRequest().url.encodedPath)
        assertIs<Session.SignedOut>(repo.session.value)
        assertNull(tokenStore.read())
    }

    private fun enqueueSignIn(token: String = "logb_pat_abcdef") {
        server.enqueue(json(me, headers = arrayOf("Set-Cookie" to "logb_session=abc; Path=/; HttpOnly")))
        server.enqueue(json("""{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"$token"}""", code = 201))
        server.enqueue(json("{}"))
        server.enqueue(json(me))
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich","timezone_locked":false}"""))
        server.enqueue(json("""{"status":"ok","version":"0.7.1","migrations":12}"""))
    }

    /** Sign in through the same six responses the sign-in test uses, draining the requests. */
    private suspend fun signInQuietly() {
        enqueueSignIn()
        assertTrue(repo.signIn(server.url("/").toString(), "ben", "correct horse").isSuccess)
        repeat(6) { server.takeRequest() }
    }

    @Test
    fun `checkServer hits health and remembers the address`() = runTest {
        server.enqueue(json("{}"))
        val r = repo.checkServer(server.url("/").toString().removeSuffix("/"))
        assertTrue(r.isSuccess)
        assertEquals("/api/health", server.takeRequest().url.encodedPath)
        assertEquals(server.url("/").toString(), serverStore.read()!!.serverUrl)
        assertIs<Session.SignedOut>(repo.session.value)
    }

    @Test
    fun `sign out clears every posted reminder notification`() = runTest {
        val notifications = FakeNotificationsClearer()
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, NoopWidgetRefresher, notifications)
        serverStore.write(ServerRecord(server.url("/").toString(), 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()
        server.enqueue(json("{}", code = 204))

        repo.signOut()

        assertIs<Session.SignedOut>(repo.session.value)
        assertEquals(1, notifications.cleared)
    }

    @Test
    fun `onUnauthorized clears every posted reminder notification`() = runTest {
        val notifications = FakeNotificationsClearer()
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, NoopWidgetRefresher, notifications)
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()

        repo.onUnauthorized()

        assertIs<Session.SignedOut>(repo.session.value)
        assertEquals(1, notifications.cleared)
    }

    @Test
    fun `sign out clears the server capabilities guard so a later account on the same server is not skipped`() = runTest {
        val capabilities = ServerCapabilities(serverStore)
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, capabilities = capabilities)
        serverStore.write(ServerRecord(server.url("/").toString(), 1, "ben", 9, serverVersion = "0.7.1"))
        tokenStore.write("logb_pat_x")
        repo.restore()
        capabilities.load()
        assertEquals("0.7.1", capabilities.version.value)
        server.enqueue(json("{}", code = 204))

        repo.signOut()

        assertIs<Session.SignedOut>(repo.session.value)
        // A different account's record for the same server must be picked up, not skipped in
        // favour of what the first account last had.
        serverStore.write(ServerRecord(server.url("/").toString(), 2, "ann", 10, serverVersion = "0.9.0"))
        capabilities.load()
        assertEquals("0.9.0", capabilities.version.value)
    }

    @Test
    fun `onUnauthorized clears the server capabilities guard so a later account on the same server is not skipped`() = runTest {
        val capabilities = ServerCapabilities(serverStore)
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, capabilities = capabilities)
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben", 9, serverVersion = "0.7.1"))
        tokenStore.write("logb_pat_x")
        repo.restore()
        capabilities.load()
        assertEquals("0.7.1", capabilities.version.value)

        repo.onUnauthorized()

        assertIs<Session.SignedOut>(repo.session.value)
        serverStore.write(ServerRecord("https://logb.example/", 2, "ann", 10, serverVersion = "0.9.0"))
        capabilities.load()
        assertEquals("0.9.0", capabilities.version.value)
    }

    @Test
    fun `forgetServer clears every posted reminder notification`() = runTest {
        val notifications = FakeNotificationsClearer()
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, NoopWidgetRefresher, notifications)
        serverStore.write(ServerRecord("https://logb.example/", 1, "ben", 9))
        tokenStore.write("logb_pat_x")
        repo.restore()

        repo.forgetServer()

        assertIs<Session.NeedsServer>(repo.session.value)
        assertEquals(1, notifications.cleared)
    }
}
