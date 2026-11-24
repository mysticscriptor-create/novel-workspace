package com.novelworkspace.dashboard

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.novelworkspace.compatibility.DataviewQueryEngine
import com.novelworkspace.compatibility.NativeNetworkProxy
import com.novelworkspace.core.security.VaultConfigStore
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.sync.VaultSyncEngine
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.VaultFileKind
import com.novelworkspace.ui.components.ConfirmDeleteDialog
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WorkspaceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CustomPageStudioScreen(
    pages: List<CustomPageManifest>,
    allFiles: List<VaultFileEntity>,
    gateway: VaultGateway?,
    pageManager: CustomPageManager?,
    queryEngine: DataviewQueryEngine,
    syncEngine: VaultSyncEngine,
    networkProxy: NativeNetworkProxy,
    configStore: VaultConfigStore,
    config: VaultConfiguration,
    initialOpenPage: CustomPageManifest?,
    onClearInitialPage: () -> Unit,
    onRefreshPages: () -> Unit,
    onToggleFavPage: (String) -> Unit,
    onNotice: (String) -> Unit,
    onOpenVaultFile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activePage by remember { mutableStateOf<CustomPageManifest?>(initialOpenPage) }
    var isEditing by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<CustomPageManifest?>(null) }

    LaunchedEffect(initialOpenPage) {
        if (initialOpenPage != null) {
            activePage = initialOpenPage
            isEditing = false
            onClearInitialPage()
        }
    }

    if (activePage != null && gateway != null && pageManager != null) {
        val manifest = activePage!!
        if (!isEditing) {
            // Live Dashboard Runner
            var jsCode by remember(manifest.id) { mutableStateOf("") }
            var cssCode by remember(manifest.id) { mutableStateOf("") }
            var reloadToken by remember { mutableIntStateOf(0) }
            val logs = remember { mutableStateListOf<ConsoleEntry>() }
            var showConsole by remember { mutableStateOf(false) }

            LaunchedEffect(manifest.id, reloadToken) {
                val (j, c) = pageManager.loadPageFiles(manifest)
                jsCode = j
                cssCode = c
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = { activePage = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Studio")
                        }
                        Text(manifest.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Row {
                        IconButton(onClick = { reloadToken++ }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reload page")
                        }
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit page")
                        }
                        TextButton(onClick = { showConsole = !showConsole }) {
                            val errCount = logs.count { it.level == "error" }
                            Text(if (errCount > 0) "Logs ($errCount!)" else "Logs")
                        }
                    }
                }
                if (showConsole) {
                    WorkspaceCard(modifier = Modifier.padding(8.dp)) {
                        Text("Page Console & Diagnostics", style = MaterialTheme.typography.titleMedium)
                        Column(modifier = Modifier.height(140.dp).verticalScroll(rememberScrollState())) {
                            if (logs.isEmpty()) Text("No console output yet.", style = MaterialTheme.typography.bodySmall)
                            logs.forEach { l ->
                                Text(
                                    "[${l.level}] ${l.message}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (l.level == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                IsolatedPageWebView(
                    manifest = manifest,
                    jsCode = jsCode,
                    cssCode = cssCode,
                    vaultGateway = gateway,
                    queryEngine = queryEngine,
                    syncEngine = syncEngine,
                    networkProxy = networkProxy,
                    config = config,
                    allowWrite = configStore.isPageWriteAllowed(manifest.id),
                    reloadToken = reloadToken,
                    onConsoleLog = { entry -> logs.add(entry) },
                    onNotice = onNotice,
                    onOpenVaultFile = onOpenVaultFile,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
            return
        } else {
            // Custom Page Studio Editor
            PageEditorView(
                manifest = manifest,
                gateway = gateway,
                pageManager = pageManager,
                queryEngine = queryEngine,
                syncEngine = syncEngine,
                networkProxy = networkProxy,
                configStore = configStore,
                config = config,
                onSaved = { updated ->
                    activePage = updated
                    onRefreshPages()
                    onNotice("Saved '${updated.title}'")
                },
                onCloseEditor = { isEditing = false },
                onNotice = onNotice,
                onOpenVaultFile = onOpenVaultFile
            )
            return
        }
    }

    // Custom Page Studio List
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Custom Page Studio", style = MaterialTheme.typography.headlineSmall)
            Row {
                OutlinedButton(onClick = { showImportDialog = true }) { Text("Import MD") }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("New")
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (pages.isEmpty()) {
            WorkspaceCard {
                Text("No Custom Pages Yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create a custom JS/CSS dashboard or import an existing DataviewJS note (such as Devour HUD, World Dashboard, or Prowess Tracker) from your vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages, key = { it.id }) { p ->
                    WorkspaceCard(onClick = { activePage = p; isEditing = false }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                UnboxedMetadataLine(listOf(p.category, "${p.sourceFolders.size} source folders"))
                            }
                            Row {
                                val isFav = config.favouritePageIds.contains(p.id)
                                IconButton(onClick = { onToggleFavPage(p.id) }) {
                                    Icon(if (isFav) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = "Favourite")
                                }
                                IconButton(onClick = { activePage = p; isEditing = true }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        pageManager?.duplicatePage(p, "${p.title} Copy")
                                        onRefreshPages()
                                    }
                                }) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate")
                                }
                                IconButton(onClick = { deleteCandidate = p }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog && pageManager != null) {
        var title by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("Dashboard") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Custom Page") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Page Name") }, singleLine = true)
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        scope.launch {
                            val id = title.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "_" + System.currentTimeMillis()
                            val created = pageManager.savePage(
                                CustomPageManifest(id = id, title = title.trim(), category = category.trim(), folderPath = ""),
                                jsCode = "dv.el('h2', '${title.trim()}');\nconst pages = dv.pages('');\ndv.paragraph('Indexed notes: ' + pages.length);\n",
                                cssCode = "#nw-page-root { padding: 12px; }\n",
                                saveSnapshot = false
                            )
                            onRefreshPages()
                            activePage = created
                            isEditing = true
                            showCreateDialog = false
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    if (showImportDialog && gateway != null && pageManager != null) {
        val mdFiles = remember(allFiles) { allFiles.filter { it.kind == VaultFileKind.MARKDOWN.name } }
        var filter by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import DataviewJS Markdown") },
            text = {
                Column {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Filter vault notes...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(mdFiles.filter { it.name.contains(filter, true) }.take(40)) { file ->
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val txt = gateway.readText(file.relativePath)?.text ?: return@launch
                                        val imported = DataviewImporter.extractFromMarkdown(txt, file.name)
                                        val resolvedDeps = DataviewImporter.resolveWithVault(imported.dependencyReport, gateway, config)
                                        if (resolvedDeps.requiresExternalNetwork) {
                                            onNotice("Warning: Script calls external hosts (${resolvedDeps.detectedExternalHosts.joinToString()})")
                                        }
                                        val id = imported.suggestedTitle.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "_" + System.currentTimeMillis()
                                        val created = pageManager.savePage(
                                            CustomPageManifest(
                                                id = id,
                                                title = imported.suggestedTitle,
                                                folderPath = "",
                                                sourceFolders = resolvedDeps.sourceFolders,
                                                explicitFiles = resolvedDeps.explicitNotes,
                                                moduleDependencies = resolvedDeps.jsModules + resolvedDeps.jsonConfigs
                                            ),
                                            jsCode = imported.javascript,
                                            cssCode = imported.extractedCss,
                                            saveSnapshot = false
                                        )
                                        onRefreshPages()
                                        activePage = created
                                        isEditing = true
                                        showImportDialog = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(file.relativePath, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showImportDialog = false }) { Text("Close") } }
        )
    }

    if (deleteCandidate != null && pageManager != null) {
        ConfirmDeleteDialog(
            itemName = deleteCandidate!!.title,
            onConfirm = {
                val target = deleteCandidate!!
                deleteCandidate = null
                scope.launch {
                    pageManager.deletePage(target)
                    onRefreshPages()
                }
            },
            onDismiss = { deleteCandidate = null }
        )
    }
}

@Composable
private fun PageEditorView(
    manifest: CustomPageManifest,
    gateway: VaultGateway,
    pageManager: CustomPageManager,
    queryEngine: DataviewQueryEngine,
    syncEngine: VaultSyncEngine,
    networkProxy: NativeNetworkProxy,
    configStore: VaultConfigStore,
    config: VaultConfiguration,
    onSaved: (CustomPageManifest) -> Unit,
    onCloseEditor: () -> Unit,
    onNotice: (String) -> Unit,
    onOpenVaultFile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var title by remember(manifest.id) { mutableStateOf(manifest.title) }
    var jsCode by remember(manifest.id) { mutableStateOf("") }
    var cssCode by remember(manifest.id) { mutableStateOf("") }
    var sourceFoldersText by remember(manifest.id) { mutableStateOf(manifest.sourceFolders.joinToString(", ")) }
    var excludedText by remember(manifest.id) { mutableStateOf(manifest.excludedFolders.joinToString(", ")) }
    var explicitFilesText by remember(manifest.id) { mutableStateOf(manifest.explicitFiles.joinToString(", ")) }
    var allowNet by remember(manifest.id) { mutableStateOf(configStore.isPageNetworkAllowed(manifest.id)) }
    var allowWrite by remember(manifest.id) { mutableStateOf(configStore.isPageWriteAllowed(manifest.id)) }
    var depReport by remember { mutableStateOf<DependencyReport?>(null) }
    var snapshots by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<ConsoleEntry>() }

    LaunchedEffect(manifest.id) {
        val (j, c) = pageManager.loadPageFiles(manifest)
        jsCode = j
        cssCode = c
        depReport = DataviewImporter.resolveWithVault(DataviewImporter.scanDependencies(j), gateway, config)
        snapshots = pageManager.listSnapshots(manifest)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onCloseEditor) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Text("Edit: $title", style = MaterialTheme.typography.titleMedium)
            }
            Row {
                IconButton(onClick = { tab = 3; reloadToken++ }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Preview")
                }
                IconButton(onClick = {
                    scope.launch {
                        configStore.setPageNetworkAllowed(manifest.id, allowNet)
                        configStore.setPageWriteAllowed(manifest.id, allowWrite)
                        val updated = pageManager.savePage(
                            manifest.copy(
                                title = title.trim().ifBlank { manifest.title },
                                sourceFolders = sourceFoldersText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                excludedFolders = excludedText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                explicitFiles = explicitFilesText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                allowNetwork = allowNet,
                                allowVaultWrite = allowWrite
                            ),
                            jsCode = jsCode,
                            cssCode = cssCode
                        )
                        snapshots = pageManager.listSnapshots(updated)
                        onSaved(updated)
                    }
                }) {
                    Icon(Icons.Outlined.Save, contentDescription = "Save page")
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("JS") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("CSS") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Config") })
            Tab(selected = tab == 3, onClick = { tab = 3; reloadToken++ }, text = { Text("Preview") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Logs") })
        }

        when (tab) {
            0 -> OutlinedTextField(
                value = jsCode,
                onValueChange = { jsCode = it },
                modifier = Modifier.fillMaxSize().padding(10.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            1 -> Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                var cssPathImport by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cssPathImport,
                        onValueChange = { cssPathImport = it },
                        label = { Text("Import CSS path (e.g. Css/theme-5-apex.css)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        scope.launch {
                            val txt = gateway.readText(cssPathImport.trim())?.text
                            if (txt != null) { cssCode = txt; onNotice("Imported CSS") }
                            else onNotice("CSS file not found")
                        }
                    }) { Text("Load") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cssCode,
                    onValueChange = { cssCode = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            2 -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Page Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sourceFoldersText, onValueChange = { sourceFoldersText = it }, label = { Text("Source Folders (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = excludedText, onValueChange = { excludedText = it }, label = { Text("Excluded Folders (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = explicitFilesText, onValueChange = { explicitFilesText = it }, label = { Text("Explicit File Dependencies") }, modifier = Modifier.fillMaxWidth())

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow External Network (requestUrl)")
                    Switch(checked = allowNet, onCheckedChange = { allowNet = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow Vault Write-Back")
                    Switch(checked = allowWrite, onCheckedChange = { allowWrite = it })
                }

                depReport?.let { rep ->
                    WorkspaceCard {
                        Text("Detected Dependencies", style = MaterialTheme.typography.titleMedium)
                        Text("JS Modules: ${rep.jsModules.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                        Text("JSON Configs: ${rep.jsonConfigs.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                        if (rep.unresolvedDependencies.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Unresolved: ${rep.unresolvedDependencies.joinToString()}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (snapshots.isNotEmpty()) {
                    WorkspaceCard {
                        Text("Restore Previous Saved Version", style = MaterialTheme.typography.titleMedium)
                        snapshots.take(5).forEach { (ts, snapPath) ->
                            TextButton(onClick = {
                                scope.launch {
                                    pageManager.restoreSnapshot(manifest, snapPath)?.let { (j, c) ->
                                        jsCode = j; cssCode = c
                                        onNotice("Restored version $ts")
                                    }
                                }
                            }) {
                                Text("Restore snapshot $ts")
                            }
                        }
                    }
                }
            }
            3 -> IsolatedPageWebView(
                manifest = manifest,
                jsCode = jsCode,
                cssCode = cssCode,
                vaultGateway = gateway,
                queryEngine = queryEngine,
                syncEngine = syncEngine,
                networkProxy = networkProxy,
                config = config,
                allowWrite = allowWrite,
                reloadToken = reloadToken,
                onConsoleLog = { logs.add(it) },
                onNotice = onNotice,
                onOpenVaultFile = onOpenVaultFile,
                modifier = Modifier.fillMaxSize()
            )
            4 -> LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(logs) { l ->
                    Text(
                        "[${l.level}] ${l.message}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (l.level == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
