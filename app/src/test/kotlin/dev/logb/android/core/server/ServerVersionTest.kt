package dev.logb.android.core.server

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerVersionTest {
    @Test fun `compares numerically per part`() {
        assertTrue(ServerVersion.parse("0.10.0") > ServerVersion.parse("0.9.9"))
        assertTrue(ServerVersion.parse("1.0") > ServerVersion.parse("0.99.99"))
        assertEquals(0, ServerVersion.parse("0.8").compareTo(ServerVersion.parse("0.8.0")))
    }

    @Test fun `ignores a pre-release or build suffix`() {
        assertEquals(0, ServerVersion.parse("0.8.0-rc1").compareTo(ServerVersion.parse("0.8.0")))
    }

    @Test fun `unparsable and missing versions are zero`() {
        assertEquals(ServerVersion.ZERO, ServerVersion.parse(null))
        assertEquals(ServerVersion.ZERO, ServerVersion.parse(""))
        assertEquals(ServerVersion.ZERO, ServerVersion.parse("banana"))
    }

    @Test fun `capabilities follow the thresholds`() {
        assertEquals(Capabilities.NONE, Capabilities.of("0.7.1"))
        assertEquals(Capabilities(tags = true, ownTypes = true, pairing = false), Capabilities.of("0.8.0"))
        assertEquals(Capabilities(tags = true, ownTypes = true, pairing = false), Capabilities.of("0.10.0"))
        assertEquals(Capabilities(tags = true, ownTypes = true, pairing = true), Capabilities.of("0.11.0"))
        assertFalse(Capabilities.of(null).tags)
    }
}
