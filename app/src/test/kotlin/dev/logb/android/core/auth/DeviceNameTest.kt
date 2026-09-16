package dev.logb.android.core.auth

import org.junit.Test
import kotlin.test.assertEquals

class DeviceNameTest {
    @Test fun `trims and collapses whitespace`() {
        assertEquals("Google Pixel 8", DeviceName.sanitize("  Google    Pixel\t8  "))
    }

    @Test fun `collapses newlines and other whitespace runs to one space`() {
        assertEquals("Google Pixel 8", DeviceName.sanitize("Google\nPixel\n\n8"))
    }

    @Test fun `caps at 64 characters`() {
        val raw = "A".repeat(100)
        val sanitized = DeviceName.sanitize(raw)
        assertEquals(64, sanitized.length)
        assertEquals("A".repeat(64), sanitized)
    }

    @Test fun `collapses whitespace before capping, not after`() {
        // Over 64 raw characters, almost all of it one run of spaces: capping first (before
        // collapsing) would land inside that run and lose "B" entirely.
        val raw = "A" + " ".repeat(100) + "B"
        assertEquals("A B", DeviceName.sanitize(raw))
    }

    @Test fun `blank input falls back to Android`() {
        assertEquals("Android", DeviceName.sanitize(""))
        assertEquals("Android", DeviceName.sanitize("   "))
        assertEquals("Android", DeviceName.sanitize("\t\n"))
    }

    @Test fun `never returns blank even at the boundary of the cap`() {
        assertEquals("Android", DeviceName.sanitize(" ".repeat(64)))
    }
}
