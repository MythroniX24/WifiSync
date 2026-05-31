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

@Entity(tableName = "shared_files")
data class SharedFileEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val content: String? = null, // For small text files created in-app
    val timestamp: Long = System.currentTimeMillis(),
    val ownerDeviceName: String = "This Device",
    val isLocal: Boolean = true,
    val isFolder: Boolean = false,
    val parentId: String? = null,
    val localFilePath: String? = null,
    val isFavorite: Boolean = false,
    val fileExtension: String? = null
)

@Dao
interface WaveDropDao {
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Query("DELETE FROM devices")
    suspend fun clearAllDevices()

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getTransferHistory(): Flow<List<TransferHistoryEntity>>

    @Insert
    suspend fun insertTransferHistory(history: TransferHistoryEntity)

    @Query("SELECT * FROM shared_files ORDER BY timestamp DESC")
    fun getSharedFiles(): Flow<List<SharedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSharedFile(file: SharedFileEntity)

    @Delete
    suspend fun deleteSharedFile(file: SharedFileEntity)

    @Query("SELECT * FROM shared_files WHERE isLocal = 1")
    fun getLocalSharedFilesSync(): List<SharedFileEntity>

    @Query("DELETE FROM shared_files WHERE isLocal = 0")
    suspend fun clearRemoteFiles()
}

@Database(entities = [DeviceEntity::class, TransferHistoryEntity::class, SharedFileEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun waveDropDao(): WaveDropDao
}
