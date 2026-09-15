package dev.logb.android.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    private fun v(s: String) = checkNotNull(AppVersion.parse(s)) { "expected $s to parse" }

    @Test fun `reads a plain version and a tag`() {
        assertEquals(AppVersion(0, 7, 1), AppVersion.parse("0.7.1"))
        assertEquals(AppVersion(0, 7, 1), AppVersion.parse("v0.7.1"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse(" v1 "))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2"))
    }

    @Test fun `ten is newer than nine`() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.7.1") > v("0.7.0"))
    }

    @Test fun `older and equal are not newer`() {
        assertTrue(v("0.6.0") < v("0.7.0"))
        assertEquals(0, v("0.7.0").compareTo(v("v0.7.0")))
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    /** "Cannot tell" must never be read as a real version. */
    @Test fun `anything that is not one to three numbers is unreadable`() {
        listOf(null, "", "v", "nightly", "1.0.0-rc1", "1.2.3.4", "1..2", "-1.0.0", "1.0.0+build")
            .forEach { assertNull("expected '$it' to be unreadable", AppVersion.parse(it)) }
    }

    @Test fun `prints without the tag prefix`() = assertEquals("0.7.1", v("v0.7.1").toString())
}
