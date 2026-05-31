package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.WaveDropViewModel

import androidx.compose.ui.platform.testTag
@Composable
fun SettingsScreen(viewModel: WaveDropViewModel) {
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val autoAccept by viewModel.autoAccept.collectAsStateWithLifecycle()
    val encryptionEnabled by viewModel.encryptionEnabled.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Device Name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = deviceName,
            onValueChange = { viewModel.updateDeviceName(it) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Auto-accept files from known devices", modifier = Modifier.weight(1f))
            Switch(checked = autoAccept, onCheckedChange = { viewModel.toggleAutoAccept(it) }, modifier = Modifier.testTag("auto_accept_switch"))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Local Encryption", modifier = Modifier.weight(1f))
            Switch(checked = encryptionEnabled, onCheckedChange = { viewModel.toggleEncryption(it) }, modifier = Modifier.testTag("encryption_switch"))
        }
    }
}
