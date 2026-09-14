package dev.logb.android.core.sync

import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.UnauthorizedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {
    private class FakeConnectivity(online: Boolean) : ConnectivityMonitor {
        override val isOnline = MutableStateFlow(online)
        override val isUnmetered = true
    }

    private val serverStore = FakeServerStore(ServerRecord("https://x/", 1, "ben", 9))
    private val tokenStore = FakeTokenStore("t")
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { b, t, j -> ApiClient.create(b, t, j) })

    @Test fun `a successful run ends idle with a timestamp`() = runTest {
        sessions.restore()
        val m = SyncManager(sessions, FakeConnectivity(true), { SyncRunner { } }, backgroundScope)
        assertTrue(m.syncNow().isSuccess)
        assertIs<SyncStatus.Idle>(m.status.value)
    }

    @Test fun `two overlapping requests run one at a time`() = runTest {
        sessions.restore()
        val gate = CompletableDeferred<Unit>()
        var running = 0; var maxRunning = 0
        val m = SyncManager(sessions, FakeConnectivity(true), { SyncRunner { running++; maxRunning = maxOf(maxRunning, running); gate.await(); running-- } }, backgroundScope)
        val a = async { m.syncNow() }
        val b = async { m.syncNow() }
        gate.complete(Unit)
        a.await(); b.await()
        assertEquals(1, maxRunning)
    }

    @Test fun `a 401 signs the session out`() = runTest {
        sessions.restore()
        val m = SyncManager(sessions, FakeConnectivity(true), { SyncRunner { throw UnauthorizedException("gone") } }, backgroundScope)
        m.syncNow()
        assertIs<SyncStatus.SignedOut>(m.status.value)
        assertIs<Session.SignedOut>(sessions.session.value)
    }

    @Test fun `no connection is offline without calling the runner, and an IOException is offline too`() = runTest {
        sessions.restore()
        var called = false
        val m = SyncManager(sessions, FakeConnectivity(false), { SyncRunner { called = true } }, backgroundScope)
        m.syncNow()
        assertIs<SyncStatus.Offline>(m.status.value); assertTrue(!called)
        val m2 = SyncManager(sessions, FakeConnectivity(true), { SyncRunner { throw IOException("reset") } }, backgroundScope)
        m2.syncNow()
        assertIs<SyncStatus.Offline>(m2.status.value)
    }

    @Test fun `a connection coming back with writes waiting triggers a run`() = runTest {
        sessions.restore()
        val connectivity = FakeConnectivity(false)
        var runs = 0
        val m = SyncManager(sessions, connectivity, { SyncRunner { runs++ } }, backgroundScope, pendingCount = { 2 }, debounceMs = 0)
        connectivity.isOnline.value = true
        kotlinx.coroutines.delay(50)
        assertEquals(1, runs)
    }

    @Test fun `a server error is failed with its message, and signed out means no runner`() = runTest {
        sessions.restore()
        val m = SyncManager(sessions, FakeConnectivity(true), { SyncRunner { throw ApiException(500, "internal", "boom") } }, backgroundScope)
        m.syncNow()
        assertEquals(SyncStatus.Failed("boom"), m.status.value)
        val m2 = SyncManager(sessions, FakeConnectivity(true), { null }, backgroundScope)
        assertTrue(m2.syncNow().isFailure)
        assertIs<SyncStatus.SignedOut>(m2.status.value)
    }
}
