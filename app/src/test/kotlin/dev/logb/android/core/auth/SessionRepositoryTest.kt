package dev.logb.android.core.auth

import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.alerts.ReminderNotificationsClearer
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.core.widget.NoopWidgetRefresher
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
    fun `me failing after a successful redeem leaves nothing stored and the session unchanged, and best-effort revokes the new token`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}""")) // redeem
        server.enqueue(json("", code = 500)) // me fails
        server.enqueue(json("{}", code = 204)) // best-effort self-revoke of the new token

        val before = repo.session.value
        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? PairingUnsupported)
        assertNull(result.exceptionOrNull() as? PairingInvalid)
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
        assertEquals(before, repo.session.value)
        assertEquals(4, server.requestCount)
        assertEquals("/api/health", server.takeRequest().url.encodedPath)
        assertEquals("/api/auth/pair/redeem", server.takeRequest().url.encodedPath)
        assertEquals("/api/auth/me", server.takeRequest().url.encodedPath)
        val revoke = server.takeRequest()
        assertEquals("/api/auth/tokens/11", revoke.url.encodedPath)
        assertEquals("DELETE", revoke.method)
        assertEquals("Bearer logb_pat_paired", revoke.headers["Authorization"])
    }

    @Test
    fun `me failing after a successful redeem reports the same ordinary error even when the best-effort revoke is itself refused by an older server`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":11,"user":{"id":1,"username":"ben","lang":"en"}}""")) // redeem
        server.enqueue(json("", code = 500)) // me fails
        server.enqueue(json("""{"error":"unauthorized","message":"authentication required"}""", code = 401)) // an older server: self-revoke still refused

        val before = repo.session.value
        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? PairingUnsupported)
        assertNull(result.exceptionOrNull() as? PairingInvalid)
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
        assertEquals(before, repo.session.value)
        assertEquals(4, server.requestCount)
        repeat(3) { server.takeRequest() } // health, redeem, me
        val revoke = server.takeRequest()
        assertEquals("/api/auth/tokens/11", revoke.url.encodedPath)
        assertEquals("DELETE", revoke.method)
    }

    @Test
    fun `a failure fetching the new token's own data while replacing an old account leaves that old account signed in and untouched, best-effort revoking only the new token`() = runTest {
        val refresher = FakeWidgetRefresher()
        val notifications = FakeNotificationsClearer()
        val capabilities = ServerCapabilities(serverStore)
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, refresher, notifications, capabilities)
        val oldServer = MockWebServer()
        oldServer.start()
        val oldRecord = ServerRecord(oldServer.url("/").toString(), 1, "ben", 9, serverVersion = "0.7.1")
        serverStore.write(oldRecord)
        tokenStore.write("logb_pat_existing")
        repo.restore()
        val before = repo.session.value
        assertIs<Session.SignedIn>(before)

        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":22,"user":{"id":2,"username":"ann","lang":"en"}}""")) // redeem
        server.enqueue(json("", code = 500)) // me fails
        server.enqueue(json("{}", code = 204)) // best-effort self-revoke of the new token

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? PairingUnsupported)
        assertNull(result.exceptionOrNull() as? PairingInvalid)

        // The fetch for the new token's own data failed before anything about the old account was
        // ever touched: no sign-out, no widget refresh, no notifications cleared, nothing sent to
        // the old server at all.
        assertEquals(0, oldServer.requestCount)
        assertEquals(0, refresher.immediate)
        assertEquals(0, notifications.cleared)

        // The new token, minted by this same failed attempt, gets its own best-effort revoke --
        // with its own bearer, never the old account's.
        server.takeRequest() // health
        server.takeRequest() // redeem
        server.takeRequest() // me
        val revoke = server.takeRequest()
        assertEquals("/api/auth/tokens/22", revoke.url.encodedPath)
        assertEquals("DELETE", revoke.method)
        assertEquals("Bearer logb_pat_paired", revoke.headers["Authorization"])

        // The old account is exactly as it was: same token, same record, still signed in.
        assertEquals("logb_pat_existing", tokenStore.read())
        assertEquals(oldRecord, serverStore.read())
        assertEquals(before, repo.session.value)

        oldServer.close()
    }

    @Test
    fun `a failure fetching the new token's settings while replacing an old account leaves that old account signed in and untouched, best-effort revoking only the new token`() = runTest {
        val refresher = FakeWidgetRefresher()
        val notifications = FakeNotificationsClearer()
        val capabilities = ServerCapabilities(serverStore)
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, refresher, notifications, capabilities)
        val oldServer = MockWebServer()
        oldServer.start()
        val oldRecord = ServerRecord(oldServer.url("/").toString(), 1, "ben", 9, serverVersion = "0.7.1")
        serverStore.write(oldRecord)
        tokenStore.write("logb_pat_existing")
        repo.restore()
        val before = repo.session.value
        assertIs<Session.SignedIn>(before)

        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":22,"user":{"id":2,"username":"ann","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":2,"username":"ann","is_admin":false,"lang":"en"}""")) // me succeeds
        server.enqueue(json("", code = 500)) // settings fails
        server.enqueue(json("{}", code = 204)) // best-effort self-revoke of the new token

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? PairingUnsupported)
        assertNull(result.exceptionOrNull() as? PairingInvalid)

        assertEquals(0, oldServer.requestCount)
        assertEquals(0, refresher.immediate)
        assertEquals(0, notifications.cleared)

        server.takeRequest() // health
        server.takeRequest() // redeem
        server.takeRequest() // me
        server.takeRequest() // settings
        val revoke = server.takeRequest()
        assertEquals("/api/auth/tokens/22", revoke.url.encodedPath)
        assertEquals("DELETE", revoke.method)
        assertEquals("Bearer logb_pat_paired", revoke.headers["Authorization"])

        assertEquals("logb_pat_existing", tokenStore.read())
        assertEquals(oldRecord, serverStore.read())
        assertEquals(before, repo.session.value)

        oldServer.close()
    }

    @Test
    fun `signInWithPairing on a 400 from redeem reports PairingRejected with the server's message and stores nothing`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"bad_request","message":"device_name must not be empty"}""", code = 400))

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        val e = assertIs<PairingRejected>(result.exceptionOrNull())
        assertEquals("device_name must not be empty", e.message)
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
    }

    @Test
    fun `signInWithPairing on a 429 from redeem reports PairingRateLimited and stores nothing`() = runTest {
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"error":"too_many_requests","message":"too many requests"}""", code = 429))

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertIs<PairingRateLimited>(result.exceptionOrNull())
        assertNull(tokenStore.read())
        assertNull(serverStore.read())
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
    fun `signInWithPairing while already signed in signs the old account out, running its cleanup hooks, only once the redeem succeeds`() = runTest {
        val refresher = FakeWidgetRefresher()
        val notifications = FakeNotificationsClearer()
        val capabilities = ServerCapabilities(serverStore)
        val repo = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }, refresher, notifications, capabilities)
        val oldServer = MockWebServer()
        oldServer.start()
        serverStore.write(ServerRecord(oldServer.url("/").toString(), 1, "ben", 9, serverVersion = "0.7.1"))
        tokenStore.write("logb_pat_existing")
        repo.restore()
        capabilities.load()
        assertEquals("0.7.1", capabilities.version.value)
        oldServer.enqueue(json("{}", code = 204)) // the old account's revoke-by-id

        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":22,"user":{"id":2,"username":"ann","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":2,"username":"ann","is_admin":false,"lang":"en"}""")) // me
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")) // settings
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")) // health (version)

        val link = PairingLink(server.url("/").toString(), "abc123")
        val result = repo.signInWithPairing(link, "Pixel 8")

        assertTrue(result.isSuccess, result.toString())
        assertEquals("/api/auth/tokens/9", oldServer.takeRequest().url.encodedPath, "the old account's token was revoked")
        val s = assertIs<Session.SignedIn>(repo.session.value)
        assertEquals("ann", s.user.username)
        assertEquals("logb_pat_paired", tokenStore.read())
        assertEquals(1, refresher.immediate, "the old account's widget refresh ran")
        assertEquals(1, notifications.cleared, "the old account's notifications were cleared")
        // The old account's capabilities guard was cleared, or a caller's own capabilities.load()
        // right after this call (as PairingConfirmViewModel/ServerViewModel both do) would still
        // skip the read and keep the old account's cached version.
        capabilities.load()
        assertEquals("0.11.0", capabilities.version.value)

        oldServer.close()
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

    @Test
    fun `the token write and the server-record write land together even when cancelled between them`() = runTest {
        // A gate around the (fake) token write, so the test can cancel the launching coroutine
        // while finishSigningIn() is suspended inside its withContext(NonCancellable) block --
        // the only way to actually exercise the guard: a plain sequential write/write, with
        // nothing to preempt it, would pass this assertion even without NonCancellable.
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gatingTokenStore = object : TokenStore {
            override suspend fun read(): String? = tokenStore.read()
            override suspend fun write(token: String) {
                started.complete(Unit)
                gate.await()
                tokenStore.write(token)
            }
            override suspend fun clear() = tokenStore.clear()
        }
        val repo = SessionRepository(serverStore, gatingTokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })
        enqueueSignIn()

        val job = launch { repo.signIn(server.url("/").toString(), "ben", "correct horse") }
        started.await() // finishSigningIn is now inside withContext(NonCancellable), paused on the gate
        job.cancel() // requests cancellation of the coroutine that is sitting inside that block
        gate.complete(Unit) // let the write proceed; NonCancellable must let it run to completion anyway
        job.join()

        assertEquals("logb_pat_abcdef", tokenStore.read())
        assertEquals(9, serverStore.read()?.tokenId)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `a second sign-in waits for the mutex, never running concurrently with the first`() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        // A fake LogbApi (NoopLogbApi below), not MockWebServer: this needs to prove a genuine
        // *absence* -- that the second call makes not even its own first request while the first
        // is still in flight -- and a real network round trip's own wall-clock time would turn
        // that into a race (a check made "too early" would pass for the wrong reason). Deterministic,
        // in-memory suspend functions plus UnconfinedTestDispatcher's eager-until-first-real-suspension
        // scheduling instead make the assertion mean what it says: if the mutex did not block
        // job2, there is nothing left to explain a still-zero call count.
        var pairHealthCalls = 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val benBase = "https://ben.example/"
        val pairBase = "https://pair.example/"
        val benApi = object : NoopLogbApi() {
            override suspend fun login(body: dev.logb.android.core.network.dto.Credentials) = dev.logb.android.core.network.dto.User(1, "ben")
            override suspend fun createToken(body: dev.logb.android.core.network.dto.NewToken): dev.logb.android.core.network.dto.NewApiToken {
                // Paused here, inside signIn()'s own critical section, mutex still held -- exactly
                // the point at which a second, concurrent call must not be able to run alongside it.
                started.complete(Unit)
                gate.await()
                return dev.logb.android.core.network.dto.NewApiToken(id = 9, name = "LogB Android", prefix = "logb_pat_ab", createdAt = "x", token = "logb_pat_abcdef")
            }
            override suspend fun logout(): retrofit2.Response<Unit> = retrofit2.Response.success(Unit)
            override suspend fun me() = dev.logb.android.core.network.dto.User(1, "ben")
            override suspend fun settings() = dev.logb.android.core.network.dto.Settings(currency = "CHF")
            override suspend fun healthInfo() = dev.logb.android.core.network.dto.HealthInfo(version = "0.7.1")
            override suspend fun revokeToken(id: Long): retrofit2.Response<Unit> = retrofit2.Response.success(Unit)
        }
        val pairApi = object : NoopLogbApi() {
            override suspend fun health(): retrofit2.Response<Unit> {
                pairHealthCalls++
                return retrofit2.Response.success(Unit)
            }
            override suspend fun redeemPairing(body: dev.logb.android.core.network.dto.PairRedeem) =
                dev.logb.android.core.network.dto.PairRedeemed(token = "logb_pat_paired", tokenId = 22, user = dev.logb.android.core.network.dto.User(2, "ann"))
            override suspend fun me() = dev.logb.android.core.network.dto.User(2, "ann")
            override suspend fun settings() = dev.logb.android.core.network.dto.Settings(currency = "CHF")
            override suspend fun healthInfo() = dev.logb.android.core.network.dto.HealthInfo(version = "0.11.0", features = listOf("pairing"))
        }
        val apiFactory = ApiFactory { base, _, _ ->
            when (base) {
                benBase -> benApi
                pairBase -> pairApi
                else -> error("unexpected base $base")
            }
        }
        val localServerStore = FakeServerStore()
        val localTokenStore = FakeTokenStore()
        val repo = SessionRepository(localServerStore, localTokenStore, apiFactory)

        val job1 = launch { repo.signIn(benBase, "ben", "correct horse") }
        started.await() // signIn is now paused inside createToken(), mutex still held

        val job2 = launch { repo.signInWithPairing(PairingLink(pairBase, "abc123"), "Pixel 8") }
        assertEquals(0, pairHealthCalls, "the second call must wait for the mutex -- it must not have made even its own first request yet")

        gate.complete(Unit) // let the first sign-in proceed; the second was only ever waiting on the mutex, not on this gate
        job1.join()
        job2.join()

        // Both calls ran to completion, one strictly after the other -- the session, the token
        // and the stored record all agree on the same (second, later) account, never a mix of
        // the two calls' writes.
        assertEquals(1, pairHealthCalls)
        val signedIn = assertIs<Session.SignedIn>(repo.session.value)
        assertEquals("ann", signedIn.user.username)
        assertEquals("logb_pat_paired", localTokenStore.read())
        assertEquals(22L, localServerStore.read()?.tokenId)
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

    /**
     * Every [dev.logb.android.core.network.LogbApi] method, each refusing to be called -- for a
     * test that only cares about a handful of them and wants no real network at all (see the
     * mutex test above). Subclasses override just the methods their scenario actually reaches.
     */
    private abstract class NoopLogbApi : dev.logb.android.core.network.LogbApi {
        private fun unused(): Nothing = error("not stubbed by this fake")
        override suspend fun health(): retrofit2.Response<Unit> = unused()
        override suspend fun healthInfo(): dev.logb.android.core.network.dto.HealthInfo = unused()
        override suspend fun logoutAll(): retrofit2.Response<Unit> = unused()
        override suspend fun updateUser(id: Long, body: dev.logb.android.core.network.dto.UserPatch): retrofit2.Response<Unit> = unused()
        override suspend fun login(body: dev.logb.android.core.network.dto.Credentials): dev.logb.android.core.network.dto.User = unused()
        override suspend fun me(): dev.logb.android.core.network.dto.User = unused()
        override suspend fun createToken(body: dev.logb.android.core.network.dto.NewToken): dev.logb.android.core.network.dto.NewApiToken = unused()
        override suspend fun redeemPairing(body: dev.logb.android.core.network.dto.PairRedeem): dev.logb.android.core.network.dto.PairRedeemed = unused()
        override suspend fun revokeToken(id: Long): retrofit2.Response<Unit> = unused()
        override suspend fun logout(): retrofit2.Response<Unit> = unused()
        override suspend fun settings(): dev.logb.android.core.network.dto.Settings = unused()
        override suspend fun bootstrap(): dev.logb.android.core.network.dto.BootstrapResult = unused()
        override suspend fun pull(since: Long, epoch: String?, limit: Int): dev.logb.android.core.network.dto.PullResult = unused()
        override suspend fun push(body: dev.logb.android.core.network.dto.PushBody): dev.logb.android.core.network.dto.PushResult = unused()
        override suspend fun createObject(body: dev.logb.android.core.network.dto.ObjectInput): dev.logb.android.core.network.dto.ObjectDto = unused()
        override suspend fun createActivity(objectId: Long, body: dev.logb.android.core.network.dto.ActivityInput): dev.logb.android.core.network.dto.ActivityDto = unused()
        override suspend fun createReminder(objectId: Long, body: dev.logb.android.core.network.dto.ReminderInput): dev.logb.android.core.network.dto.ReminderDto = unused()
        override suspend fun createType(body: dev.logb.android.core.network.dto.TypeBody): dev.logb.android.core.network.dto.TypeDto = unused()
        override suspend fun downloadOriginal(fileId: Long): okhttp3.ResponseBody = unused()
        override suspend fun downloadThumb(fileId: Long): okhttp3.ResponseBody = unused()
        override suspend fun export(objectId: Long): okhttp3.ResponseBody = unused()
        override suspend fun upload(objectId: Long, file: okhttp3.MultipartBody.Part, fields: Map<String, okhttp3.RequestBody>): dev.logb.android.core.network.dto.AttachmentDto = unused()
        override suspend fun listTokens(): List<dev.logb.android.core.network.dto.ApiToken> = unused()
        override suspend fun exportAll(): okhttp3.ResponseBody = unused()
        override suspend fun importZip(body: okhttp3.RequestBody): dev.logb.android.core.network.dto.ImportCounts = unused()
        override suspend fun notifications(): dev.logb.android.core.network.dto.ServerNotifications = unused()
        override suspend fun saveNotifications(body: dev.logb.android.core.network.dto.ServerNotificationsIn): dev.logb.android.core.network.dto.ServerNotifications = unused()
        override suspend fun testNotifications(): dev.logb.android.core.network.dto.NotificationTest = unused()
    }
}
