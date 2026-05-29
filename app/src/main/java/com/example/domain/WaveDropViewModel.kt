package com.example.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.DeviceEntity
import com.example.data.WaveDropRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WaveDropViewModel(private val repository: WaveDropRepository) : ViewModel() {
    val devices: StateFlow<List<DeviceEntity>> = repository.devices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val history = repository.transferHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun addMockDevice() {
        viewModelScope.launch {
            repository.addDevice(DeviceEntity(id = "dev-${System.currentTimeMillis()}", name = "Nearby iPhone ${System.currentTimeMillis() % 100}"))
        }
    }
}

class WaveDropViewModelFactory(private val repository: WaveDropRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WaveDropViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WaveDropViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
