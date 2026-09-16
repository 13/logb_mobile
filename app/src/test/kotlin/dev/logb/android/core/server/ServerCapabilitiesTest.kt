package dev.logb.android.core.server

import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.ServerStore
import dev.logb.android.core.network.dto.HealthInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerCapabilitiesTest {
    private val record = ServerRecord("https://logb.example/", userId = 1, username = "ben", serverVersion = "0.7.1")

    /** Gates only the very first [read] call, so a test can start [load] mid-flight, let a
     * concurrent [refresh] finish, and only then let the stalled read return -- with the value
     * that read would have seen *before* that refresh, since a real, slow DataStore read that
     * started before a write can still complete after it with what it originally saw. Without
     * that, the delayed read would simply pick up whatever refresh() already wrote and the test
     * could not tell a guarded load() from an unguarded one landing on the same answer by luck. */
    private class GatedServerStore(private val delegate: ServerStore, private val gate: CompletableDeferred<Unit>) : ServerStore {
        private var firstRead = true
        override suspend fun read(): ServerRecord? {
            if (firstRead) {
                firstRead = false
                val stale = delegate.read()
                gate.await()
                return stale
            }
            return delegate.read()
        }
        override suspend fun write(record: ServerRecord) = delegate.write(record)
        override suspend fun clear() = delegate.clear()
        override suspend fun setVersion(serverUrl: String, version: String?, features: List<String>) = delegate.setVersion(serverUrl, version, features)
    }

    @Test fun `load reads the stored version`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.8.0")))
        caps.load()
        assertTrue(caps.current.value.tags)
        assertEquals("0.8.0", caps.version.value)
    }

    @Test fun `load reads the stored features`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.11.0", features = listOf("pairing"))))
        caps.load()
        assertTrue(caps.current.value.pairing)
    }

    @Test fun `no features field means no pairing, even on a version that once implied it`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.11.0")))
        caps.load()
        assertFalse(caps.current.value.pairing)
    }

    @Test fun `refresh stores the new version and reports a gained capability once`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        assertTrue(caps.refresh { HealthInfo(version = "0.8.0") }, "0.7.1 -> 0.8.0 gains tags")
        assertEquals("0.8.0", store.read()!!.serverVersion)
        assertFalse(caps.refresh { HealthInfo(version = "0.8.0") }, "no change the second time")
    }

    @Test fun `refresh stores the fetched features and sign-in stores them the same way`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        caps.refresh { HealthInfo(version = "0.7.1", features = listOf("pairing")) }
        assertEquals(listOf("pairing"), store.read()!!.features)
        assertTrue(caps.current.value.pairing)
    }

    @Test fun `pairing feature alone is not a reason to bootstrap`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.8.0")))
        caps.load()
        assertFalse(caps.refresh { HealthInfo(version = "0.8.0", features = listOf("pairing")) })
        assertTrue(caps.current.value.pairing)
    }

    @Test fun `a failed fetch keeps what is known`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.8.0")))
        caps.load()
        assertFalse(caps.refresh { null })
        assertTrue(caps.current.value.tags)
    }

    @Test fun `no stored record means no capabilities`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(null))
        caps.load()
        assertEquals(Capabilities.NONE, caps.current.value)
        assertFalse(caps.refresh { HealthInfo(version = "0.9.0") }, "nothing to store the version in")
    }

    @Test fun `store cleared during the fetch is left cleared and refresh reports no gain`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        assertFalse(caps.refresh { store.clear(); HealthInfo(version = "0.8.0") })
        assertEquals(null, store.read())
        assertEquals("0.7.1", caps.version.value, "in-memory value untouched")
    }

    @Test fun `a refresh that finishes while a slower load is still reading is not clobbered`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = GatedServerStore(FakeServerStore(record), gate)
        val caps = ServerCapabilities(store)

        // load() reads first (0.7.1, before refresh()), then stalls on the gate; refresh() runs to
        // completion and writes 0.9.0; only then is load()'s stale 0.7.1 read let through. Without
        // the guard, load() would apply it and clobber refresh()'s 0.9.0 back down to 0.7.1.
        val loadJob = launch(start = CoroutineStart.UNDISPATCHED) { caps.load() }
        assertTrue(caps.refresh { HealthInfo(version = "0.9.0") })
        gate.complete(Unit)
        loadJob.join()

        assertEquals("0.9.0", caps.version.value, "the guard must have made load() skip its stale read")
    }

    @Test fun `load after refresh for one server picks up a different server's version`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        assertTrue(caps.refresh { HealthInfo(version = "0.8.0") })
        assertEquals("0.8.0", caps.version.value)

        val other = ServerRecord("https://other.example/", userId = 2, username = "ann", serverVersion = "0.9.0")
        store.write(other)
        caps.load()

        assertEquals("0.9.0", caps.version.value, "load must not skip just because a previous server was refreshed")
        assertTrue(caps.current.value.ownTypes)
    }

    @Test fun `clear resets the guard so a different account's load on the same server is not skipped`() = runBlocking {
        val store = FakeServerStore(record) // server S, account A, 0.7.1
        val caps = ServerCapabilities(store)
        caps.load()
        assertTrue(caps.refresh { HealthInfo(version = "0.8.0") }) // A refreshes: refreshedUrl now points at S
        assertEquals("0.8.0", caps.version.value)

        caps.clear() // account A signs out

        // Account B signs into the same server S; its own record, with its own version and features, lands in the store.
        val accountB = record.copy(userId = 2, username = "ann", serverVersion = "0.11.0", features = listOf("pairing"))
        store.write(accountB)
        caps.load()

        assertEquals("0.11.0", caps.version.value, "load after sign-out must read account B's own record, not trust account A's stale refresh guard")
        assertTrue(caps.current.value.pairing)
    }

    @Test fun `server url changed during the fetch is not overwritten with the new version`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        val other = ServerRecord("https://other.example/", userId = 2, username = "ann", serverVersion = null)
        assertFalse(caps.refresh { store.write(other); HealthInfo(version = "0.8.0") })
        assertEquals(other, store.read(), "the other server's record is untouched")
        assertEquals("0.7.1", caps.version.value, "in-memory value untouched")
    }
}
