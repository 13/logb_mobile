package dev.logb.android.core.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LwwTest {
    @Test
    fun `newer wins, older loses, equal breaks on device id`() {
        assertTrue(Lww.wins("2026-09-01T00:00:01.000Z", "b", "2026-09-01T00:00:00.000Z", "a"))
        assertFalse(Lww.wins("2026-09-01T00:00:00.000Z", "b", "2026-09-01T00:00:01.000Z", "a"))
        assertTrue(Lww.wins("2026-09-01T00:00:00.000Z", "b", "2026-09-01T00:00:00.000Z", "a"))
        assertFalse(Lww.wins("2026-09-01T00:00:00.000Z", "a", "2026-09-01T00:00:00.000Z", "b"))
        assertTrue(Lww.wins("2026-09-01T00:00:00.000Z", "a", null, null), "an unstamped field loses to anything")
    }

    @Test
    fun `timestamps compare chronologically, not lexically`() {
        assertTrue(Lww.wins("2026-09-01T02:00:00+02:00", "a", "2026-08-31T23:59:59.999Z", "b"))
        assertEquals("2026-09-01T00:00:00.000Z", Clock.canonical("2026-09-01T02:00:00+02:00"))
        assertEquals("2026-09-01T00:00:00.123Z", Clock.canonical("2026-09-01T00:00:00.123456Z"))
        assertEquals(null, Clock.canonical("yesterday"))
    }

    @Test
    fun `an unparseable incoming stamp never wins over a real one`() {
        assertFalse(Lww.wins("garbage", "z", "2026-09-01T00:00:00.000Z", "a"))
    }
}
