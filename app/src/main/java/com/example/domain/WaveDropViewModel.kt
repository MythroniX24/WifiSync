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

    data class IncomingRequest(
        val peerIp: String,
        val fileName: String,
        val fileSize: Long,
        val senderName: String,
        val onDecision: (Boolean) -> Unit
    )

    private val _incomingRequest = MutableStateFlow<IncomingRequest?>(null)
    val incomingRequest: StateFlow<IncomingRequest?> = _incomingRequest.asStateFlow()

    // Dynamic default name using phone manufacturer and model
    private val defaultDeviceName: String = run {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty().trim()
        val model = android.os.Build.MODEL.orEmpty().trim()
        val capitalizedMfg = if (manufacturer.isNotEmpty()) {
            manufacturer.substring(0, 1).uppercase() + manufacturer.substring(1)
        } else {
            "Android Device"
        }
        if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$capitalizedMfg $model"
        }
    }

    private val _deviceName = MutableStateFlow(defaultDeviceName)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _autoAccept = MutableStateFlow(true)
    val autoAccept: StateFlow<Boolean> = _autoAccept.asStateFlow()

    private val _encryptionEnabled = MutableStateFlow(false)
    val encryptionEnabled: StateFlow<Boolean> = _encryptionEnabled.asStateFlow()

    // Incoming Drop popup states
    data class IncomingDropEvent(
        val fileName: String,
        val fileSize: Long,
        val senderName: String,
        val localFilePath: String? = null
    )

    private val _incomingDropEvent = MutableStateFlow<IncomingDropEvent?>(null)
    val incomingDropEvent: StateFlow<IncomingDropEvent?> = _incomingDropEvent.asStateFlow()

    fun dismissIncomingDropEvent() {
        _incomingDropEvent.value = null
    }

    // Combined devices list
    val devices: StateFlow<List<DeviceEntity>> = nsdDiscoveryManager.discoveredDevices
        .combine(repository.devices) { nsdList, repoList ->
            val list = mutableListOf<DeviceEntity>()
            nsdList.forEach { nsd ->
                val ip = nsd.host?.hostAddress ?: "Unknown IP"
                val cleanName = if (nsd.serviceName.contains("_")) {
                    val lastPart = nsd.serviceName.substringAfterLast("_")
                    if (lastPart.all { it.isDigit() }) {
                        nsd.serviceName.substringBeforeLast("_")
                    } else {
                        nsd.serviceName
                    }
                } else {
                    nsd.serviceName
                }
                list.add(DeviceEntity(id = nsd.serviceName, name = "$cleanName ($ip)"))
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

    val localIpAddress: String = retrieveLocalIpAddress()

    init {
        // Start background sync routine & Server Socket
        viewModelScope.launch(Dispatchers.IO) {
            startServer()
        }
        viewModelScope.launch(Dispatchers.IO) {
            startHttpServer()
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
                    s.tcpNoDelay = true
                    s.sendBufferSize = 1024 * 1024
                    s.receiveBufferSize = 1024 * 1024
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
                                    val buffer = ByteArray(512 * 1024)
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
                        val senderName = parts.getOrNull(2)?.replace("_", " ") ?: "Unknown Device"
                        
                        val peerIp = socket.inetAddress?.hostAddress ?: "Unknown Host"
                        val decisionDeferred = CompletableDeferred<Boolean>()
                        _incomingRequest.value = IncomingRequest(
                            peerIp = peerIp,
                            fileName = fileName,
                            fileSize = fileSize,
                            senderName = senderName,
                            onDecision = { accepted ->
                                decisionDeferred.complete(accepted)
                            }
                        )
                        
                        val isAccepted = try {
                            withTimeout(60000) {
                                decisionDeferred.await()
                            }
                        } catch (e: Exception) {
                            false
                        } finally {
                            _incomingRequest.value = null
                        }
                        
                        if (isAccepted) {
                            val context = getApplication<Application>()
                            val downloadDir = File(context.filesDir, "shared_downloads").apply { mkdirs() }
                            val targetFile = File(downloadDir, "${System.currentTimeMillis()}_${fileName}")
                            
                            output.write("READY\n".toByteArray())
                            output.flush()
                            
                            var bytesCopied = 0L
                            targetFile.outputStream().use { outFil ->
                                val buffer = ByteArray(512 * 1024)
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
                                ownerDeviceName = senderName,
                                localFilePath = targetFile.absolutePath,
                                fileExtension = fileExt.lowercase()
                            )
                            repository.addSharedFile(entity)
                            
                            repository.addTransferHistory(
                                TransferHistoryEntity(
                                    fileName = fileName,
                                    fileSize = bytesCopied,
                                    isIncoming = true,
                                    peerName = senderName,
                                    status = "COMPLETED"
                                )
                            )
                        } else {
                            output.write("DENIED\n".toByteArray())
                            output.flush()
                            
                            repository.addTransferHistory(
                                TransferHistoryEntity(
                                    fileName = fileName,
                                    fileSize = fileSize,
                                    isIncoming = true,
                                    peerName = senderName,
                                    status = "REJECTED"
                                )
                            )
                        }
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
        nsdDiscoveryManager.registerService(port, _deviceName.value)
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
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 1048576
                    socket.receiveBufferSize = 1048576
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
                            val buffer = ByteArray(512 * 1024)
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
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 1048576
                    socket.receiveBufferSize = 1048576
                    socket.connect(java.net.InetSocketAddress(ipPart, 8888), 6000)
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()

                    val cleanName = fileName.replace(" ", "_")
                    val cleanSender = _deviceName.value.replace(" ", "_").trim()
                    output.write("DIRECT_SEND $cleanName $fileSize $cleanSender\n".toByteArray())
                    output.flush()

                    val response = readHeaderLine(input).trim()
                    if (response == "READY") {
                        context.contentResolver.openInputStream(uri)?.use { fileIn ->
                            val buffer = ByteArray(512 * 1024)
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

    // Windows / Computer Web Hub Embedded Server
    private suspend fun startHttpServer() {
        var serverSocket: ServerSocket? = null
        while (currentCoroutineContext().isActive) {
            try {
                serverSocket = ServerSocket(9999)
                Log.d("WaveDropSync", "HTTP Laptop Web Portal opened on port 9999")
                while (currentCoroutineContext().isActive) {
                    val client = serverSocket.accept()
                    handleHttpClient(client)
                }
            } catch (e: Exception) {
                Log.e("WaveDropSync", "HTTP Server error", e)
                try { serverSocket?.close() } catch (ex: Exception) {}
                serverSocket = null
                if (currentCoroutineContext().isActive) delay(5000)
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                socket.use { s ->
                    s.soTimeout = 60000
                    s.tcpNoDelay = true
                    s.sendBufferSize = 1024 * 1024
                    s.receiveBufferSize = 1024 * 1024
                    val os = s.getOutputStream()
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    
                    val reqLine = reader.readLine() ?: return@use
                    val parts = reqLine.split(" ")
                    if (parts.size < 2) return@use
                    val method = parts[0]
                    val path = parts[1]
                    
                    var contentLength = 0L
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isEmpty()) break
                        if (line!!.lowercase().startsWith("content-length:")) {
                            contentLength = line!!.substringAfter(":").trim().toLongOrNull() ?: 0L
                        }
                    }
                    
                    if (method == "GET" && path == "/") {
                        val html = buildHttpDashboardHtml()
                        val htmlBytes = html.toByteArray(Charsets.UTF_8)
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${htmlBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        os.write(response.toByteArray(Charsets.UTF_8))
                        os.write(htmlBytes)
                        os.flush()
                    } else if (method == "GET" && path.startsWith("/download")) {
                        val fileId = path.substringAfter("id=").substringBefore("&")
                        val matchedFile = sharedFiles.value.firstOrNull { it.id == fileId && it.isLocal }
                        if (matchedFile != null) {
                            if (matchedFile.content != null) {
                                val bytes = matchedFile.content.toByteArray(Charsets.UTF_8)
                                val response = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/plain; charset=utf-8\r\n" +
                                        "Content-Disposition: attachment; filename=\"${matchedFile.name}\"\r\n" +
                                        "Content-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                os.write(response.toByteArray(Charsets.UTF_8))
                                os.write(bytes)
                                os.flush()
                            } else if (matchedFile.localFilePath != null) {
                                val dFile = File(matchedFile.localFilePath)
                                if (dFile.exists()) {
                                    val response = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: application/octet-stream\r\n" +
                                            "Content-Disposition: attachment; filename=\"${matchedFile.name}\"\r\n" +
                                            "Content-Length: ${dFile.length()}\r\n" +
                                            "Connection: close\r\n\r\n"
                                    os.write(response.toByteArray(Charsets.UTF_8))
                                    
                                    val buffer = ByteArray(512 * 1024)
                                    dFile.inputStream().use { fileIn ->
                                        var bytesRead: Int
                                        while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                                            os.write(buffer, 0, bytesRead)
                                        }
                                    }
                                    os.flush()
                                } else {
                                    sendHttpTextResponse(os, 404, "Not Found", "File content not found on host disk.")
                                }
                            } else {
                                sendHttpTextResponse(os, 404, "Not Found", "No local path for file ID.")
                            }
                        } else {
                            sendHttpTextResponse(os, 404, "Not Found", "File not found or remote.")
                        }
                    } else if (method == "POST" && path.startsWith("/upload") && contentLength > 0) {
                        var pName = path.substringAfter("name=", "").substringBefore("&")
                        pName = java.net.URLDecoder.decode(pName, "UTF-8")
                        if (pName.isEmpty()) pName = "computer_upload"
                        
                        val context = getApplication<Application>()
                        val targetDir = File(context.filesDir, "shared_space").apply { mkdirs() }
                        val cleanUuid = java.util.UUID.randomUUID().toString()
                        val targetFile = File(targetDir, "${cleanUuid}_${pName}")
                        
                        var bytesReceived = 0L
                        targetFile.outputStream().use { outFil ->
                            val buffer = ByteArray(512 * 1024)
                            val stream = s.getInputStream()
                            while (bytesReceived < contentLength) {
                                val remaining = contentLength - bytesReceived
                                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                                val amt = stream.read(buffer, 0, toRead)
                                if (amt == -1) break
                                outFil.write(buffer, 0, amt)
                                bytesReceived += amt
                            }
                        }
                        
                        val ext = pName.substringAfterLast('.', "")
                        val entity = SharedFileEntity(
                            id = cleanUuid,
                            name = pName,
                            size = targetFile.length(),
                            isLocal = true,
                            ownerDeviceName = _deviceName.value,
                            parentId = null,
                            localFilePath = targetFile.absolutePath,
                            fileExtension = ext.lowercase()
                        )
                        repository.addSharedFile(entity)
                        
                        repository.addTransferHistory(
                            TransferHistoryEntity(
                                fileName = pName,
                                fileSize = targetFile.length(),
                                isIncoming = true,
                                peerName = "Windows Web Portal",
                                status = "COMPLETED"
                            )
                        )
                        
                        val jsonRes = "{\"success\": true}"
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${jsonRes.length}\r\n" +
                                "Connection: close\r\n\r\n" +
                                jsonRes
                        os.write(response.toByteArray(Charsets.UTF_8))
                        os.flush()
                    } else {
                        sendHttpTextResponse(os, 404, "Not Found", "Page not found.")
                    }
                }
            } catch (e: Exception) {
                Log.e("WaveDropHttp", "HTTP Connection exception", e)
            }
        }
    }

    private fun sendHttpTextResponse(os: java.io.OutputStream, code: Int, status: String, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $code $status\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            os.write(header.toByteArray(Charsets.UTF_8))
            os.write(bytes)
            os.flush()
        } catch (e: Exception) {}
    }

    private fun buildHttpDashboardHtml(): String {
        val list = sharedFiles.value.filter { it.isLocal }
        val sb = java.lang.StringBuilder()
        if (list.isEmpty()) {
            sb.append("<tr><td colspan='4' style='text-align:center; color:#94a3b8; padding: 24px 0;'>No files uploaded to Space yet. Use the card above to drag and drop!</td></tr>")
        } else {
            list.forEach { file ->
                val sizeStr = if (file.size > 1024 * 1024) {
                    String.format("%.2f MB", file.size / (1024.0 * 1024.0))
                } else if (file.size > 1024) {
                    String.format("%.2f KB", file.size / 1024.0)
                } else {
                    "${file.size} Bytes"
                }
                sb.append("<tr>")
                sb.append("<td style='font-weight:600;'>${file.name}</td>")
                sb.append("<td style='color:#94a3b8;'>${file.ownerDeviceName}</td>")
                sb.append("<td style='color:#94a3b8;'>$sizeStr</td>")
                sb.append("<td class='actions'><a href='/download?id=${file.id}' class='dl-link'>Download</a></td>")
                sb.append("</tr>")
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>WaveDrop Windows Portal</title>
                <style>
                    :root {
                        --bg-color: #0f172a;
                        --card-color: #1e293b;
                        --accent-color: #3b82f6;
                        --text-color: #f1f5f9;
                        --subtitle-color: #94a3b8;
                        --border-color: #334155;
                        --hover-color: #2563eb;
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                        background-color: var(--bg-color);
                        color: var(--text-color);
                        margin: 0;
                        padding: 24px;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                    }
                    .container {
                        width: 100%;
                        max-width: 800px;
                    }
                    h1, h2 {
                        margin: 0 0 8px 0;
                    }
                    p {
                        color: var(--subtitle-color);
                        margin: 0 0 24px 0;
                    }
                    .card {
                        background-color: var(--card-color);
                        border-radius: 16px;
                        border: 1px solid var(--border-color);
                        padding: 24px;
                        margin-bottom: 24px;
                        box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
                    }
                    .device-info {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                    }
                    .badge {
                        background-color: var(--accent-color);
                        color: white;
                        padding: 6px 12px;
                        border-radius: 20px;
                        font-size: 0.85rem;
                        font-weight: 600;
                    }
                    .dropzone {
                        border: 2px dashed #475569;
                        border-radius: 12px;
                        padding: 40px;
                        text-align: center;
                        cursor: pointer;
                        transition: all 0.2s ease;
                    }
                    .dropzone:hover {
                        border-color: var(--accent-color);
                        background-color: rgba(59, 130, 246, 0.05);
                    }
                    .btn {
                        background-color: var(--accent-color);
                        color: white;
                        border: none;
                        padding: 10px 20px;
                        border-radius: 8px;
                        font-weight: 600;
                        cursor: pointer;
                        transition: background 0.2s;
                    }
                    .btn:hover {
                        background-color: var(--hover-color);
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin-top: 16px;
                    }
                    th, td {
                        text-align: left;
                        padding: 14px 16px;
                        border-bottom: 1px solid var(--border-color);
                    }
                    th {
                        color: var(--subtitle-color);
                        font-size: 0.85rem;
                        text-transform: uppercase;
                        letter-spacing: 0.05em;
                    }
                    tr:hover td {
                        background-color: rgba(255, 255, 255, 0.02);
                    }
                    .actions {
                        text-align: right;
                    }
                    a.dl-link {
                        color: var(--accent-color);
                        text-decoration: none;
                        font-weight: 500;
                    }
                    a.dl-link:hover {
                        text-decoration: underline;
                    }
                    .progress-container {
                        width: 100%;
                        background-color: var(--border-color);
                        border-radius: 8px;
                        height: 10px;
                        margin-top: 16px;
                        display: none;
                        overflow: hidden;
                    }
                    .progress-bar {
                        height: 100%;
                        background-color: var(--accent-color);
                        width: 0%;
                        transition: width 0.1s linear;
                    }
                    .status-text {
                        font-size: 0.9rem;
                        color: var(--subtitle-color);
                        margin-top: 8px;
                        display: none;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="card device-info">
                        <div>
                            <h1>WaveDrop Laptop Share</h1>
                            <p style="margin:0">Direct local files hub over Wi-Fi</p>
                        </div>
                        <span class="badge">Connected: ${_deviceName.value}</span>
                    </div>

                    <div class="card">
                        <h2>Upload files to Space</h2>
                        <p>Upload a file from your Windows laptop directly to the phone's Space (max speeds inside 1s!)</p>
                        <div class="dropzone" id="dropzone" onclick="document.getElementById('fileInput').click()">
                            <svg width="48" height="48" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" style="margin: 0 auto 12px auto; color: var(--subtitle-color)">
                                <path stroke-linecap="round" stroke-linejoin="round" d="M3 15a4 4 0 004 4h9a5 5 0 10-.1-9.999 5.002 5.002 0 10-9.78 2.096A4.001 4.001 0 003 15z"></path>
                                <path stroke-linecap="round" stroke-linejoin="round" d="M12 5v11m0 0l-3-3m3 3l3-3"></path>
                            </svg>
                            <div>Click or Drag and Drop any file to upload</div>
                            <input type="file" id="fileInput" style="display:none" onchange="uploadFiles()">
                        </div>
                        <div class="progress-container" id="progContainer">
                            <div class="progress-bar" id="progressBar"></div>
                        </div>
                        <div class="status-text" id="statusText">Uploading file...</div>
                    </div>

                    <div class="card">
                        <h2>Current Space Storage Files</h2>
                        <p>Files in the local phone space shared directory</p>
                        <table>
                            <thead>
                                <tr>
                                    <th>Name</th>
                                    <th>Device Author</th>
                                    <th>File Size</th>
                                    <th class="actions">Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                $sb
                            </tbody>
                        </table>
                    </div>
                </div>

                <script>
                    const dropzone = document.getElementById('dropzone');
                    
                    ['dragenter', 'dragover'].forEach(eventName => {
                        dropzone.addEventListener(eventName, e => {
                            e.preventDefault();
                            dropzone.style.borderColor = "#3b82f6";
                            dropzone.style.backgroundColor = "rgba(59, 130, 246, 0.05)";
                        }, false);
                    });

                    ['dragleave', 'drop'].forEach(eventName => {
                        dropzone.addEventListener(eventName, e => {
                            e.preventDefault();
                            dropzone.style.borderColor = "#475569";
                            dropzone.style.backgroundColor = "transparent";
                        }, false);
                    });

                    dropzone.addEventListener('drop', e => {
                        const dt = e.dataTransfer;
                        const files = dt.files;
                        if (files.length > 0) {
                            document.getElementById('fileInput').files = files;
                            uploadFiles();
                        }
                    });

                    function uploadFiles() {
                        const fileInput = document.getElementById('fileInput');
                        if (fileInput.files.length === 0) return;
                        const file = fileInput.files[0];
                        
                        const progContainer = document.getElementById('progContainer');
                        const progressBar = document.getElementById('progressBar');
                        const statusText = document.getElementById('statusText');
                        
                        progContainer.style.display = 'block';
                        statusText.style.display = 'block';
                        statusText.innerText = 'Uploading ' + file.name + '...';
                        progressBar.style.width = '0%';

                        const xhr = new XMLHttpRequest();
                        xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);
                        
                        xhr.upload.onprogress = function(e) {
                            if (e.lengthComputable) {
                                const percent = Math.round((e.loaded / e.total) * 100);
                                progressBar.style.width = percent + '%';
                                statusText.innerText = 'Uploading: ' + percent + '% (' + formatBytes(e.loaded) + ' / ' + formatBytes(e.total) + ')';
                            }
                        };
                        
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                statusText.innerText = 'Upload successful!';
                                setTimeout(() => {
                                    window.location.reload();
                                }, 500);
                            } else {
                                statusText.innerText = 'Upload failed: ' + xhr.responseText;
                            }
                        };
                        
                        xhr.onerror = function() {
                            statusText.innerText = 'Network error occurred.';
                        };
                        
                        xhr.send(file);
                    }

                    function formatBytes(bytes) {
                        if (bytes === 0) return '0 Bytes';
                        const k = 1024;
                        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun retrieveLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
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
