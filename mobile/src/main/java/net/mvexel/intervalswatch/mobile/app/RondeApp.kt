package net.mvexel.intervalswatch.mobile.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.StateFlow
import net.mvexel.intervalswatch.mobile.PendingLogin
import net.mvexel.intervalswatch.mobile.data.session.Session
import net.mvexel.intervalswatch.mobile.data.session.SessionState
import net.mvexel.intervalswatch.mobile.feature.history.HistoryRoute
import net.mvexel.intervalswatch.mobile.feature.history.HistoryViewModel
import net.mvexel.intervalswatch.mobile.feature.history.detail.ActivityDetailRoute
import net.mvexel.intervalswatch.mobile.feature.history.detail.ActivityDetailViewModel
import net.mvexel.intervalswatch.mobile.feature.history.detail.RepositoryDetailLoader
import net.mvexel.intervalswatch.mobile.feature.login.LoginRoute
import net.mvexel.intervalswatch.mobile.feature.login.LoginViewModel
import net.mvexel.intervalswatch.mobile.feature.plan.PlanRoute
import net.mvexel.intervalswatch.mobile.feature.plan.PlanViewModel
import net.mvexel.intervalswatch.mobile.feature.plan.PlannedWorkoutDetailRoute
import net.mvexel.intervalswatch.mobile.feature.plan.PlannedWorkoutDetailViewModel
import net.mvexel.intervalswatch.mobile.feature.settings.SettingsRoute
import net.mvexel.intervalswatch.mobile.feature.settings.SettingsViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingActions
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingsViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.WatchRoute
import net.mvexel.intervalswatch.mobile.feature.watch.WatchScreensRoute
import net.mvexel.intervalswatch.mobile.feature.watch.WatchScreensViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.WatchViewModel

/** Activity-scoped: starts the session when the app opens and decides login vs tabs. */
class AppViewModel(private val session: Session) : ViewModel() {
    val state: StateFlow<SessionState> = session.state

    init {
        session.start()
    }

    fun handleRedirect(uri: String?) = session.handleRedirect(uri)
    suspend fun beginLogin(): PendingLogin = session.beginLogin()
    fun showStatus(msg: String) = session.showStatus(msg)
}

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    PLAN(Routes.PLAN, "Plan", Icons.Filled.DateRange),
    HISTORY(Routes.HISTORY, "History", Icons.AutoMirrored.Filled.List),
    WATCH(Routes.WATCH, "Watch", Icons.Filled.PlayArrow),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}

object Routes {
    const val PLAN = "plan"
    const val HISTORY = "history"
    const val WATCH = "watch"
    const val SETTINGS = "settings"
    const val ARG_ID = "id"
    const val PLANNED_WORKOUT = "plan/workout/{$ARG_ID}"
    const val ACTIVITY = "history/activity/{$ARG_ID}"
    const val WATCH_SCREENS = "watch/screens"

    fun plannedWorkout(eventId: Long) = "plan/workout/$eventId"
    fun activity(id: String) = "history/activity/" + android.net.Uri.encode(id)

    /** The tab a route belongs to: its first path segment. */
    fun tabOf(route: String?): Tab? = route?.substringBefore('/')?.let { seg -> Tab.entries.firstOrNull { it.route == seg } }
}

/** Login while signed out, otherwise the four tabs. Nothing until settings have loaded, so login never flashes. */
@Composable
fun RondeApp(
    container: AppContainer,
    appVm: AppViewModel,
    recordings: RecordingsViewModel,
    actions: RecordingActions,
    onLogin: () -> Unit,
) {
    val st by appVm.state.collectAsStateWithLifecycle()
    when {
        !st.loaded -> Box(Modifier.fillMaxSize())
        !st.settings.configured -> Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
            Box(Modifier.padding(pad)) {
                LoginRoute(viewModel { LoginViewModel(container.session) }, recordings, actions, onLogin)
            }
        }
        else -> SignedIn(container, recordings, actions, onLogin)
    }
}

@Composable
private fun SignedIn(container: AppContainer, recordings: RecordingsViewModel, actions: RecordingActions, onLogin: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    MainTabsScaffold(selected = Routes.tabOf(entry?.destination?.route) ?: Tab.PLAN, onSelect = { nav.navigateToTab(it) }) {
        NavHost(nav, startDestination = Routes.PLAN) {
            composable(Routes.PLAN) {
                PlanRoute(viewModel { PlanViewModel(container.session, container.prefsDao) }) { nav.navigate(Routes.plannedWorkout(it)) }
            }
            composable(Routes.PLANNED_WORKOUT, arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong(Routes.ARG_ID)
                PlannedWorkoutDetailRoute(viewModel { PlannedWorkoutDetailViewModel(id, container.session, container.prefsDao) }) { nav.popBackStack() }
            }
            composable(Routes.HISTORY) {
                HistoryRoute(viewModel { HistoryViewModel(container.activityRepository) }) { nav.navigate(Routes.activity(it)) }
            }
            composable(Routes.ACTIVITY, arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType })) { e ->
                val id = e.arguments!!.getString(Routes.ARG_ID)!!
                // TODO(detail-loader): use container.activityDetailLoader once the sync work adds it to AppContainer.
                ActivityDetailRoute(viewModel { ActivityDetailViewModel(RepositoryDetailLoader(container.activityRepository), id) }) { nav.popBackStack() }
            }
            composable(Routes.WATCH) {
                WatchRoute(viewModel { WatchViewModel(container.session) }, recordings, actions) { nav.navigate(Routes.WATCH_SCREENS) }
            }
            composable(Routes.WATCH_SCREENS) {
                WatchScreensRoute(viewModel { WatchScreensViewModel(container.screenSettings, container.buzzer, container.session) }) { nav.popBackStack() }
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(viewModel { SettingsViewModel(container.session) }, onLogin = onLogin, onWatchScreens = { nav.navigate(Routes.WATCH_SCREENS) })
            }
        }
    }
}

private fun NavHostController.navigateToTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** The bottom bar around a tab's content. Stateless, so screenshot tests can use it. */
@Composable
fun MainTabsScaffold(selected: Tab, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == selected, onClick = { onSelect(t) },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad -> Box(Modifier.padding(pad)) { content() } }
}
