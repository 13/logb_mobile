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

/**
 * [RealWidgetRefresher] against Robolectric's [androidx.glance.appwidget.GlanceAppWidgetManager],
 * which reports no placed widgets in a unit test -- there is no launcher to place one in. The
 * point of the test is the early-return path this exercises: refreshing must be cheap, and safe,
 * when nothing is placed, without reaching for the Hilt entry point `WidgetUpdater.refresh` needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RealWidgetRefresherTest {
    @Test
    fun `a debounced refresh with nothing placed does not throw or touch updateAll`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val refresher = RealWidgetRefresher(context, backgroundScope)
        refresher.requestRefresh()
        advanceTimeBy(600) // past the debounce delay
    }

    @Test
    fun `an immediate refresh with nothing placed does not throw`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val refresher = RealWidgetRefresher(context, backgroundScope)
        refresher.requestImmediateRefresh()
        runCurrent()
    }
}
