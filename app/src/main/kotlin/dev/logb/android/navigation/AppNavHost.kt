package dev.logb.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.logb.android.feature.objects.ObjectsScreen

/** The signed-in, bootstrapped app: a bottom bar with three destinations and the screens under them. */
@Composable
fun AppNavHost(onSignOut: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val active = activeDestination(backStack?.destination?.route)
    Scaffold(
        bottomBar = {
            BottomBar(active) { destination ->
                val route: Any = when (destination) {
                    Destination.Objects -> Objects
                    Destination.Search -> Search
                    Destination.Settings -> Settings
                }
                nav.navigate(route) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Objects, modifier = Modifier.padding(padding)) {
            composable<Objects> { ObjectsScreen(onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) }) }
            composable<ObjectDetail> { entry -> Text("object ${entry.arguments?.getString("uuid")}") } // Task 11
            composable<Search> { Text("search") } // Task 12
            composable<Settings> { Text("settings") } // Task 12
        }
    }
}
