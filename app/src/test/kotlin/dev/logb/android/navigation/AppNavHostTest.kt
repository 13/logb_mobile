package dev.logb.android.navigation

import org.junit.Test
import kotlin.test.assertEquals

/**
 * [transitionKind] is the pure decision behind `AppNavHost`'s four transition lambdas. The two
 * "tempting but wrong" alternatives the fix avoided don't apply here — this only checks the
 * actual signals `AppNavHost` feeds it: a bottom-bar-initiated navigation, or a jump between two
 * top-level tabs that isn't ([AppNavHost]'s `isTabSwitch()`).
 */
class AppNavHostTest {
    @Test
    fun `either signal makes the transition instant`() {
        assertEquals(TransitionKind.Instant, transitionKind(bottomBarNavigation = true, tabSwitch = true))
        assertEquals(TransitionKind.Instant, transitionKind(bottomBarNavigation = true, tabSwitch = false))
        assertEquals(TransitionKind.Instant, transitionKind(bottomBarNavigation = false, tabSwitch = true))
    }

    @Test
    fun `neither signal slides, as an ordinary push or pop does`() {
        assertEquals(TransitionKind.Slide, transitionKind(bottomBarNavigation = false, tabSwitch = false))
    }

    @Test
    fun `a bottom-bar tap from deep in a stack is instant even though isTabSwitch alone would say no`() {
        // ActivityForm -> Search: isTabSwitch() is false (ActivityForm isn't top-level), which is
        // the bug this fix closes. bottomBarNavigation alone must be enough.
        assertEquals(TransitionKind.Instant, transitionKind(bottomBarNavigation = true, tabSwitch = false))
    }
}
