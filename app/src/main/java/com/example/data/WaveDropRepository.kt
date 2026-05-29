package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class WaveDropRepository(private val dao: WaveDropDao) {
    val devices: Flow<List<DeviceEntity>> = dao.getAllDevices()
    val transferHistory: Flow<List<TransferHistoryEntity>> = dao.getTransferHistory()

    suspend fun addDevice(device: DeviceEntity) = dao.insertDevice(device)
    suspend fun addTransferHistory(history: TransferHistoryEntity) = dao.insertTransferHistory(history)
}
