package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.data.DatabaseProvider
import com.example.data.WaveDropRepository
import com.example.domain.WaveDropViewModel
import com.example.domain.WaveDropViewModelFactory
import com.example.ui.WaveDropAppScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private lateinit var viewModel: WaveDropViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val database = DatabaseProvider.getDatabase(this)
    val repository = WaveDropRepository(database.waveDropDao())
    viewModel = ViewModelProvider(this, WaveDropViewModelFactory(repository))[WaveDropViewModel::class.java]

    setContent {
      MyApplicationTheme {
        WaveDropAppScreen(viewModel)
      }
    }
  }
}

