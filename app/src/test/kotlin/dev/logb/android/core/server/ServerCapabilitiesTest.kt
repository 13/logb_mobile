package dev.logb.android.core.server

import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.ServerRecord
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerCapabilitiesTest {
    private val record = ServerRecord("https://logb.example/", userId = 1, username = "ben", serverVersion = "0.7.1")

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
