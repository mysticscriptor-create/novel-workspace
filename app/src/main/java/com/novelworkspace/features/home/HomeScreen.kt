package com.novelworkspace.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelworkspace.core.security.HomeSectionPrefs
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.data.database.RecentAccessEntity
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.models.ChapterRecord
import com.novelworkspace.data.models.CharacterRecord
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.WorldRecord
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun HomeScreen(
    config: VaultConfiguration,
    isSyncing: Boolean,
    recentAccess: List<RecentAccessEntity>,
    recentlyModified: List<VaultFileEntity>,
    characters: List<CharacterRecord>,
    customPages: List<CustomPageManifest>,
    chapters: List<ChapterRecord>,
    worldRecords: List<WorldRecord>,
    traitCount: Int,
    onRefreshSync: () -> Unit,
    onOpenSearch: () -> Unit,
    onNavigateTab: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenCustomPage: (CustomPageManifest) -> Unit,
    onUpdateHomePrefs: (HomeSectionPrefs) -> Unit
) {
    var showCustomize by remember { mutableStateOf(false) }
    val prefs = config.homePrefs

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Novel Workspace", style = MaterialTheme.typography.headlineSmall)
                    UnboxedMetadataLine(
                        listOf(
                            "${characters.size} Characters",
                            "$traitCount Traits",
                            "${chapters.size} Chapters"
                        )
                    )
                }
                Row {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "Global Search")
                    }
                    IconButton(onClick = onRefreshSync) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Sync Vault")
                    }
                    IconButton(onClick = { showCustomize = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Customize Home")
                    }
                }
            }
            if (isSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        // Quick Navigation
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val quickTabs = listOf(
                    "characters" to "Characters",
                    "traits" to "Traits",
                    "world" to "Worldbuilding",
                    "timeline" to "Timeline",
                    "studio" to "Page Studio",
                    "library" to "Library"
                )
                items(quickTabs) { (route, label) ->
                    OutlinedButton(onClick = { onNavigateTab(route) }) {
                        Text(label)
                    }
                }
            }
        }

        if (prefs.showFavouritePages) {
            val favPages = customPages.filter { config.favouritePageIds.contains(it.id) }.ifEmpty { customPages.take(4) }
            if (favPages.isNotEmpty()) {
                item {
                    Text("Dashboards", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(favPages) { page ->
                            WorkspaceCard(
                                modifier = Modifier.fillParentMaxWidth(0.68f),
                                onClick = { onOpenCustomPage(page) }
                            ) {
                                Text(page.title, style = MaterialTheme.typography.titleMedium)
                                UnboxedMetadataLine(listOf(page.category, "${page.sourceFolders.size} sources"))
                            }
                        }
                    }
                }
            }
        }

        if (prefs.showFavouriteCharacters) {
            val favChars = characters.filter { config.favouriteNotePaths.contains(it.relativePath) }.ifEmpty { characters.take(5) }
            if (favChars.isNotEmpty()) {
                item {
                    Text("Characters", style = MaterialTheme.typography.titleMedium)
                }
                items(favChars) { ch ->
                    WorkspaceCard(onClick = { onOpenNote(ch.relativePath) }) {
                        Text(ch.name, style = MaterialTheme.typography.titleMedium)
                        UnboxedMetadataLine(listOf(ch.rank, ch.species, ch.status))
                    }
                }
            }
        }

        if (prefs.showChapterProgress && chapters.isNotEmpty()) {
            item {
                Text("Chapter Progress", style = MaterialTheme.typography.titleMedium)
            }
            items(chapters.take(4)) { ch ->
                WorkspaceCard(onClick = { onOpenNote(ch.relativePath) }) {
                    Text("Ch. ${ch.chapterNumber} — ${ch.title}", style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(ch.timelineDate, "${ch.characters.size} characters", "${ch.events.size} events"))
                }
            }
        }

        if (prefs.showRecentNotes && recentAccess.isNotEmpty()) {
            item {
                Text("Recently Accessed", style = MaterialTheme.typography.titleMedium)
            }
            items(recentAccess.take(5)) { item ->
                WorkspaceCard(onClick = { onOpenNote(item.relativePath) }) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(item.relativePath))
                }
            }
        }

        if (prefs.showRecentChanges && recentlyModified.isNotEmpty()) {
            item {
                Text("Recently Changed in Vault", style = MaterialTheme.typography.titleMedium)
            }
            items(recentlyModified.take(5)) { file ->
                WorkspaceCard(onClick = { onOpenNote(file.relativePath) }) {
                    Text(file.title, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(file.kind, file.relativePath))
                }
            }
        }

        if (prefs.showWorldHighlights && worldRecords.isNotEmpty()) {
            item {
                Text("Worldbuilding Highlights", style = MaterialTheme.typography.titleMedium)
            }
            items(worldRecords.take(4)) { rec ->
                WorkspaceCard(onClick = { onOpenNote(rec.relativePath) }) {
                    Text(rec.name, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(rec.category, "${rec.outgoingLinks.size} links"))
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showCustomize) {
        AlertDialog(
            onDismissRequest = { showCustomize = false },
            title = { Text("Customize Home Dashboard") },
            text = {
                Column {
                    ToggleRow("Favourite Dashboards", prefs.showFavouritePages) {
                        onUpdateHomePrefs(prefs.copy(showFavouritePages = it))
                    }
                    ToggleRow("Favourite Characters", prefs.showFavouriteCharacters) {
                        onUpdateHomePrefs(prefs.copy(showFavouriteCharacters = it))
                    }
                    ToggleRow("Chapter Progress", prefs.showChapterProgress) {
                        onUpdateHomePrefs(prefs.copy(showChapterProgress = it))
                    }
                    ToggleRow("Recently Accessed", prefs.showRecentNotes) {
                        onUpdateHomePrefs(prefs.copy(showRecentNotes = it))
                    }
                    ToggleRow("Recently Changed", prefs.showRecentChanges) {
                        onUpdateHomePrefs(prefs.copy(showRecentChanges = it))
                    }
                    ToggleRow("Worldbuilding Highlights", prefs.showWorldHighlights) {
                        onUpdateHomePrefs(prefs.copy(showWorldHighlights = it))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showCustomize = false }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}
