package dev.logb.android.core.auth

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Privacy hook: toggling the lock, in either direction, must reach a placed widget at once. */
@RunWith(RobolectricTestRunner::class)
class LockPrefsTest {
    private class FakeWidgetRefresher : WidgetRefresher {
        var debounced = 0
        var immediate = 0
        override fun requestRefresh() { debounced++ }
        override fun requestImmediateRefresh() { immediate++ }
    }

    @Test
    fun `setEnabled persists first, then asks for an immediate refresh`() = runTest {
        val refresher = FakeWidgetRefresher()
        val prefs = LockPrefs(ApplicationProvider.getApplicationContext(), refresher)

        prefs.setEnabled(true)
        assertTrue(prefs.current(), "the setting must already be persisted when the widget redraws")
        assertEquals(1, refresher.immediate)
        assertEquals(0, refresher.debounced)

        prefs.setEnabled(false)
        assertFalse(prefs.current())
        assertEquals(2, refresher.immediate)
    }
}
