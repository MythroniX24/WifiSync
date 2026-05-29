package com.example.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.WaveDropViewModel

@Composable
fun WaveDropAppScreen(viewModel: WaveDropViewModel) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { WaveDropBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "shared_space",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("shared_space") { SharedSpaceScreen(viewModel) }
            composable("devices") { DevicesScreen(viewModel) }
            composable("history") { HistoryScreen(viewModel) }
            composable("settings") { SettingsScreen() }
        }
    }
}

@Composable
fun WaveDropBottomBar(navController: NavHostController) {
    val items = listOf("shared_space", "devices", "history", "settings")
    val icons = listOf(Icons.Default.Home, Icons.Default.WifiTethering, Icons.Default.History, Icons.Default.Settings)
    val labels = listOf("Space", "Nearby", "History", "Settings")

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        items.forEachIndexed { index, route ->
            NavigationBarItem(
                icon = { Icon(icons[index], contentDescription = labels[index]) },
                label = { Text(labels[index]) },
                selected = currentRoute == route,
                onClick = {
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
