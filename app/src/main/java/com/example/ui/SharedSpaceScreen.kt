package com.example.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SharedFileEntity
import com.example.domain.WaveDropViewModel
import androidx.compose.ui.platform.testTag
import java.io.File

@Composable
fun SharedSpaceScreen(viewModel: WaveDropViewModel) {
    val context = LocalContext.current
    val allSharedFiles by viewModel.sharedFiles.collectAsStateWithLifecycle()
    
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showViewContentDialog by remember { mutableStateOf<String?>(null) } 
    var searchQuery by remember { mutableStateOf("") }
    
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var currentFolderName by remember { mutableStateOf<String?>(null) }
    
    // Dialog for file contextual actions
    var activeActionFile by remember { mutableStateOf<SharedFileEntity?>(null) }

    // Dynamic breadcrumb trail back tracker
    val breadcrumbs = remember(allSharedFiles, currentFolderId) {
        val trail = mutableListOf<Pair<String?, String>>()
        var currId: String? = currentFolderId
        while (currId != null) {
            val folder = allSharedFiles.firstOrNull { it.id == currId }
            if (folder != null) {
                trail.add(0, Pair(folder.id, folder.name))
                currId = folder.parentId
            } else {
                break
            }
        }
        trail.add(0, Pair(null, "Space"))
        trail
    }

    // Filter files for current folder path
    val folderSharedFiles = remember(allSharedFiles, currentFolderId) {
        allSharedFiles.filter { it.parentId == currentFolderId }
    }

    // Filter by live search query
    val sharedFiles = remember(folderSharedFiles, searchQuery) {
        if (searchQuery.isBlank()) {
            folderSharedFiles
        } else {
            folderSharedFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    // Folder Size Stats Calculator
    val folderStats = remember(folderSharedFiles) {
        val filesCount = folderSharedFiles.count { !it.isFolder }
        val foldersCount = folderSharedFiles.count { it.isFolder }
        val totalBytes = folderSharedFiles.sumOf { it.size }
        Triple(filesCount, foldersCount, totalBytes)
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            var fileName = "Unnamed_Payload"
            var fileSize = 0L
            try {
                context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            
            // Invoke true local copying so that bytes are preserved internally
            viewModel.addLocalFileFromUri(selectedUri, fileName, fileSize, currentFolderId)
            Toast.makeText(context, "Adding file inside space...", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { showCreateFolderDialog = true },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.testTag("create_folder_fab")
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Create Folder")
                }
                Spacer(modifier = Modifier.height(16.dp))
                SmallFloatingActionButton(
                    onClick = { showCreateFileDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("create_text_file_fab")
                ) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Create Text File")
                }
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(
                    onClick = { fileLauncher.launch("*/*") },
                    modifier = Modifier.testTag("upload_file_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Upload File")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Interactive breadcrumbs navigation bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                if (currentFolderId != null) {
                    IconButton(onClick = {
                        val parent = allSharedFiles.firstOrNull { it.id == currentFolderId }?.parentId
                        currentFolderId = parent
                        currentFolderName = allSharedFiles.firstOrNull { it.id == parent }?.name
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                
                // Horizontal scrollable path breadcrumbs trail
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    breadcrumbs.forEachIndexed { idx, crumb ->
                        if (idx > 0) {
                            Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 14.sp)
                        }
                        Text(
                            text = crumb.second,
                            fontWeight = if (idx == breadcrumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp,
                            color = if (idx == breadcrumbs.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable {
                                    currentFolderId = crumb.first
                                    currentFolderName = if (crumb.first == null) null else crumb.second
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                    }
                }
            }

            // Laptop Sharing Portal Info Card
            val localIp = viewModel.localIpAddress
            if (localIp != "127.0.0.1" && localIp != "Unknown IP") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "💻",
                            fontSize = 26.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(
                                text = "Windows PC Sharing Portal",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Go to http://$localIp:9999 in your laptop browser to download/upload files instantly!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // Real-time live files filter box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search inside this space") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Current Directory Stats Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Directory Status",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Folders: ${folderStats.second} • Files: ${folderStats.first}", style = MaterialTheme.typography.bodySmall)
                        val totalMB = String.format("%.2f KB", folderStats.third / 1024.0)
                        Text("Total weight: $totalMB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (sharedFiles.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primaryContainer)
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matching results" else if (currentFolderId == null) "No files in Shared Space" else "Folder is empty",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sharedFiles) { file ->
                        val containerColor = when {
                            file.isFolder -> MaterialTheme.colorScheme.secondaryContainer
                            file.isFavorite -> MaterialTheme.colorScheme.tertiaryContainer
                            file.isLocal -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                        }

                        val onContainerColor = when {
                            file.isFolder -> MaterialTheme.colorScheme.onSecondaryContainer
                            file.isFavorite -> MaterialTheme.colorScheme.onTertiaryContainer
                            file.isLocal -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }

                        val iconColor = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

                        val fileIcon = remember(file) {
                            when {
                                file.isFolder -> Icons.Default.Folder
                                file.fileExtension in listOf("png", "jpg", "jpeg", "webp") -> Icons.Default.Image
                                file.fileExtension in listOf("mp3", "wav", "ogg") -> Icons.Default.Audiotrack
                                else -> Icons.Default.Description
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (file.isFolder) {
                                        currentFolderId = file.id
                                        currentFolderName = file.name
                                    } else {
                                        activeActionFile = file
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = containerColor,
                                contentColor = onContainerColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = fileIcon, 
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = file.name, 
                                            fontWeight = FontWeight.Bold, 
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f, fill = false),
                                            color = onContainerColor
                                        )
                                        if (file.isFavorite) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(Icons.Default.Favorite, contentDescription = "Favorite", tint = Color.Red, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (!file.isFolder) {
                                        val sourceTag = if (file.isLocal) "Local Space" else "P2P Peer File"
                                        val sizeLabel = if (file.size > 1024 * 1024) {
                                            String.format("%.2f MB", file.size / (1024.0 * 1024.0))
                                        } else if (file.size > 1024) {
                                            String.format("%.2f KB", file.size / 1024.0)
                                        } else {
                                            "${file.size} Bytes"
                                        }
                                        Text(
                                            text = "$sizeLabel • $sourceTag • ${file.ownerDeviceName}", 
                                            style = MaterialTheme.typography.bodySmall,
                                            color = onContainerColor.copy(alpha = 0.8f)
                                        )
                                    } else {
                                        Text(
                                            text = "Folder • Owner: ${file.ownerDeviceName}", 
                                            style = MaterialTheme.typography.bodySmall,
                                            color = onContainerColor.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                                
                                // Mini Action click icon
                                IconButton(onClick = { activeActionFile = file }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Item",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreateFileDialog) {
            CreateFileDialog(
                onDismiss = { showCreateFileDialog = false },
                onConfirm = { name, content ->
                    viewModel.createNewTextFile(name, content, currentFolderId)
                    showCreateFileDialog = false
                }
            )
        }
        
        if (showCreateFolderDialog) {
            CreateFolderDialog(
                onDismiss = { showCreateFolderDialog = false },
                onConfirm = { name ->
                    viewModel.createNewFolder(name, currentFolderId)
                    showCreateFolderDialog = false
                }
            )
        }
        
        if (showViewContentDialog != null) {
            AlertDialog(
                onDismissRequest = { showViewContentDialog = null },
                title = { Text("File Content") },
                text = { Text(showViewContentDialog ?: "") },
                confirmButton = {
                    TextButton(onClick = { showViewContentDialog = null }) { Text("Close") }
                }
            )
        }

        // Expanded detailed Context Dialog overlay
        activeActionFile?.let { file ->
            AlertDialog(
                onDismissRequest = { activeActionFile = null },
                title = { Text(file.name) },
                text = {
                    Column {
                        Text("Aesthetic parameters:")
                        Text("Size: ${file.size} bytes", fontWeight = FontWeight.Bold)
                        Text("Owner: ${file.ownerDeviceName}", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                viewModel.toggleFavorite(file)
                                activeActionFile = null
                                Toast.makeText(context, if (file.isFavorite) "Removed from favorites" else "Added to favorites", Toast.LENGTH_SHORT).show()
                            }) {
                                val favIcon = if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder
                                Icon(favIcon, contentDescription = null, tint = Color.Red)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (file.isFavorite) "Unfavorite" else "Favorite")
                            }
                            
                            // Download button only shown if the file belongs to a remote peer (isLocal == false)
                            if (!file.isLocal) {
                                Button(
                                    onClick = {
                                        Toast.makeText(context, "Initiating direct LAN download...", Toast.LENGTH_SHORT).show()
                                        viewModel.downloadSharedFile(file) { success, msg ->
                                            Toast.makeText(context, msg ?: (if (success) "Fetched file!" else "Connection lost"), Toast.LENGTH_LONG).show()
                                        }
                                        activeActionFile = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Download P2P")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (!file.content.isNullOrEmpty()) {
                                showViewContentDialog = file.content
                            } else if (file.localFilePath != null) {
                                val diskFile = File(file.localFilePath)
                                if (diskFile.exists()) {
                                    // Open file intent
                                    try {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.provider",
                                            diskFile
                                        )
                                        val ext = diskFile.extension.lowercase()
                                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mime)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Open File"))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open file.", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Payload resides safely on LAN server.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, "No local copy downloaded yet. Pull from peer first.", Toast.LENGTH_SHORT).show()
                            }
                            activeActionFile = null
                        }
                    ) {
                        Text(if (!file.content.isNullOrEmpty()) "Read text contents" else "Open File")
                    }
                },
                dismissButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            viewModel.deleteFileOrFolder(file)
                            activeActionFile = null
                            Toast.makeText(context, "Purged ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            )
        }
    }
}

@Composable
fun CreateFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder Name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (folderName.isNotBlank()) onConfirm(folderName) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateFileDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var fileName by remember { mutableStateOf("") }
    var fileContent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New File") },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name (e.g., Note)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fileContent,
                    onValueChange = { fileContent = it },
                    label = { Text("Content") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (fileName.isNotBlank()) onConfirm(fileName, fileContent) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
