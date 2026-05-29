package com.example.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.DeviceEntity
import com.example.data.TransferHistoryEntity
import com.example.data.WaveDropRepository
import com.example.discovery.NsdDiscoveryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WaveDropViewModel(application: Application, private val repository: WaveDropRepository) : AndroidViewModel(application) {
    private val nsdDiscoveryManager = NsdDiscoveryManager(application)

    // Settings States
    private val _deviceName = MutableStateFlow("My Android Device")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _autoAccept = MutableStateFlow(true)
    val autoAccept: StateFlow<Boolean> = _autoAccept.asStateFlow()

    private val _encryptionEnabled = MutableStateFlow(false)
    val encryptionEnabled: StateFlow<Boolean> = _encryptionEnabled.asStateFlow()

    // Combine real discovered NSD Services with standard devices list
    val devices: StateFlow<List<DeviceEntity>> = nsdDiscoveryManager.discoveredDevices
        .combine(repository.devices) { nsdList, repoList ->
            val list = mutableListOf<DeviceEntity>()
            // Map real discovered services
            nsdList.forEach { nsd ->
                val ip = nsd.host?.hostAddress ?: "Unknown IP"
                list.add(DeviceEntity(id = nsd.serviceName, name = "${nsd.serviceName} ($ip)"))
            }
            // Include db devices
            repoList.forEach { repo ->
                if (list.none { it.id == repo.id }) {
                    list.add(repo)
                }
            }
            list
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val history = repository.transferHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun startDiscovery() {
        nsdDiscoveryManager.discoverServices()
    }

    fun stopDiscovery() {
        nsdDiscoveryManager.stopDiscovery()
    }

    fun registerLocalService(port: Int) {
        nsdDiscoveryManager.registerService(port)
    }

    fun updateDeviceName(name: String) {
        _deviceName.value = name
    }

    fun toggleAutoAccept(enabled: Boolean) {
        _autoAccept.value = enabled
    }

    fun toggleEncryption(enabled: Boolean) {
        _encryptionEnabled.value = enabled
    }

    fun addOutgoingTransfer(fileName: String, fileSize: Long, peerName: String) {
        viewModelScope.launch {
            repository.addTransferHistory(
                TransferHistoryEntity(
                    fileName = fileName,
                    fileSize = fileSize,
                    isIncoming = false,
                    peerName = peerName,
                    status = "COMPLETED"
                )
            )
        }
    }
}

class WaveDropViewModelFactory(
    private val application: Application,
    private val repository: WaveDropRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WaveDropViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WaveDropViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
