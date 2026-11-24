package com.novelworkspace.features.world

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelworkspace.data.models.ChapterRecord
import com.novelworkspace.data.models.WorldRecord
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WikilinkChipGroup
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun WorldbuildingScreen(
    records: List<WorldRecord>,
    onOpenNote: (String) -> Unit,
    onFollowWikilink: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<WorldRecord?>(null) }

    if (selected != null) {
        val rec = selected!!
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(rec.name, style = MaterialTheme.typography.headlineSmall)
                    UnboxedMetadataLine(listOf(rec.category) + rec.tags)
                }
            }
            if (rec.summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(rec.summary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            WikilinkChipGroup("References (Outgoing)", rec.outgoingLinks, onFollowWikilink)
            WikilinkChipGroup("Referenced By (Incoming)", rec.incomingLinks.map { it.substringAfterLast('/').substringBeforeLast('.') }, onFollowWikilink)

            if (rec.properties.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text("Properties", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                rec.properties.forEach { (k, v) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onOpenNote(rec.relativePath) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Original Note")
            }
        }
        return
    }

    val categories = remember(records) { records.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
    val filtered = remember(records, query, categoryFilter) {
        records.filter { r ->
            (query.isBlank() || r.name.contains(query, true) || r.summary.contains(query, true)) &&
                (categoryFilter == null || r.category.equals(categoryFilter, true))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Worldbuilding (${filtered.size})", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search regions, cities, factions, creatures...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(selected = categoryFilter == null, onClick = { categoryFilter = null }, label = { Text("All") })
            }
            items(categories) { cat ->
                FilterChip(
                    selected = categoryFilter == cat,
                    onClick = { categoryFilter = if (categoryFilter == cat) null else cat },
                    label = { Text(cat) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.relativePath }) { rec ->
                WorkspaceCard(onClick = { selected = rec }) {
                    Text(rec.name, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(rec.category, "${rec.outgoingLinks.size} out", "${rec.incomingLinks.size} in"))
                    if (rec.summary.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(rec.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineScreen(
    chapters: List<ChapterRecord>,
    onOpenNote: (String) -> Unit,
    onFollowWikilink: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Timeline & Chapters (${chapters.size})", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (chapters.isEmpty()) {
            Text(
                "No chapter notes found. Configure chapter folders in Settings or tag notes with #chapter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(chapters, key = { it.relativePath }) { ch ->
                    WorkspaceCard(onClick = { onOpenNote(ch.relativePath) }) {
                        Text("Chapter ${ch.chapterNumber}: ${ch.title}", style = MaterialTheme.typography.titleMedium)
                        UnboxedMetadataLine(listOf(ch.timelineDate, "${ch.characters.size} characters", "${ch.locations.size} locations"))
                        if (ch.summary.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(ch.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (ch.events.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            ch.events.forEach { ev ->
                                Text("• $ev", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        WikilinkChipGroup("Characters", ch.characters, onFollowWikilink)
                        WikilinkChipGroup("Locations", ch.locations, onFollowWikilink)
                        WikilinkChipGroup("Factions", ch.factions, onFollowWikilink)
                    }
                }
            }
        }
    }
}
