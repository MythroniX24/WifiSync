package com.example.data

import kotlinx.coroutines.flow.Flow

class WaveDropRepository(private val dao: WaveDropDao) {
    val devices: Flow<List<DeviceEntity>> = dao.getAllDevices()
    val transferHistory: Flow<List<TransferHistoryEntity>> = dao.getTransferHistory()
    val sharedFiles: Flow<List<SharedFileEntity>> = dao.getSharedFiles()

    suspend fun addDevice(device: DeviceEntity) {
        dao.insertDevice(device)
    }

    suspend fun deleteDevice(deviceId: String) {
        dao.deleteDevice(deviceId)
    }

    suspend fun clearAllDevices() {
        dao.clearAllDevices()
    }

    suspend fun addTransferHistory(history: TransferHistoryEntity) {
        dao.insertTransferHistory(history)
    }

    suspend fun addSharedFile(file: SharedFileEntity) {
        dao.insertSharedFile(file)
    }

    suspend fun deleteSharedFile(file: SharedFileEntity) {
        dao.deleteSharedFile(file)
    }

    suspend fun clearRemoteFiles() {
        dao.clearRemoteFiles()
    }
}
