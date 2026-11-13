package de.sleeptracker.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.sleeptracker.app.R
import de.sleeptracker.app.ui.journal.JournalScreen
import de.sleeptracker.app.ui.journal.ReportScreen
import de.sleeptracker.app.ui.settings.RecordingsScreen
import de.sleeptracker.app.ui.settings.SettingsScreen
import de.sleeptracker.app.ui.sleep.SleepScreen
import de.sleeptracker.app.ui.stats.StatisticsScreen
import de.sleeptracker.app.ui.tracking.TrackingScreen

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    SLEEP("sleep", R.string.tab_sleep, Icons.Filled.Bedtime),
    JOURNAL("journal", R.string.tab_journal, Icons.AutoMirrored.Filled.MenuBook),
    STATS("stats", R.string.tab_statistics, Icons.Filled.BarChart),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

object Routes {
    const val TRACKING = "tracking"
    const val RECORDINGS = "recordings"
    fun report(id: Long) = "report/$id"
}

@Composable
fun AppRoot(launchTarget: LaunchTarget?, onLaunchHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            is LaunchTarget.Report -> nav.navigate(Routes.report(launchTarget.sessionId)) { launchSingleTop = true }
            LaunchTarget.Tracking -> nav.navigate(Routes.TRACKING) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        onLaunchHandled()
    }

    Scaffold(
        bottomBar = {
            if (showBar) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.SLEEP.route, modifier = Modifier.padding(padding)) {
            composable(Tab.SLEEP.route) {
                SleepScreen(
                    onTrackingStarted = { nav.navigate(Routes.TRACKING) },
                    onOpenReport = { nav.navigate(Routes.report(it)) },
                    onOpenSettings = { nav.navigate(Tab.SETTINGS.route) },
                )
            }
            composable(Tab.JOURNAL.route) { JournalScreen(onOpen = { nav.navigate(Routes.report(it)) }) }
            composable(Tab.STATS.route) { StatisticsScreen(onOpenReport = { nav.navigate(Routes.report(it)) }) }
            composable(Tab.SETTINGS.route) { SettingsScreen(onOpenRecordings = { nav.navigate(Routes.RECORDINGS) }) }
            composable(Routes.TRACKING) {
                TrackingScreen(
                    onFinished = { id ->
                        nav.popBackStack(Tab.SLEEP.route, inclusive = false)
                        if (id != null) nav.navigate(Routes.report(id))
                    },
                )
            }
            composable(Routes.RECORDINGS) { RecordingsScreen(onBack = { nav.popBackStack() }) }
            composable("report/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                ReportScreen(sessionId = id, onBack = { nav.popBackStack() }, onDeleted = { nav.popBackStack() })
            }
        }
    }
}
