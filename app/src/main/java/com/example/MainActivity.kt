package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.DatabaseProvider
import com.example.data.WaveDropRepository
import com.example.domain.WaveDropViewModel
import com.example.domain.WaveDropViewModelFactory
import com.example.ui.WaveDropAppScreen
import com.example.ui.theme.MyApplicationTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val allGranted = permissions.entries.all { it.value }
    if (allGranted) {
      viewModel.startDiscovery()
    }
  }

  private val viewModel: WaveDropViewModel by viewModels {
    val database = DatabaseProvider.getDatabase(applicationContext)
    WaveDropViewModelFactory(application, WaveDropRepository(database.waveDropDao()))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    checkPermissions()

    setContent {
      MyApplicationTheme {
        WaveDropAppScreen(viewModel)
      }
    }
  }

  private fun checkPermissions() {
    val permissions = mutableListOf<String>()
    
    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) {
      permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
      permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val missing = permissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    if (missing.isNotEmpty()) {
      requestPermissionLauncher.launch(missing.toTypedArray())
    } else {
      viewModel.startDiscovery()
    }
  }
}
