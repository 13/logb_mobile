package dev.logb.android.feature.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The privacy gate as it actually renders: a locked widget must never emit a node carrying an
 * object name or a reminder title, only the count -- [DueWidgetStateTest] covers the data that
 * feeds this, this covers that the composable honours it.
 */
@RunWith(RobolectricTestRunner::class)
class DueWidgetContentTest {
    private val rows = listOf(
        WidgetRow("r1", "o1", "Golf", "Oil change", "due", canMarkDone = true),
        WidgetRow("r2", "o2", "Boiler", "Filter", "in 3 days", canMarkDone = false),
    )

    @Test
    fun `locked shows the count text and no object name or title nodes`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DueWidget.MEDIUM)
        provideComposable { DueWidgetContent(DueWidgetState(count = 2, rows = emptyList(), locked = true, signedIn = true)) }
        onNode(hasText("Due · 2")).assertExists()
        onNode(hasText("Golf", true)).assertDoesNotExist()
        onNode(hasText("Boiler", true)).assertDoesNotExist()
        onNode(hasText("Oil change", true)).assertDoesNotExist()
    }

    @Test
    fun `unlocked shows the rows`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DueWidget.MEDIUM)
        provideComposable { DueWidgetContent(DueWidgetState(count = 2, rows = rows, locked = false, signedIn = true)) }
        onNode(hasText("Golf: Oil change")).assertExists()
        onNode(hasText("Boiler: Filter")).assertExists()
        onAllNodes(hasContentDescription("Mark done")).assertCountEquals(1)
    }

    @Test
    fun `a reading row has no check`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DueWidget.MEDIUM)
        val reading = listOf(WidgetRow("r1", "o1", "Golf", "Mileage", "due", canMarkDone = false))
        provideComposable { DueWidgetContent(DueWidgetState(count = 1, rows = reading, locked = false, signedIn = true)) }
        onNode(hasText("Golf: Mileage")).assertExists()
        onNode(hasContentDescription("Mark done")).assertDoesNotExist()
    }
}
