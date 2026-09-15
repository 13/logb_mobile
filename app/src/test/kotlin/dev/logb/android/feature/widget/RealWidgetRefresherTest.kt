package dev.logb.android.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * [RealWidgetRefresher] with its two seams faked, so "nothing placed" is asserted as *the updater
 * was not called* rather than merely as "nothing threw". One test still goes through the real
 * Glance manager (which reports nothing placed in a unit test -- there is no launcher) to check
 * that path stays safe without a Hilt entry point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RealWidgetRefresherTest {
    private var updates = 0

    private fun refresher(scope: kotlinx.coroutines.CoroutineScope, placed: Boolean) =
        RealWidgetRefresher(scope, anyPlaced = { placed }, update = { updates++ })

    @Test
    fun `a debounced refresh with nothing placed never touches the updater`() = runTest {
        val refresher = refresher(backgroundScope, placed = false)
        refresher.requestRefresh()
        advanceTimeBy(600)
        assertEquals(0, updates)
    }

    @Test
    fun `an immediate refresh with nothing placed never touches the updater`() = runTest {
        val refresher = refresher(backgroundScope, placed = false)
        refresher.requestImmediateRefresh()
        runCurrent()
        assertEquals(0, updates)
    }

    @Test
    fun `with a widget placed, a debounced refresh updates once after the delay`() = runTest {
        val refresher = refresher(backgroundScope, placed = true)
        repeat(5) { refresher.requestRefresh() }
        runCurrent()
        assertEquals(0, updates, "nothing runs before the debounce elapses")
        advanceTimeBy(600)
        assertEquals(1, updates)
    }

    @Test
    fun `with a widget placed, an immediate refresh updates at once`() = runTest {
        val refresher = refresher(backgroundScope, placed = true)
        refresher.requestImmediateRefresh()
        runCurrent()
        assertEquals(1, updates)
    }

    @Test
    fun `the real, Hilt-free path with nothing placed does not throw`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RealWidgetRefresher(context, backgroundScope).requestImmediateRefresh()
        runCurrent()
    }
}
