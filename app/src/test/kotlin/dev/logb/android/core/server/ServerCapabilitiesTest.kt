package dev.logb.android.core.server

import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.ServerStore
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
     * concurrent [refresh] finish, and only then let the stalled read return. */
    private class GatedServerStore(private val delegate: ServerStore, private val gate: CompletableDeferred<Unit>) : ServerStore {
        private var firstRead = true
        override suspend fun read(): ServerRecord? {
            if (firstRead) { firstRead = false; gate.await() }
            return delegate.read()
        }
        override suspend fun write(record: ServerRecord) = delegate.write(record)
        override suspend fun clear() = delegate.clear()
        override suspend fun setVersion(serverUrl: String, version: String?) = delegate.setVersion(serverUrl, version)
    }

    @Test fun `load reads the stored version`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.8.0")))
        caps.load()
        assertTrue(caps.current.value.tags)
        assertEquals("0.8.0", caps.version.value)
    }

    @Test fun `refresh stores the new version and reports a gained capability once`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        assertTrue(caps.refresh { "0.8.0" }, "0.7.1 -> 0.8.0 gains tags")
        assertEquals("0.8.0", store.read()!!.serverVersion)
        assertFalse(caps.refresh { "0.8.0" }, "no change the second time")
    }

    @Test fun `pairing alone is not a reason to bootstrap`() = runBlocking {
        val caps = ServerCapabilities(FakeServerStore(record.copy(serverVersion = "0.8.0")))
        caps.load()
        assertFalse(caps.refresh { "0.9.0" })
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
        assertFalse(caps.refresh { "0.9.0" }, "nothing to store the version in")
    }

    @Test fun `store cleared during the fetch is left cleared and refresh reports no gain`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        assertFalse(caps.refresh { store.clear(); "0.8.0" })
        assertEquals(null, store.read())
        assertEquals("0.7.1", caps.version.value, "in-memory value untouched")
    }

    @Test fun `a refresh that finishes while a slower load is still reading is not clobbered`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = GatedServerStore(FakeServerStore(record), gate)
        val caps = ServerCapabilities(store)

        val loadJob = launch(start = CoroutineStart.UNDISPATCHED) { caps.load() }
        assertTrue(caps.refresh { "0.9.0" })
        gate.complete(Unit)
        loadJob.join()

        assertEquals("0.9.0", caps.version.value)
    }

    @Test fun `server url changed during the fetch is not overwritten with the new version`() = runBlocking {
        val store = FakeServerStore(record)
        val caps = ServerCapabilities(store)
        caps.load()
        val other = ServerRecord("https://other.example/", userId = 2, username = "ann", serverVersion = null)
        assertFalse(caps.refresh { store.write(other); "0.8.0" })
        assertEquals(other, store.read(), "the other server's record is untouched")
        assertEquals("0.7.1", caps.version.value, "in-memory value untouched")
    }
}
