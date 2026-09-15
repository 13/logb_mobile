package dev.logb.android.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.core.design.components.LocalTypeRegistry
import dev.logb.android.feature.share.LaunchTarget
import dev.logb.android.feature.share.ShareInbox
import dev.logb.android.feature.share.ShareTargetScreen
import dev.logb.android.feature.types.TypesRootViewModel
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.logb.android.feature.entries.ActivityFormScreen
import dev.logb.android.feature.entries.AttachmentViewerScreen
import dev.logb.android.feature.entries.ReadingFormScreen
import dev.logb.android.feature.objects.ObjectFormScreen
import dev.logb.android.feature.reminders.DueListScreen
import dev.logb.android.feature.reminders.ReminderFormScreen
import dev.logb.android.feature.objects.ObjectDetailScreen
import dev.logb.android.feature.objects.ObjectsScreen
import dev.logb.android.feature.search.SearchScreen
import dev.logb.android.feature.stats.StatsScreen
import dev.logb.android.feature.settings.AboutScreen
import dev.logb.android.feature.settings.AccountScreen
import dev.logb.android.feature.settings.AppearanceScreen
import dev.logb.android.feature.settings.NotificationsScreen
import dev.logb.android.feature.settings.SettingsHubScreen
import dev.logb.android.feature.settings.SettingsPage
import dev.logb.android.feature.settings.SyncScreen
import dev.logb.android.feature.settings.data.DataScreen
import dev.logb.android.feature.settings.tokens.TokensScreen
import dev.logb.android.feature.types.TypesScreen

/**
 * Wraps a destination's content in an opaque background so the slide transition's quarter-width
 * overlap (see [AppNavHost]'s `NavHost` transitions) never lets another screen bleed through.
 * Matches the color `MainActivity`'s root `Surface` paints (its default, unset `color` param is
 * `MaterialTheme.colorScheme.surface`).
 */
@Composable
private fun Screen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
}

/**
 * True when both sides of a transition are one of the bottom bar's own top-level routes
 * (`Objects`/`Search`/`Settings`). Covers a jump between two top-level tabs that does *not* go
 * through the bottom bar's own `onClick` — [ShareInbox]'s `LaunchTarget.Search`, navigated from
 * `AppNavHost`'s `LaunchedEffect(target)` — which still shouldn't slide like a push. It does
 * *not* cover a bottom-bar tap taken from deep in a stack (its `initialState` isn't top-level),
 * which is what [transitionKind]'s `bottomBarNavigation` flag is for.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
    fun NavBackStackEntry.isTopLevel() =
        destination.hasRoute<Objects>() || destination.hasRoute<Search>() || destination.hasRoute<Settings>()
    return initialState.isTopLevel() && targetState.isTopLevel()
}

/** Whether a NavHost transition should be instant, or the ordinary push/pop slide. */
internal enum class TransitionKind { Instant, Slide }

/**
 * The pure decision behind every one of `AppNavHost`'s four transition lambdas: instant exactly
 * when either signal says so — a bottom-bar-initiated navigation (a plain tab switch, or a
 * reselect of the already-active tab that pops it to its root, from any depth), or [isTabSwitch]'s
 * own narrower top-level-to-top-level check for the other call site that isn't the bottom bar.
 */
internal fun transitionKind(bottomBarNavigation: Boolean, tabSwitch: Boolean): TransitionKind =
    if (bottomBarNavigation || tabSwitch) TransitionKind.Instant else TransitionKind.Slide

/** The signed-in, bootstrapped app: a bottom bar with three destinations and the screens under them. */
@Composable
fun AppNavHost(shareInbox: ShareInbox? = null) {
    val typesRoot: TypesRootViewModel = hiltViewModel()
    val registry by typesRoot.registry.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val shared by (shareInbox?.pending ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    LaunchedEffect(shared.isNotEmpty()) { if (shared.isNotEmpty()) nav.navigate(ShareTarget) { launchSingleTop = true } }
    val target by (shareInbox?.target ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    LaunchedEffect(target) {
        val request = shareInbox?.takeTarget()
        when (request?.target) {
            LaunchTarget.Due -> nav.navigate(DueList) { launchSingleTop = true }
            LaunchTarget.Search -> nav.navigate(Search) { launchSingleTop = true }
            LaunchTarget.NewObject -> nav.navigate(ObjectForm()) { launchSingleTop = true }
            // Never singleTop: that would reuse another object's entry -- see navigateForLaunch.
            LaunchTarget.Reminders -> request.objectUuid?.let { nav.navigateForLaunch(ObjectDetail(it, tab = "reminders")) }
            LaunchTarget.Reading -> request.objectUuid?.let { nav.navigateForLaunch(ReadingForm(it)) }
            null -> Unit
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val active = activeDestination(backStack?.destination?.route)

    // True while the transition in progress was started by a bottom-bar tap — a plain tab
    // switch, or a reselect of the already-active tab that pops it to its root — from any stack
    // depth. Set in `BottomBar`'s `onClick` below, right before its `nav.navigate()`.
    //
    // It can't simply be flipped back off on the next recomposition (a `SideEffect`, or a
    // `LaunchedEffect` keyed on `backStack?.id`, are the two obvious ways to write that reset).
    // `AnimatedContent`'s `transitionSpec` isn't sampled once when a transition starts and then
    // left alone for its ~300ms — Compose re-evaluates it on later recompositions of that same,
    // still-running transition too. Resetting the flag after only the first of those frames flips
    // a transition that's still animating back to "Slide" mid-flight: an instant switch visibly
    // restarts as a slide partway through. (An earlier, `SideEffect`-based version of this fix hit
    // exactly that — confirmed by `BottomBarTransitionTimingTest`, which caught it: the "instant"
    // destination was still measured at a full screen-width offset on the frame the test checked.)
    //
    // So the reset instead waits for the transition to actually finish. `NavHost` promotes an
    // entering entry's own `Lifecycle` to `RESUMED` exactly then — not sooner — so a
    // `LaunchedEffect` on that entry, watching its `Lifecycle.currentStateFlow`, resets the flag
    // right after, once nothing will read it for this transition again. One case that reaches no
    // `RESUMED` transition at all: a reselect of the tab whose root is already showing navigates
    // to the entry already on top, so nothing changes and no transition starts. `BottomBar`'s
    // `onClick` below resets the flag for that case itself, synchronously, right after the no-op
    // `navigate()` call.
    var bottomBarNavigation by remember { mutableStateOf(false) }
    LaunchedEffect(backStack) {
        backStack?.lifecycle?.currentStateFlow?.collect { state ->
            if (state == Lifecycle.State.RESUMED) bottomBarNavigation = false
        }
    }

    CompositionLocalProvider(LocalTypeRegistry provides registry) {
        Scaffold(
            bottomBar = {
                BottomBar(active) { destination ->
                    val route: Any = when (destination) {
                        Destination.Objects -> Objects
                        Destination.Search -> Search
                        Destination.Settings -> Settings
                    }
                    val before = nav.currentBackStackEntry?.id
                    bottomBarNavigation = true
                    nav.navigate(route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    if (nav.currentBackStackEntry?.id == before) bottomBarNavigation = false
                }
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = Objects,
                modifier = Modifier.padding(padding),
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
                composable<Objects> {
                    Screen {
                        ObjectsScreen(
                            onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) }, onOpenSync = { nav.navigate(SyncSettings) }, onNew = { nav.navigate(ObjectForm()) },
                            onLog = { uuid -> nav.navigate(ActivityForm(uuid)) }, onReading = { uuid -> nav.navigate(ReadingForm(uuid)) },
                            onOpenDue = { nav.navigate(DueList) }, onOpenStats = { nav.navigate(Stats) },
                        )
                    }
                }
                composable<Stats> { Screen { StatsScreen(onBack = { nav.popBackStack() }, onOpenObject = { uuid -> nav.navigate(ObjectDetail(uuid)) }) } }
                composable<ObjectDetail> {
                    Screen {
                        ObjectDetailScreen(
                            onBack = { nav.popBackStack() },
                            onOpen = { uuid -> nav.navigate(ObjectDetail(uuid)) },
                            onEdit = { uuid -> nav.navigate(ObjectForm(uuid = uuid)) },
                            onAddChild = { uuid -> nav.navigate(ObjectForm(parentUuid = uuid)) },
                            onLog = { uuid -> nav.navigate(ActivityForm(uuid)) },
                            onEditEntry = { objectUuid, uuid -> nav.navigate(ActivityForm(objectUuid, uuid)) },
                            onAddReminder = { uuid -> nav.navigate(ReminderForm(uuid)) },
                            onAddReadingReminder = { uuid -> nav.navigate(ReminderForm(uuid, kind = "reading")) },
                            onReading = { uuid -> nav.navigate(ReadingForm(uuid)) },
                            onEditReminder = { objectUuid, uuid -> nav.navigate(ReminderForm(objectUuid, uuid)) },
                            onLogForReminder = { objectUuid, reminderUuid, title -> nav.navigate(ActivityForm(objectUuid, doneReminderUuid = reminderUuid, title = title)) },
                            onAttachment = { uuid -> nav.navigate(Viewer(uuid)) },
                        )
                    }
                }
                composable<ActivityForm> { Screen { ActivityFormScreen(onBack = { nav.popBackStack() }, onAttachment = { uuid -> nav.navigate(Viewer(uuid)) }) } }
                composable<Viewer> { Screen { AttachmentViewerScreen(onBack = { nav.popBackStack() }) } }
                composable<ShareTarget> { Screen { ShareTargetScreen(onCancel = { nav.popBackStack() }, onPick = { uuid -> nav.navigate(ActivityForm(uuid, fromShare = true)) { popUpTo<ShareTarget> { inclusive = true } } }) } }
                composable<ReadingForm> { Screen { ReadingFormScreen(onBack = { nav.popBackStack() }) } }
                composable<ReminderForm> { Screen { ReminderFormScreen(onBack = { nav.popBackStack() }) } }
                composable<DueList> { Screen { DueListScreen(onBack = { nav.popBackStack() }, onOpen = { uuid -> nav.navigate(ObjectDetail(uuid, tab = "reminders")) }, onReading = { uuid -> nav.navigate(ReadingForm(uuid)) }) } }
                composable<ObjectForm> { entry ->
                    val editing = entry.toRoute<ObjectForm>().uuid != null
                    Screen {
                        ObjectFormScreen(
                            onBack = { nav.popBackStack() },
                            onSaved = { uuid -> if (editing) nav.popBackStack() else nav.navigate(ObjectDetail(uuid)) { popUpTo<ObjectForm> { inclusive = true } } },
                            onDeleted = { nav.popBackStack<Objects>(inclusive = false) },
                        )
                    }
                }
                composable<Search> { Screen { SearchScreen(onOpenObject = { uuid -> nav.navigate(ObjectDetail(uuid)) }) } }
                composable<Settings> {
                    Screen {
                        SettingsHubScreen(onOpen = { page ->
                            nav.navigate(
                                when (page) {
                                    SettingsPage.Appearance -> Appearance
                                    SettingsPage.Account -> Account
                                    SettingsPage.Sync -> SyncSettings
                                    SettingsPage.Notifications -> Notifications
                                    SettingsPage.Types -> TypesSettings
                                    SettingsPage.Data -> DataSettings
                                    SettingsPage.ApiAccess -> ApiAccessSettings
                                    SettingsPage.About -> About
                                },
                            )
                        })
                    }
                }
                composable<Appearance> { Screen { AppearanceScreen(onBack = { nav.popBackStack() }) } }
                composable<Account> { Screen { AccountScreen(onBack = { nav.popBackStack() }) } }
                composable<SyncSettings> { Screen { SyncScreen(onBack = { nav.popBackStack() }) } }
                composable<About> { Screen { AboutScreen(onBack = { nav.popBackStack() }) } }
                composable<Notifications> { Screen { NotificationsScreen(onBack = { nav.popBackStack() }) } }
                composable<TypesSettings> { Screen { TypesScreen(onBack = { nav.popBackStack() }) } }
                composable<DataSettings> { Screen { DataScreen(onBack = { nav.popBackStack() }) } }
                composable<ApiAccessSettings> { Screen { TokensScreen(onBack = { nav.popBackStack() }) } }
            }
        }
    }
}
