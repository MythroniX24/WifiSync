package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.WaveDropViewModel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment

@Composable
fun WaveDropAppScreen(viewModel: WaveDropViewModel) {
    val navController = rememberNavController()
    val incomingReq by viewModel.incomingRequest.collectAsStateWithLifecycle()

    incomingReq?.let { req ->
        AlertDialog(
            onDismissRequest = { req.onDecision(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Incoming File Drop", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "A nearby device wants to drop a file directly to your screen:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = req.fileName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val bytesLabel = if (req.fileSize > 1024 * 1024) {
                                String.format("%.2f MB", req.fileSize / (1024.0 * 1024.0))
                            } else {
                                String.format("%.2f KB", req.fileSize / 1024.0)
                            }
                            Text(
                                text = "Size: $bytesLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Sender: ${req.senderName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "From IP: ${req.peerIp}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { req.onDecision(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Accept Drop")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { req.onDecision(false) }
                ) {
                    Text("Decline")
                }
            }
        )
    }

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
            composable("settings") { SettingsScreen(viewModel) }
        }
    }
}

@Composable
fun WaveDropBottomBar(navController: NavHostController) {
    val items = listOf("shared_space", "devices", "settings")
    val icons = listOf(Icons.Default.Home, Icons.Default.WifiTethering, Icons.Default.Settings)
    val labels = listOf("Space", "Nearby", "Settings")

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        items.forEachIndexed { index, route ->
            NavigationBarItem(
                icon = { Icon(icons[index], contentDescription = labels[index]) },
                label = { Text(labels[index]) },
                selected = currentRoute == route,
                modifier = Modifier.testTag("nav_item_${route}"),
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
