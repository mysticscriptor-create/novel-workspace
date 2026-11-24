package com.novelworkspace.features.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.SearchFilterScope
import com.novelworkspace.data.models.SearchHit
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun SearchScreen(
    query: String,
    scope: SearchFilterScope,
    results: List<SearchHit>,
    customPages: List<CustomPageManifest>,
    onQueryChange: (String) -> Unit,
    onScopeChange: (SearchFilterScope) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenPage: (CustomPageManifest) -> Unit
) {
    val matchingPages = if (query.isBlank()) emptyList() else customPages.filter {
        it.title.contains(query, true) || it.category.contains(query, true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Global Search", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search notes, traits, YAML properties, tags, wikilinks...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SearchFilterScope.entries) { s ->
                FilterChip(
                    selected = scope == s,
                    onClick = { onScopeChange(s) },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scope == SearchFilterScope.ALL || scope == SearchFilterScope.PAGES) {
                items(matchingPages) { p ->
                    WorkspaceCard(onClick = { onOpenPage(p) }) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        UnboxedMetadataLine(listOf("Custom Page", p.category, p.folderPath))
                    }
                }
            }
            items(results, key = { it.relativePath }) { hit ->
                WorkspaceCard(onClick = { onOpenNote(hit.relativePath) }) {
                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(hit.matchedIn, hit.kind.name, hit.relativePath))
                    if (hit.snippet.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}
