package dev.logb.android.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute

/**
 * Opens [route] for a notification or widget tap. `launchSingleTop` is wrong here: when the top
 * entry is the same destination for a *different* object, Navigation reuses that entry -- its
 * ViewModel and SavedStateHandle still bound to the old object -- so ObjectDetail(A) or
 * ReadingForm(A) would stay on screen for a tap meant for B, and a reading could be saved to A.
 *
 * Instead: exactly this route already on top is left alone; the same destination with other
 * arguments on top is replaced (popped, then a fresh entry pushed, so back does not return to
 * the wrong object's form); anything else is an ordinary push.
 */
internal inline fun <reified T : Any> NavController.navigateForLaunch(route: T) {
    val current = currentBackStackEntry?.takeIf { it.destination.hasRoute<T>() }?.toRoute<T>()
    when {
        current == route -> Unit
        current != null -> navigate(route) { popUpTo<T> { inclusive = true } }
        else -> navigate(route)
    }
}
