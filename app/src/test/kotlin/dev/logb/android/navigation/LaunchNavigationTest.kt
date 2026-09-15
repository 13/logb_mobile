package dev.logb.android.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@Serializable internal object LaunchRoot
@Serializable internal object LaunchDeep

/** What a real screen's ViewModel does: reads its object from the entry's SavedStateHandle, once. */
internal class BoundViewModel(handle: SavedStateHandle) : ViewModel() {
    val boundTo: String = handle.get<String>("uuid") ?: handle.get<String>("objectUuid") ?: "?"
}

/**
 * [navigateForLaunch] over the real `ObjectDetail`/`ReadingForm` routes, in the same bare-NavHost
 * harness style as [BottomBarTransitionTimingTest] (the real screens need Hilt). Each destination
 * shows both its route argument and what its entry-scoped ViewModel was bound to, so a reused
 * entry -- the singleTop bug -- is visible as a mismatch.
 */
@RunWith(AndroidJUnit4::class)
class LaunchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Harness(onReady: (NavHostController) -> Unit) {
        val nav = rememberNavController()
        onReady(nav)
        NavHost(nav, startDestination = LaunchRoot) {
            composable<LaunchRoot> { BasicText("root", Modifier.fillMaxSize().testTag("root")) }
            composable<LaunchDeep> { BasicText("deep") }
            composable<ObjectDetail> { entry ->
                val vm: BoundViewModel = viewModel { BoundViewModel(entry.savedStateHandle) }
                BasicText("detail route=${entry.toRoute<ObjectDetail>().uuid} vm=${vm.boundTo}")
            }
            composable<ReadingForm> { entry ->
                val vm: BoundViewModel = viewModel { BoundViewModel(entry.savedStateHandle) }
                BasicText("reading route=${entry.toRoute<ReadingForm>().objectUuid} vm=${vm.boundTo}")
            }
        }
    }

    private fun start(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent { Harness { nav = it } }
        compose.waitForIdle()
        return nav
    }

    @Test
    fun `with ReadingForm(A) on top, a Reading request for B shows B, not A`() {
        val nav = start()
        compose.runOnIdle { nav.navigate(ReadingForm("A")) }
        compose.onNodeWithText("reading route=A vm=A").assertIsDisplayed()

        compose.runOnIdle { nav.navigateForLaunch(ReadingForm("B")) }
        compose.onNodeWithText("reading route=B vm=B").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("B", nav.currentBackStackEntry!!.toRoute<ReadingForm>().objectUuid)
            // Replaced, not stacked: back from B's form returns to where A's form was opened from.
            assertEquals(listOf("LaunchRoot", "ReadingForm"), nav.currentBackStack.value.mapNotNull { it.destination.route?.substringAfterLast('.')?.substringBefore('/')?.substringBefore('?') })
        }
    }

    @Test
    fun `with ObjectDetail(A) on top, a Reminders request for B shows B, not A`() {
        val nav = start()
        compose.runOnIdle { nav.navigate(ObjectDetail("A", tab = "reminders")) }
        compose.onNodeWithText("detail route=A vm=A").assertIsDisplayed()

        compose.runOnIdle { nav.navigateForLaunch(ObjectDetail("B", tab = "reminders")) }
        compose.onNodeWithText("detail route=B vm=B").assertIsDisplayed()
        compose.runOnIdle { assertEquals("B", nav.currentBackStackEntry!!.toRoute<ObjectDetail>().uuid) }
    }

    @Test
    fun `the very same route already on top is left alone`() {
        val nav = start()
        compose.runOnIdle { nav.navigate(ReadingForm("A")) }
        compose.waitForIdle()
        val before = compose.runOnIdle { nav.currentBackStackEntry!!.id }

        compose.runOnIdle { nav.navigateForLaunch(ReadingForm("A")) }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(before, nav.currentBackStackEntry!!.id) }
        compose.onNodeWithText("reading route=A vm=A").assertIsDisplayed()
    }

    @Test
    fun `an object's detail deeper in the stack does not stop a fresh push`() {
        val nav = start()
        compose.runOnIdle { nav.navigate(ObjectDetail("A")); nav.navigate(LaunchDeep) }
        compose.waitForIdle()

        compose.runOnIdle { nav.navigateForLaunch(ObjectDetail("B", tab = "reminders")) }
        compose.onNodeWithText("detail route=B vm=B").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, nav.currentBackStack.value.count { it.destination.route != null && it.destination.navigatorName != "navigation" }) }
    }
}
