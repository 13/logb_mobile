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
import androidx.navigation.toRoute
import dev.logb.android.feature.entries.ActivityFormScreen
import dev.logb.android.feature.entries.ReadingFormScreen
import dev.logb.android.feature.objects.ObjectFormScreen
import dev.logb.android.feature.reminders.DueListScreen
import dev.logb.android.feature.reminders.ReminderFormScreen
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
            composable<Objects> {
                ObjectsScreen(
                    onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) }, onOpenSync = { nav.navigate(SyncSettings) }, onNew = { nav.navigate(ObjectForm()) },
                    onLog = { uuid -> nav.navigate(ActivityForm(uuid)) }, onReading = { uuid -> nav.navigate(ReadingForm(uuid)) },
                    onOpenDue = { nav.navigate(DueList) },
                )
            }
            composable<ObjectDetail> {
                ObjectDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) },
                    onEdit = { uuid -> nav.navigate(ObjectForm(uuid = uuid)) },
                    onAddChild = { uuid -> nav.navigate(ObjectForm(parentUuid = uuid)) },
                    onLog = { uuid -> nav.navigate(ActivityForm(uuid)) },
                    onEditEntry = { objectUuid, uuid -> nav.navigate(ActivityForm(objectUuid, uuid)) },
                    onAddReminder = { uuid -> nav.navigate(ReminderForm(uuid)) },
                    onEditReminder = { objectUuid, uuid -> nav.navigate(ReminderForm(objectUuid, uuid)) },
                    onLogForReminder = { objectUuid, reminderUuid, title -> nav.navigate(ActivityForm(objectUuid, doneReminderUuid = reminderUuid, title = title)) },
                )
            }
            composable<ActivityForm> { ActivityFormScreen(onBack = { nav.popBackStack() }) }
            composable<ReadingForm> { ReadingFormScreen(onBack = { nav.popBackStack() }) }
            composable<ReminderForm> { ReminderFormScreen(onBack = { nav.popBackStack() }) }
            composable<DueList> { DueListScreen(onBack = { nav.popBackStack() }, onOpen = { uuid -> nav.navigate(ObjectDetail(uuid, tab = "reminders")) }) }
            composable<ObjectForm> { entry ->
                val editing = entry.toRoute<ObjectForm>().uuid != null
                ObjectFormScreen(
                    onBack = { nav.popBackStack() },
                    onSaved = { uuid -> if (editing) nav.popBackStack() else nav.navigate(ObjectDetail(uuid)) { popUpTo<ObjectForm> { inclusive = true } } },
                    onDeleted = { nav.popBackStack<Objects>(inclusive = false) },
                )
            }
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
