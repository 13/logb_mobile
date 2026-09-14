package dev.logb.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.logb.android.feature.objects.ObjectDetailScreen
import dev.logb.android.feature.objects.ObjectsScreen
import dev.logb.android.feature.search.SearchScreen
import dev.logb.android.feature.settings.AboutScreen
import dev.logb.android.feature.settings.AccountScreen
import dev.logb.android.feature.settings.AppearanceScreen
import dev.logb.android.feature.settings.SettingsHubScreen
import dev.logb.android.feature.settings.SettingsPage
import dev.logb.android.feature.settings.SyncScreen

/** The signed-in, bootstrapped app: a bottom bar with three destinations and the screens under them. */
@Composable
fun AppNavHost() {
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
            composable<Objects> { ObjectsScreen(onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) }, onOpenSync = { nav.navigate(SyncSettings) }) }
            composable<ObjectDetail> { ObjectDetailScreen(onBack = { nav.popBackStack() }, onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) }) }
            composable<Search> { SearchScreen(onOpenObject = { uuid -> nav.navigate(ObjectDetail(uuid)) }) }
            composable<Settings> {
                SettingsHubScreen(onOpen = { page ->
                    nav.navigate(
                        when (page) {
                            SettingsPage.Appearance -> Appearance
                            SettingsPage.Account -> Account
                            SettingsPage.Sync -> SyncSettings
                            SettingsPage.About -> About
                        },
                    )
                })
            }
            composable<Appearance> { AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable<Account> { AccountScreen(onBack = { nav.popBackStack() }) }
            composable<SyncSettings> { SyncScreen(onBack = { nav.popBackStack() }) }
            composable<About> { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
