package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.WaveDropRepository
import com.example.domain.WaveDropViewModel
import com.example.domain.WaveDropViewModelFactory
import com.example.ui.WaveDropAppScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private val viewModel: WaveDropViewModel by viewModels {
    WaveDropViewModelFactory(application, WaveDropRepository())
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        WaveDropAppScreen(viewModel)
      }
    }
  }
}
