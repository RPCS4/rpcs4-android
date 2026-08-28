package com.rpcs4.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rpcs4.android.ui.screens.emulator.EmulatorScreen
import com.rpcs4.android.ui.screens.library.LibraryScreen
import com.rpcs4.android.ui.screens.logs.LogScreen
import com.rpcs4.android.ui.screens.settings.SettingsScreen
import com.rpcs4.android.ui.navigation.Routes
import com.rpcs4.android.ui.navigation.bottomEntries
import androidx.navigation.NavGraph.Companion.findStartDestination

@Composable
fun Rpcs4NavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != Routes.Emulator.PATTERN

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomEntries.forEach { entry ->
                        NavigationBarItem(
                            selected = currentRoute == entry.route,
                            onClick = {
                                navController.navigate(entry.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Library.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.Library.route) {
                LibraryScreen(
                    onBootGame = { titleId ->
                        navController.navigate(Routes.Emulator.build(titleId))
                    },
                )
            }

            composable(Routes.Logs.route) {
                LogScreen()
            }

            composable(Routes.Settings.route) {
                SettingsScreen()
            }

            composable(
                route = Routes.Emulator.PATTERN,
                arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
            ) { entry ->
                val titleId = requireNotNull(entry.arguments).getString("titleId").orEmpty()
                EmulatorScreen(
                    titleId = titleId,
                    onExit = { navController.popBackStack() },
                )
            }
        }
    }
}
