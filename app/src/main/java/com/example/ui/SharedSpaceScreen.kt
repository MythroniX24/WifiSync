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
                        val itemColor = remember(file) {
                            when {
                                file.isFolder -> Color(0xFFFFECC2) 
                                file.isFavorite -> Color(0xFFFFF4D2) 
                                file.isLocal -> Color(0xFFE8F1FF) 
                                else -> Color(0xFFE7F6E7) 
                            }
                        }

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
                            colors = CardDefaults.cardColors(containerColor = itemColor)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = fileIcon, 
                                    contentDescription = null,
                                    tint = if (file.isFolder) Color(0xFFFFA000) else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(file.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                                        if (file.isFavorite) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.Favorite, contentDescription = "Favorite", tint = Color.Red, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    if (!file.isFolder) {
                                        val sourceTag = if (file.isLocal) "Offline Storage" else "P2P Peer File"
                                        Text("${file.ownerDeviceName} • ${file.size} bytes • $sourceTag", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Text("${file.ownerDeviceName} • Folder directory", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                
                                // Mini Action click icon
                                IconButton(onClick = { activeActionFile = file }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Item",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
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
                                    // Local view alert
                                    Toast.makeText(context, "Local file resides at: ${diskFile.name}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Payload resides safely on LAN server.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, "No local copy downloaded yet. Pull from peer first.", Toast.LENGTH_SHORT).show()
                            }
                            activeActionFile = null
                        }
                    ) {
                        Text(if (!file.content.isNullOrEmpty()) "Read text contents" else "Check Info")
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
