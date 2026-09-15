package dev.logb.android.feature.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.auth.Session
import dev.logb.android.core.network.dto.User
import dev.logb.android.core.notify.DueFixtures
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test
    fun `signed out shows the sign-in prompt and nothing else`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DueWidget.MEDIUM)
        provideComposable { DueWidgetContent(DueWidgetState(count = 0, rows = emptyList(), locked = false, signedIn = false)) }
        onNode(hasText("Open LogB to sign in")).assertExists()
        onNode(hasText("Due", true)).assertDoesNotExist()
        onNode(hasContentDescription("Mark done")).assertDoesNotExist()
    }

    @Test
    fun `the body starts locked, so no name renders before the first state arrives`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DueWidget.MEDIUM)
        provideComposable { DueWidgetBody(MutableSharedFlow()) }
        onNode(hasText("Due · 0")).assertExists()
        onNode(hasText("Golf", true)).assertDoesNotExist()
    }

    /**
     * The body renders what the flow says, locked included. Glance's unit-test environment copies
     * the tree once, on the Recomposer's first idle, and then stops observing it -- so a later
     * recomposition (a lock toggled mid-session) cannot be inspected here; that part is proven on
     * the flow itself in [DueWidgetFlowTest].
     */
    @Test
    fun `the body renders the flow's state, and a locked flow shows only the count`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DueWidget.MEDIUM)
        val session = MutableStateFlow<Session>(Session.SignedIn("https://logb.example/", User(1, "ben"), "t", "EUR"))
        val items = listOf(DueFixtures.service("r1", "Golf", "Oil change", due = true), DueFixtures.service("r2", "Boiler", "Filter", due = true))
        val states = DueWidgetFlow.states(session, MutableStateFlow(true), { MutableStateFlow(items) }, "due") { "in $it days" }
        provideComposable { DueWidgetBody(states) }
        onNode(hasText("Due · 2")).assertExists()
        onNode(hasText("Golf", true)).assertDoesNotExist()
        onNode(hasText("Oil change", true)).assertDoesNotExist()
    }
}
