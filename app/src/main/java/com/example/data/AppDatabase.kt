package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lastSeen: Long = System.currentTimeMillis()
)

@Entity(tableName = "transfer_history")
data class TransferHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val fileSize: Long,
    val isIncoming: Boolean,
    val peerName: String,
    val status: String, // "COMPLETED", "FAILED", "IN_PROGRESS"
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface WaveDropDao {
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getTransferHistory(): Flow<List<TransferHistoryEntity>>

    @Insert
    suspend fun insertTransferHistory(history: TransferHistoryEntity)
}

@Database(entities = [DeviceEntity::class, TransferHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun waveDropDao(): WaveDropDao
}
