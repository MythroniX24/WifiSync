package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WaveDropRepository {
    private val _devices = MutableStateFlow<List<DeviceEntity>>(emptyList())
    val devices: Flow<List<DeviceEntity>> = _devices.asStateFlow()

    private val _transferHistory = MutableStateFlow<List<TransferHistoryEntity>>(emptyList())
    val transferHistory: Flow<List<TransferHistoryEntity>> = _transferHistory.asStateFlow()

    suspend fun addDevice(device: DeviceEntity) {
        _devices.update { current ->
            val updated = current.toMutableList()
            updated.removeAll { it.id == device.id }
            updated.add(device)
            updated
        }
    }

    suspend fun addTransferHistory(history: TransferHistoryEntity) {
        _transferHistory.update { current ->
            current + history
        }
    }
}
