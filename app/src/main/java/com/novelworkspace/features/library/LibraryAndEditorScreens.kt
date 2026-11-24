package com.novelworkspace.features.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.novelworkspace.NoteEditorState
import com.novelworkspace.core.markdown.MarkdownEngine
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.models.VaultFileKind
import com.novelworkspace.ui.components.ConfirmDeleteDialog
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WikilinkChipGroup
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun LibraryBrowserScreen(
    allFiles: List<VaultFileEntity>,
    config: VaultConfiguration,
    gateway: VaultGateway?,
    onOpenNote: (String) -> Unit,
    onCreateNote: (String, String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onToggleFavFolder: (String) -> Unit
) {
    var currentFolder by remember { mutableStateOf("") }
    var filterText by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<VaultFileKind?>(null) }
    var sortByModified by remember { mutableStateOf(false) }
    var showNewNoteDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    val directFolders = remember(allFiles, currentFolder) {
        val prefix = if (currentFolder.isEmpty()) "" else "$currentFolder/"
        allFiles.asSequence()
            .filter { it.relativePath.startsWith(prefix) }
            .mapNotNull {
                val rest = it.relativePath.removePrefix(prefix)
                if (rest.contains('/')) "$prefix${rest.substringBefore('/')}" else null
            }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    val directFiles = remember(allFiles, currentFolder, filterText, kindFilter, sortByModified) {
        val base = if (filterText.isNotBlank()) {
            allFiles.filter {
                (currentFolder.isEmpty() || it.folderPath == currentFolder || it.folderPath.startsWith("$currentFolder/")) &&
                    (it.name.contains(filterText, true) || it.title.contains(filterText, true))
            }
        } else {
            allFiles.filter { it.folderPath == currentFolder }
        }.filter { kindFilter == null || it.kind == kindFilter?.name }

        if (sortByModified) base.sortedByDescending { it.lastModifiedMs }
        else base.sortedBy { it.name.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (currentFolder.isNotEmpty()) {
                    IconButton(onClick = { currentFolder = VaultPathValidator.parentOf(currentFolder) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up folder")
                    }
                }
                Text(
                    text = if (currentFolder.isEmpty()) "Vault Root" else currentFolder,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Row {
                if (currentFolder.isNotEmpty()) {
                    val isFav = config.favouriteFolders.contains(currentFolder)
                    IconButton(onClick = { onToggleFavFolder(currentFolder) }) {
                        Icon(
                            if (isFav) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favourite folder"
                        )
                    }
                }
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New Folder")
                }
                IconButton(onClick = { showNewNoteDialog = true }) {
                    Icon(Icons.Outlined.NoteAdd, contentDescription = "New Note")
                }
            }
        }

        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text("Search within folder...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            FilterChip(
                selected = kindFilter == null,
                onClick = { kindFilter = null },
                label = { Text("All") }
            )
            FilterChip(
                selected = kindFilter == VaultFileKind.MARKDOWN,
                onClick = { kindFilter = VaultFileKind.MARKDOWN },
                label = { Text("Notes") }
            )
            FilterChip(
                selected = kindFilter == VaultFileKind.JSON,
                onClick = { kindFilter = VaultFileKind.JSON },
                label = { Text("JSON") }
            )
            FilterChip(
                selected = kindFilter == VaultFileKind.IMAGE,
                onClick = { kindFilter = VaultFileKind.IMAGE },
                label = { Text("Assets") }
            )
            FilterChip(
                selected = sortByModified,
                onClick = { sortByModified = !sortByModified },
                label = { Text("Recent") }
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (currentFolder.isEmpty() && config.favouriteFolders.isNotEmpty() && filterText.isBlank()) {
                items(config.favouriteFolders.sorted()) { fav ->
                    WorkspaceCard(onClick = { currentFolder = fav }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Star, contentDescription = null)
                            Spacer(Modifier.padding(6.dp))
                            Text(fav, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            if (filterText.isBlank()) {
                items(directFolders) { folder ->
                    WorkspaceCard(onClick = { currentFolder = folder }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                            Spacer(Modifier.padding(6.dp))
                            Text(VaultPathValidator.nameOf(folder), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            items(directFiles, key = { it.relativePath }) { file ->
                WorkspaceCard(onClick = {
                    if (file.kind == VaultFileKind.IMAGE.name) previewImagePath = file.relativePath
                    else onOpenNote(file.relativePath)
                }) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(file.kind, "${file.sizeBytes} B", file.parseError ?: ""))
                }
            }
        }
    }

    if (showNewNoteDialog) {
        var noteName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewNoteDialog = false },
            title = { Text("New Markdown Note") },
            text = {
                OutlinedTextField(
                    value = noteName,
                    onValueChange = { noteName = it },
                    label = { Text("Filename (e.g. Character Name.md)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (noteName.isNotBlank()) {
                        val fn = if (noteName.endsWith(".md")) noteName else "$noteName.md"
                        val path = if (currentFolder.isEmpty()) fn else "$currentFolder/$fn"
                        onCreateNote(path, "---\ntitle: ${fn.removeSuffix(".md")}\n---\n\n# ${fn.removeSuffix(".md")}\n")
                        showNewNoteDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewNoteDialog = false }) { Text("Cancel") } }
        )
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (folderName.isNotBlank()) {
                        val path = if (currentFolder.isEmpty()) folderName.trim() else "$currentFolder/${folderName.trim()}"
                        onCreateFolder(path)
                        showNewFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") } }
        )
    }

    if (previewImagePath != null && gateway != null) {
        var bytes by remember(previewImagePath) { mutableStateOf<ByteArray?>(null) }
        LaunchedEffect(previewImagePath) {
            bytes = gateway.readBytes(previewImagePath!!)
        }
        AlertDialog(
            onDismissRequest = { previewImagePath = null },
            title = { Text(VaultPathValidator.nameOf(previewImagePath!!)) },
            text = {
                val bmp = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = previewImagePath, modifier = Modifier.fillMaxWidth().height(240.dp))
                } else {
                    Text("Loading or unsupported image format.")
                }
            },
            confirmButton = { TextButton(onClick = { previewImagePath = null }) { Text("Close") } }
        )
    }
}

@Composable
fun NoteEditorScreen(
    state: NoteEditorState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdateFrontmatter: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onFollowWikilink: (String) -> Unit,
    onBack: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddProp by remember { mutableStateOf(false) }
    val parsed = remember(state.content) { MarkdownEngine.parse(state.content, state.relativePath) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(
                        VaultPathValidator.nameOf(state.relativePath),
                        style = MaterialTheme.typography.titleMedium
                    )
                    UnboxedMetadataLine(listOf(state.relativePath, if (state.isDirty) "Unsaved" else "Synced"))
                }
            }
            Row {
                IconButton(onClick = onSave) {
                    Icon(Icons.Outlined.Save, contentDescription = "Save note")
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete note")
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Reader") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Properties") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Source") })
        }

        when (tab) {
            0 -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (parsed.parseError != null) {
                    Text(parsed.parseError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                WikilinkChipGroup("Linked Notes", parsed.wikilinks.map { it.target }, onFollowWikilink)
                if (parsed.wikilinks.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(parsed.body, style = MaterialTheme.typography.bodyMedium)
            }
            1 -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                OutlinedButton(onClick = { showAddProp = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit / Add Frontmatter Property")
                }
                Spacer(Modifier.height(10.dp))
                parsed.frontmatter.forEach { (k, v) ->
                    WorkspaceCard(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v?.toString() ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            2 -> Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                OutlinedTextField(
                    value = state.content,
                    onValueChange = onContentChange,
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            itemName = VaultPathValidator.nameOf(state.relativePath),
            onConfirm = { showDeleteConfirm = false; onDelete(state.relativePath) },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (showAddProp) {
        var key by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddProp = false },
            title = { Text("Update Frontmatter Property") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, singleLine = true)
                    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value (blank removes)") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (key.isNotBlank()) {
                        onUpdateFrontmatter(key.trim(), value.trim())
                        showAddProp = false
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showAddProp = false }) { Text("Cancel") } }
        )
    }
}
