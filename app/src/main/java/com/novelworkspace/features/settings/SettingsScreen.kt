package com.novelworkspace.features.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelworkspace.core.security.AppThemeMode
import com.novelworkspace.core.security.VaultConfigStore
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.data.database.SyncErrorEntity
import com.novelworkspace.data.models.SyncReport
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun SettingsScreen(
    config: VaultConfiguration,
    configStore: VaultConfigStore,
    isSyncing: Boolean,
    lastReport: SyncReport?,
    indexedFileCount: Int,
    syncErrors: List<SyncErrorEntity>,
    onReconnectVault: () -> Unit,
    onManualSync: () -> Unit,
    onRebuildIndex: () -> Unit,
    onUpdateConfig: ((VaultConfiguration) -> VaultConfiguration) -> Unit,
    onThemeChange: (AppThemeMode) -> Unit,
    onNotice: (String) -> Unit
) {
    var includedText by remember(config.includedFolders) { mutableStateOf(config.includedFolders.joinToString(", ")) }
    var excludedText by remember(config.excludedFolders) { mutableStateOf(config.excludedFolders.joinToString(", ")) }
    var charFolders by remember(config.characterFolders) { mutableStateOf(config.characterFolders.joinToString(", ")) }
    var traitFolders by remember(config.traitFolders) { mutableStateOf(config.traitFolders.joinToString(", ")) }
    var worldFolders by remember(config.worldFolders) { mutableStateOf(config.worldFolders.joinToString(", ")) }
    var chapterFolders by remember(config.chapterFolders) { mutableStateOf(config.chapterFolders.joinToString(", ")) }
    var pagesRoot by remember(config.customPagesRoot) { mutableStateOf(config.customPagesRoot) }

    var aliasFrom by remember { mutableStateOf("") }
    var aliasTo by remember { mutableStateOf("") }

    var geminiKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var openRouterKey by remember { mutableStateOf("") }

    var showBackupDialog by remember { mutableStateOf(false) }
    var backupJson by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // Vault Connection & Sync
        WorkspaceCard {
            Text("Connected Obsidian Vault", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                config.treeUriString ?: "No vault selected",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            val syncLabel = if (config.lastSyncTimeMs > 0L) {
                "Last sync: ${DateUtils.getRelativeTimeSpanString(config.lastSyncTimeMs)}"
            } else "Not synced yet"
            UnboxedMetadataLine(
                listOf(
                    if (isSyncing) "Syncing..." else syncLabel,
                    "$indexedFileCount indexed files",
                    lastReport?.let { "${it.durationMs}ms" } ?: ""
                )
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onManualSync) { Text("Sync Now") }
                OutlinedButton(onClick = onRebuildIndex) { Text("Rebuild Index") }
                OutlinedButton(onClick = onReconnectVault) { Text("Change Vault") }
            }
        }

        // Theme
        WorkspaceCard {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.themeMode == mode,
                        onClick = { onThemeChange(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }

        // Folder Mappings
        WorkspaceCard {
            Text("Vault Folder Mappings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = includedText, onValueChange = { includedText = it }, label = { Text("Included Folders (empty = entire vault)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = excludedText, onValueChange = { excludedText = it }, label = { Text("Excluded Folders") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = charFolders, onValueChange = { charFolders = it }, label = { Text("Character Folders") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = traitFolders, onValueChange = { traitFolders = it }, label = { Text("Trait Folders") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = worldFolders, onValueChange = { worldFolders = it }, label = { Text("Worldbuilding Folders") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = chapterFolders, onValueChange = { chapterFolders = it }, label = { Text("Chapter Folders") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = pagesRoot, onValueChange = { pagesRoot = it }, label = { Text("Custom Pages Folder") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                fun parseSet(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                onUpdateConfig {
                    it.copy(
                        includedFolders = parseSet(includedText),
                        excludedFolders = parseSet(excludedText),
                        characterFolders = parseSet(charFolders),
                        traitFolders = parseSet(traitFolders),
                        worldFolders = parseSet(worldFolders),
                        chapterFolders = parseSet(chapterFolders),
                        customPagesRoot = pagesRoot.trim().ifBlank { "Novel Workspace/Pages" }
                    )
                }
                onNotice("Saved folder mappings")
            }) { Text("Save Folder Mappings") }
        }

        // Path Aliases
        WorkspaceCard {
            Text("Script & Config Path Aliases", style = MaterialTheme.typography.titleMedium)
            Text(
                "Redirect dashboard paths (e.g. Meta/Scripts/ -> Sub-js/) without moving vault files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            config.pathAliases.forEach { (from, to) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$from -> $to", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    TextButton(onClick = {
                        onUpdateConfig { cfg ->
                            val next = cfg.pathAliases.toMutableMap()
                            next.remove(from)
                            cfg.copy(pathAliases = next)
                        }
                    }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = aliasFrom, onValueChange = { aliasFrom = it }, label = { Text("From") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = aliasTo, onValueChange = { aliasTo = it }, label = { Text("To") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                if (aliasFrom.isNotBlank() && aliasTo.isNotBlank()) {
                    onUpdateConfig { cfg ->
                        val next = cfg.pathAliases.toMutableMap()
                        next[aliasFrom.trim()] = aliasTo.trim()
                        cfg.copy(pathAliases = next)
                    }
                    aliasFrom = ""; aliasTo = ""
                }
            }) { Text("Add Path Alias") }
        }

        // AI Provider Credentials (Keystore)
        WorkspaceCard {
            Text("AI Provider Keys (Android Keystore)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Configured: ${configStore.getConfiguredProviders().joinToString().ifBlank { "None" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geminiKey,
                onValueChange = { geminiKey = it },
                label = { Text("Gemini API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = groqKey,
                onValueChange = { groqKey = it },
                label = { Text("Groq API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = openRouterKey,
                onValueChange = { openRouterKey = it },
                label = { Text("OpenRouter API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (geminiKey.isNotBlank()) configStore.setApiKey("gemini", geminiKey)
                if (groqKey.isNotBlank()) configStore.setApiKey("groq", groqKey)
                if (openRouterKey.isNotBlank()) configStore.setApiKey("openrouter", openRouterKey)
                geminiKey = ""; groqKey = ""; openRouterKey = ""
                onNotice("Saved API credentials securely")
            }) { Text("Save Credentials") }
        }

        // Backup & Diagnostics
        WorkspaceCard {
            Text("Backup & Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                backupJson = configStore.exportConfigJson(includeTreeUri = false)
                showBackupDialog = true
            }) { Text("Backup / Restore Settings JSON") }

            if (syncErrors.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Parsing & Sync Diagnostics (${syncErrors.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                syncErrors.forEach { err ->
                    Text("${err.filePath}: ${err.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Settings JSON Backup / Restore") },
            text = {
                OutlinedTextField(
                    value = backupJson,
                    onValueChange = { backupJson = it },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                Button(onClick = {
                    runCatching { configStore.importConfigJson(backupJson) }
                        .onSuccess { imported ->
                            onUpdateConfig { imported }
                            onNotice("Restored settings")
                            showBackupDialog = false
                        }
                        .onFailure { onNotice("Invalid JSON: ${it.message}") }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showBackupDialog = false }) { Text("Close") } }
        )
    }
}
