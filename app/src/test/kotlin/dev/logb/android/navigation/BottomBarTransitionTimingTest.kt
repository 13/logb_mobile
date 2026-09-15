package dev.logb.android.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Not `private`: kotlinx.serialization's reflective serializer lookup for a route's type needs
// at least package visibility (a private top-level object trips a JPMS IllegalAccessException).
@Serializable internal object RootA
@Serializable internal object RootB
@Serializable internal object DeepFromA

/**
 * Reproduces `AppNavHost`'s own NavHost transition wiring — the `bottomBarNavigation` flag, its
 * lifecycle-`RESUMED`-gated reset, and [transitionKind] — over three bare routes instead of the
 * real screens, which need `hiltViewModel()` and so a full Hilt DI graph `AppNavHost` can't get
 * inside a plain `ComponentActivity` under Robolectric. This exercises the actual
 * composition/effect ordering the fix depends on, not just [transitionKind] in isolation.
 */
@Composable
private fun Harness(onReady: (nav: NavHostController, bottomBarTap: (Any) -> Unit) -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    var bottomBarNavigation by remember { mutableStateOf(false) }
    LaunchedEffect(backStack) {
        backStack?.lifecycle?.currentStateFlow?.collect { state ->
            if (state == Lifecycle.State.RESUMED) bottomBarNavigation = false
        }
    }
    val bottomBarTap: (Any) -> Unit = { route ->
        val before = nav.currentBackStackEntry?.id
        bottomBarNavigation = true
        nav.navigate(route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        if (nav.currentBackStackEntry?.id == before) bottomBarNavigation = false
    }
    onReady(nav, bottomBarTap)

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
        fun NavBackStackEntry.isTopLevel() = destination.hasRoute<RootA>() || destination.hasRoute<RootB>()
        return initialState.isTopLevel() && targetState.isTopLevel()
    }

    NavHost(
        nav,
        startDestination = RootA,
        enterTransition = {
            if (transitionKind(bottomBarNavigation, isTabSwitch()) == TransitionKind.Instant) EnterTransition.None
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
        },
        exitTransition = {
            if (transitionKind(bottomBarNavigation, isTabSwitch()) == TransitionKind.Instant) ExitTransition.None
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300), targetOffset = { it / 4 })
        },
        popEnterTransition = {
            if (transitionKind(bottomBarNavigation, isTabSwitch()) == TransitionKind.Instant) EnterTransition.None
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300), initialOffset = { it / 4 })
        },
        popExitTransition = {
            if (transitionKind(bottomBarNavigation, isTabSwitch()) == TransitionKind.Instant) ExitTransition.None
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
        },
    ) {
        composable<RootA> { BasicText("A", Modifier.fillMaxSize().testTag("A")) }
        composable<RootB> { BasicText("B", Modifier.fillMaxSize().testTag("B")) }
        composable<DeepFromA> { BasicText("Deep", Modifier.fillMaxSize().testTag("Deep")) }
    }
}

@RunWith(AndroidJUnit4::class)
class BottomBarTransitionTimingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Pumps single frames (never the whole `tween(300)`) until [tag] is laid out with a real
     * size. `mainClock.autoAdvance = false` means composing and measuring the new destination —
     * not just animating it — needs explicit frames, so the first frame or two after a navigate()
     * can still show it zero-sized or absent; this finds the first frame it is actually on screen,
     * without over-advancing into (or past) the animation the test means to inspect.
     */
    private fun advanceUntilDisplayed(tag: String, maxFrames: Int = 8) {
        repeat(maxFrames) {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
            if (node != null && node.size.width > 0 && node.size.height > 0) return
        }
        error("'$tag' never became visible within $maxFrames frames")
    }

    @Test
    fun `a bottom-bar tap from deep in a stack lands fully in place, and stays there for the whole transition window`() {
        lateinit var nav: NavHostController
        lateinit var bottomBarTap: (Any) -> Unit
        compose.mainClock.autoAdvance = false
        compose.setContent { Harness { n, t -> nav = n; bottomBarTap = t } }
        compose.mainClock.advanceTimeBy(500) // settle the initial composition

        // Push deep, the ordinary way (not through the bottom bar), and let that slide settle.
        compose.runOnIdle { nav.navigate(DeepFromA) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("Deep").assertIsDisplayed()

        // The bug: tapping a bottom-bar tab from here used to slide like a push. Tap it, and
        // check the first frame "B" shows up on at all — no half-slid frame to wait out.
        compose.runOnIdle { bottomBarTap(RootB) }
        advanceUntilDisplayed("B")
        compose.onNodeWithTag("B").assertLeftPositionInRootIsEqualTo(0.dp)

        // A regression check for a bug this test itself first caught: an earlier version of the
        // fix reset its flag one frame too early, which didn't show up on the very first frame —
        // only once `tween(300)`'s later frames re-ran the transition spec and found the flag
        // already back to false, snapping "B" out to a full screen-width offset mid-transition.
        // So keep checking every frame across the whole 300ms window, not just the first.
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("B").assertLeftPositionInRootIsEqualTo(0.dp)
        }
    }

    @Test
    fun `an ordinary push is still a slide, not instant`() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent { Harness { n, _ -> nav = n } }
        compose.mainClock.advanceTimeBy(500)

        compose.runOnIdle { nav.navigate(DeepFromA) }
        // The first frame "Deep" is actually displayed on: `tween(300)` isn't done yet, so the
        // entering content is still offset — proving this harness (and so `transitionKind`)
        // actually distinguishes the two cases rather than making everything instant.
        advanceUntilDisplayed("Deep")
        val left = compose.onNodeWithTag("Deep").fetchSemanticsNode().positionInRoot.x
        assert(left > 0f) { "expected Deep to still be sliding in on its first frame, was at x=$left" }
    }

    @Test
    fun `reselecting the tab whose root is already showing is a no-op, and doesn't stick the flag`() {
        lateinit var nav: NavHostController
        lateinit var bottomBarTap: (Any) -> Unit
        compose.mainClock.autoAdvance = false
        compose.setContent { Harness { n, t -> nav = n; bottomBarTap = t } }
        compose.mainClock.advanceTimeBy(500)

        // Tap the tab that's already showing at its root: nothing changes, so no `RESUMED`
        // transition-complete event will ever come to reset the flag — `bottomBarTap` must
        // reset it itself, synchronously, or the next real (non-bottom-bar) push would wrongly
        // inherit "instant" from this stale flag.
        compose.runOnIdle { bottomBarTap(RootA) }
        compose.mainClock.advanceTimeBy(500)

        compose.runOnIdle { nav.navigate(DeepFromA) }
        advanceUntilDisplayed("Deep")
        val left = compose.onNodeWithTag("Deep").fetchSemanticsNode().positionInRoot.x
        assert(left > 0f) { "expected the ordinary push after a same-tab reselect to still slide, was at x=$left" }
    }
}
