package com.example.domain

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.DeviceEntity
import com.example.data.SharedFileEntity
import com.example.data.TransferHistoryEntity
import com.example.data.WaveDropRepository
import com.example.discovery.NsdDiscoveryManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log
import java.net.Socket
import java.net.ServerSocket
import java.io.PrintWriter
import java.io.File
import java.io.InputStream
import java.util.Scanner

class WaveDropViewModel(application: Application, private val repository: WaveDropRepository) : AndroidViewModel(application) {
    private val nsdDiscoveryManager = NsdDiscoveryManager(application)

    // Settings States
    private val _deviceName = MutableStateFlow("My Android Device")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _autoAccept = MutableStateFlow(true)
    val autoAccept: StateFlow<Boolean> = _autoAccept.asStateFlow()

    private val _encryptionEnabled = MutableStateFlow(false)
    val encryptionEnabled: StateFlow<Boolean> = _encryptionEnabled.asStateFlow()

    // Combined devices list
    val devices: StateFlow<List<DeviceEntity>> = nsdDiscoveryManager.discoveredDevices
        .combine(repository.devices) { nsdList, repoList ->
            val list = mutableListOf<DeviceEntity>()
            nsdList.forEach { nsd ->
                val ip = nsd.host?.hostAddress ?: "Unknown IP"
                list.add(DeviceEntity(id = nsd.serviceName, name = "${nsd.serviceName} ($ip)"))
            }
            repoList.forEach { repo ->
                if (list.none { it.id == repo.id }) list.add(repo)
            }
            list
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history = repository.transferHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val sharedFiles = repository.sharedFiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val sharedFileListAdapter = moshi.adapter<List<SharedFileEntity>>(
        Types.newParameterizedType(List::class.java, SharedFileEntity::class.java)
    )

    init {
        // Start background sync routine & Server Socket
        viewModelScope.launch(Dispatchers.IO) {
            startServer()
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Periodically clear old remote files and fetch new ones
            while (isActive) {
                val currentDevices = devices.value
                // Only clear if we actually have devices to sync with
                if (currentDevices.isNotEmpty()) {
                    repository.clearRemoteFiles()
                    currentDevices.forEach { device ->
                        launch { fetchMetadataFromPeer(device) }
                    }
                }
                delay(15000) // Poll every 15 seconds
            }
        }
    }

    private suspend fun startServer() {
        var serverSocket: ServerSocket? = null
        while (currentCoroutineContext().isActive) {
            try {
                serverSocket = ServerSocket(8888)
                Log.d("WaveDropSync", "Server socket opened on 8888")
                while (currentCoroutineContext().isActive) {
                    val client = serverSocket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e("WaveDropSync", "Server error", e)
                try { serverSocket?.close() } catch (ex: Exception) {}
                serverSocket = null
                if (currentCoroutineContext().isActive) delay(5000)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                socket.use { s ->
                    s.soTimeout = 15000
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    
                    val request = readHeaderLine(input).trim()
                    Log.d("WaveDropSync", "Received request: $request")
                    
                    if (request == "GET_METADATA") {
                        val localFiles = sharedFiles.value.filter { it.isLocal }
                        val json = sharedFileListAdapter.toJson(localFiles)
                        output.write((json + "\n").toByteArray())
                        output.flush()
                    } else if (request.startsWith("DOWNLOAD_FILE")) {
                        val fileId = request.substringAfter("DOWNLOAD_FILE").trim()
                        val localFiles = sharedFiles.value.filter { it.isLocal }
                        val matched = localFiles.firstOrNull { it.id == fileId }
                        if (matched != null) {
                            if (matched.content != null) {
                                val bytes = matched.content.toByteArray()
                                output.write("OK ${bytes.size}\n".toByteArray())
                                output.write(bytes)
                                output.flush()
                            } else if (matched.localFilePath != null) {
                                val file = File(matched.localFilePath)
                                if (file.exists()) {
                                    output.write("OK ${file.length()}\n".toByteArray())
                                    val buffer = ByteArray(8192)
                                    file.inputStream().use { fileIn ->
                                        var read: Int
                                        while (fileIn.read(buffer).also { read = it } != -1) {
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    output.flush()
                                } else {
                                    output.write("ERROR File not found on disk\n".toByteArray())
                                    output.flush()
                                }
                            } else {
                                output.write("ERROR No data available\n".toByteArray())
                                output.flush()
                            }
                        } else {
                            output.write("ERROR File not found\n".toByteArray())
                            output.flush()
                        }
                    } else if (request.startsWith("DIRECT_SEND")) {
                        val parts = request.substringAfter("DIRECT_SEND").trim().split(" ")
                        val fileName = parts.firstOrNull()?.replace("_", " ") ?: "received_file"
                        val fileSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        
                        val context = getApplication<Application>()
                        val downloadDir = File(context.filesDir, "shared_downloads").apply { mkdirs() }
                        val targetFile = File(downloadDir, "${System.currentTimeMillis()}_${fileName}")
                        
                        output.write("READY\n".toByteArray())
                        output.flush()
                        
                        var bytesCopied = 0L
                        targetFile.outputStream().use { outFil ->
                            val buffer = ByteArray(8192)
                            while (bytesCopied < fileSize) {
                                val remaining = fileSize - bytesCopied
                                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                                val amt = input.read(buffer, 0, toRead)
                                if (amt == -1) break
                                outFil.write(buffer, 0, amt)
                                bytesCopied += amt
                            }
                        }
                        
                        val fileExt = fileName.substringAfterLast('.', "")
                        val entity = SharedFileEntity(
                            name = fileName,
                            size = bytesCopied,
                            isLocal = true,
                            ownerDeviceName = "Direct Drop",
                            localFilePath = targetFile.absolutePath,
                            fileExtension = fileExt.lowercase()
                        )
                        repository.addSharedFile(entity)
                        
                        repository.addTransferHistory(
                            TransferHistoryEntity(
                                fileName = fileName,
                                fileSize = bytesCopied,
                                isIncoming = true,
                                peerName = "Direct Push",
                                status = "COMPLETED"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("WaveDropSync", "Handling error", e)
            }
        }
    }

    private suspend fun fetchMetadataFromPeer(device: DeviceEntity) {
        val ipPart = device.name.substringAfterLast("(").substringBefore(")")
        if (ipPart == "Unknown IP" || ipPart == device.name) return

        try {
            Socket().use { socket ->
                socket.soTimeout = 5000
                socket.connect(java.net.InetSocketAddress(ipPart, 8888), 3000)
                val writer = PrintWriter(socket.getOutputStream(), true)
                val input = socket.getInputStream()
                
                writer.println("GET_METADATA")
                val json = readHeaderLine(input).trim()
                if (json.isNotEmpty() && !json.startsWith("ERROR")) {
                    val remoteFiles = sharedFileListAdapter.fromJson(json) ?: emptyList()
                    remoteFiles.forEach { file ->
                        repository.addSharedFile(file.copy(isLocal = false, ownerDeviceName = device.name))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WaveDropSync", "Failed to sync with ${device.name}", e)
        }
    }

    fun startDiscovery() {
        nsdDiscoveryManager.discoverServices()
        registerLocalService(8888) 
    }

    fun stopDiscovery() {
        nsdDiscoveryManager.stopDiscovery()
        nsdDiscoveryManager.unregisterService()
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

    // Direct upload copying selected Uri to internal app space
    fun addLocalFileFromUri(uri: Uri, fileName: String, fileSize: Long, parentId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val targetDir = File(context.filesDir, "shared_space").apply { mkdirs() }
                val cleanUuid = java.util.UUID.randomUUID().toString()
                val targetFile = File(targetDir, "${cleanUuid}_${fileName}")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val ext = fileName.substringAfterLast('.', "")
                val entity = SharedFileEntity(
                    id = cleanUuid,
                    name = fileName,
                    size = targetFile.length(),
                    isLocal = true,
                    ownerDeviceName = _deviceName.value,
                    parentId = parentId,
                    localFilePath = targetFile.absolutePath,
                    fileExtension = ext.lowercase()
                )
                repository.addSharedFile(entity)

                repository.addTransferHistory(
                    TransferHistoryEntity(
                        fileName = fileName,
                        fileSize = targetFile.length(),
                        isIncoming = false,
                        peerName = "Local Space",
                        status = "COMPLETED"
                    )
                )
            } catch (e: Exception) {
                Log.e("WaveDropSync", "Error adding local file payload", e)
            }
        }
    }

    // Direct network download from another peer's shared file list
    fun downloadSharedFile(file: SharedFileEntity, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ipPart = file.ownerDeviceName.substringAfterLast("(").substringBefore(")")
            if (ipPart == "Unknown IP" || ipPart == file.ownerDeviceName) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Unknown IP or file is already local.")
                }
                return@launch
            }

            try {
                Socket().use { socket ->
                    socket.soTimeout = 12000
                    socket.connect(java.net.InetSocketAddress(ipPart, 8888), 4000)
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()

                    output.write("DOWNLOAD_FILE ${file.id}\n".toByteArray())
                    output.flush()

                    val header = readHeaderLine(input).trim()
                    Log.d("WaveDropSync", "Download header response: $header")
                    if (header.startsWith("OK")) {
                        val size = header.substringAfter("OK").trim().toLongOrNull() ?: file.size
                        
                        val context = getApplication<Application>()
                        val downloadDir = File(context.filesDir, "shared_downloads").apply { mkdirs() }
                        val downloadedFile = File(downloadDir, file.name)

                        var bytesCopied = 0L
                        downloadedFile.outputStream().use { fileOut ->
                            val buffer = ByteArray(8192)
                            while (bytesCopied < size) {
                                val remaining = size - bytesCopied
                                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                                val amt = input.read(buffer, 0, toRead)
                                if (amt == -1) break
                                fileOut.write(buffer, 0, amt)
                                bytesCopied += amt
                            }
                        }

                        val ext = file.name.substringAfterLast('.', "")
                        val localEntity = file.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            isLocal = true,
                            ownerDeviceName = _deviceName.value,
                            localFilePath = downloadedFile.absolutePath,
                            fileExtension = ext.lowercase()
                        )
                        repository.addSharedFile(localEntity)

                        repository.addTransferHistory(
                            TransferHistoryEntity(
                                fileName = file.name,
                                fileSize = size,
                                isIncoming = true,
                                peerName = file.ownerDeviceName,
                                status = "COMPLETED"
                            )
                        )

                        withContext(Dispatchers.Main) {
                            onResult(true, "Successfully downloaded to space!")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Download failed: $header")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WaveDropSync", "Download error", e)
                withContext(Dispatchers.Main) {
                    onResult(false, "P2P connection error: ${e.message}")
                }
            }
        }
    }

    // Direct file send from "Nearby" to another device
    fun sendDirectFileToPeer(device: DeviceEntity, uri: Uri, fileName: String, fileSize: Long, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ipPart = device.name.substringAfterLast("(").substringBefore(")")
            if (ipPart == "Unknown IP" || ipPart == device.name) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Device has no valid local IP address.")
                }
                return@launch
            }
            try {
                val context = getApplication<Application>()
                Socket().use { socket ->
                    socket.soTimeout = 20000
                    socket.connect(java.net.InetSocketAddress(ipPart, 8888), 6000)
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()

                    val cleanName = fileName.replace(" ", "_")
                    output.write("DIRECT_SEND $cleanName $fileSize\n".toByteArray())
                    output.flush()

                    val response = readHeaderLine(input).trim()
                    if (response == "READY") {
                        context.contentResolver.openInputStream(uri)?.use { fileIn ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (fileIn.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }
                        output.flush()

                        repository.addTransferHistory(
                            TransferHistoryEntity(
                                fileName = fileName,
                                fileSize = fileSize,
                                isIncoming = false,
                                peerName = device.name,
                                status = "COMPLETED"
                            )
                        )

                        withContext(Dispatchers.Main) {
                            onResult(true, "Files delivered to peer successfully!")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Peer denied transfer: $response")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WaveDropSync", "Error sending direct drop", e)
                withContext(Dispatchers.Main) {
                    onResult(false, "Network connection failed: ${e.message}")
                }
            }
        }
    }

    // Toggle items as Favorites
    fun toggleFavorite(file: SharedFileEntity) {
        viewModelScope.launch {
            repository.addSharedFile(file.copy(isFavorite = !file.isFavorite))
        }
    }

    // Delete folder or file and cleanup recursively
    fun deleteFileOrFolder(file: SharedFileEntity) {
        viewModelScope.launch {
            if (file.isFolder) {
                deleteFolderContents(file.id)
            }
            file.localFilePath?.let { path ->
                try { File(path).delete() } catch (e: Exception) {}
            }
            repository.deleteSharedFile(file)
        }
    }

    private suspend fun deleteFolderContents(folderId: String) {
        val childFiles = sharedFiles.value.filter { it.parentId == folderId }
        childFiles.forEach { child ->
            if (child.isFolder) {
                deleteFolderContents(child.id)
            }
            child.localFilePath?.let { path ->
                try { File(path).delete() } catch (e: Exception) {}
            }
            repository.deleteSharedFile(child)
        }
    }

    // Purge cached offline/stale devices
    fun clearOfflineDevices() {
        viewModelScope.launch {
            repository.clearAllDevices()
        }
    }

    private fun readHeaderLine(input: InputStream): String {
        val sb = java.lang.StringBuilder()
        var b: Int
        while (input.read().also { b = it } != -1) {
            if (b == '\n'.code) {
                break
            }
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    fun addOutgoingTransfer(fileName: String, fileSize: Long, peerName: String, parentId: String? = null) {
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
            repository.addSharedFile(
                SharedFileEntity(
                    name = fileName,
                    size = fileSize,
                    ownerDeviceName = _deviceName.value,
                    isLocal = true,
                    parentId = parentId
                )
            )
        }
    }

    fun createNewTextFile(name: String, content: String, parentId: String? = null) {
        viewModelScope.launch {
            repository.addSharedFile(
                SharedFileEntity(
                    name = if (name.endsWith(".txt")) name else "$name.txt",
                    size = content.length.toLong(),
                    content = content,
                    ownerDeviceName = _deviceName.value,
                    isLocal = true,
                    parentId = parentId,
                    fileExtension = "txt"
                )
            )
        }
    }

    fun createNewFolder(name: String, parentId: String? = null) {
        viewModelScope.launch {
            repository.addSharedFile(
                SharedFileEntity(
                    name = name,
                    size = 0L,
                    ownerDeviceName = _deviceName.value,
                    isLocal = true,
                    isFolder = true,
                    parentId = parentId
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
