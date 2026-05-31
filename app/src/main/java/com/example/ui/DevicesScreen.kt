package com.example.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DeviceEntity
import com.example.domain.WaveDropViewModel
import androidx.compose.ui.platform.testTag

@Composable
fun DevicesScreen(viewModel: WaveDropViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedDeviceForSend by remember { mutableStateOf<DeviceEntity?>(null) }

    val directSendLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val device = selectedDeviceForSend
        if (uri != null && device != null) {
            var fileName = "Unnamed_Payload"
            var fileSize = 0L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            Toast.makeText(context, "Connecting to direct drop of $fileName...", Toast.LENGTH_SHORT).show()
            viewModel.sendDirectFileToPeer(device, uri, fileName, fileSize) { success, msg ->
                Toast.makeText(context, msg ?: (if (success) "Files sent successfully" else "Direct drop failed"), Toast.LENGTH_LONG).show()
            }
        }
        selectedDeviceForSend = null
    }

    // Real trigger of network service discovery when the screen is viewed
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose {
            viewModel.stopDiscovery()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Nearby Devices", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Searching via NSD over local Wi-Fi / LAN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { 
                        viewModel.clearOfflineDevices()
                        Toast.makeText(context, "Cleared prior offline entries", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("clear_cached_devices_button")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Cached Devices", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(
                    onClick = { 
                        viewModel.stopDiscovery()
                        viewModel.startDiscovery()
                        Toast.makeText(context, "Scanning local Wi-Fi", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("refresh_discovery_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Scan Network Now")
                }
            }
        }
        
        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val transition = rememberInfiniteTransition(label = "pulse_radar")
                    val pulseScale by transition.animateFloat(
                        initialValue = 0.8f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulsescale"
                    )
                    
                    Box(
                        modifier = Modifier
                            .scale(pulseScale)
                            .size(72.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsInputAntenna,
                            contentDescription = "Scanning...",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Listening on current Wi-Fi subnets...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Ensure other devices are running WaveDrop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedDeviceForSend = device
                                directSendLauncher.launch("*/*")
                            }
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer, contentDescription = "Device", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(device.name, fontWeight = FontWeight.SemiBold)
                                Text("Click to drop / transfer a files directly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
