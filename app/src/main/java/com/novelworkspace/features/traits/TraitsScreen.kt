package com.novelworkspace.features.traits

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
import com.novelworkspace.data.models.TraitRecord
import com.novelworkspace.ui.components.StatBarRow
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WikilinkChipGroup
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun TraitsScreen(
    traits: List<TraitRecord>,
    onOpenNote: (String) -> Unit,
    onFollowWikilink: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var layerFilter by remember { mutableStateOf<String?>(null) }
    var formFilter by remember { mutableStateOf<String?>(null) }
    var rankFilter by remember { mutableStateOf<String?>(null) }
    var sortAsc by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TraitRecord?>(null) }

    if (selected != null) {
        val tr = selected!!
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to traits")
                }
                Column {
                    Text(tr.name, style = MaterialTheme.typography.headlineSmall)
                    UnboxedMetadataLine(listOf(tr.rank, tr.layer, tr.subcategory, tr.form, tr.status))
                }
            }
            if (tr.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(tr.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            WorkspaceCard {
                Text("Stat & Boost Contributions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                StatBarRow("Power", tr.statPower, tr.boostPower)
                StatBarRow("Speed", tr.statSpeed, tr.boostSpeed)
                StatBarRow("Vitality", tr.statVitality, tr.boostVitality)
                StatBarRow("Perception", tr.statPerception, tr.boostPerception)
                StatBarRow("Energy", tr.statEnergy, tr.boostEnergy)
            }
            Spacer(Modifier.height(10.dp))
            WorkspaceCard {
                Text("Combat & Origin Metadata", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                MetaLine("Type", tr.type)
                MetaLine("Activation", tr.activation)
                MetaLine("Load", if (tr.load > 0) tr.load.toString() else "")
                MetaLine("Scaling", tr.scaling)
                MetaLine("Source Creature", tr.sourceCreature)
                MetaLine("Species", tr.species)
                MetaLine("Devoured In", tr.devouredIn)
                MetaLine("Chapter", tr.chapter)
            }
            if (tr.sourceCreature.isNotBlank()) {
                WikilinkChipGroup("Source Creature", listOf(tr.sourceCreature), onFollowWikilink)
            }
            WikilinkChipGroup("Synergy", tr.synergy) { link ->
                val found = traits.firstOrNull { it.name.equals(link, true) }
                if (found != null) selected = found else onFollowWikilink(link)
            }
            WikilinkChipGroup("Conflicts", tr.conflicts) { link ->
                val found = traits.firstOrNull { it.name.equals(link, true) }
                if (found != null) selected = found else onFollowWikilink(link)
            }
            if (tr.body.isNotBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text(tr.body, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onOpenNote(tr.relativePath) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Original Trait Note")
            }
        }
        return
    }

    val layers = remember(traits) { traits.map { it.layer }.filter { it.isNotBlank() }.distinct().sorted() }
    val forms = remember(traits) { traits.map { it.form }.filter { it.isNotBlank() }.distinct().sorted() }
    val ranks = remember(traits) { traits.map { it.rank }.filter { it.isNotBlank() }.distinct() }

    val filtered = remember(traits, query, layerFilter, formFilter, rankFilter, sortAsc) {
        traits.filter { t ->
            (query.isBlank() || t.name.contains(query, true) || t.description.contains(query, true) ||
                t.sourceCreature.contains(query, true) || t.subcategory.contains(query, true) ||
                t.activation.contains(query, true)) &&
                (layerFilter == null || t.layer.equals(layerFilter, true)) &&
                (formFilter == null || t.form.equals(formFilter, true)) &&
                (rankFilter == null || t.rank.equals(rankFilter, true))
        }.let { list ->
            if (sortAsc) list.sortedBy { it.rankValue } else list.sortedByDescending { it.rankValue }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Trait Archive (${filtered.size})", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search traits, creature, activation, subcategory...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(selected = sortAsc, onClick = { sortAsc = !sortAsc }, label = { Text(if (sortAsc) "Rank ↑" else "Rank ↓") })
            }
            items(layers) { layer ->
                FilterChip(
                    selected = layerFilter == layer,
                    onClick = { layerFilter = if (layerFilter == layer) null else layer },
                    label = { Text(layer) }
                )
            }
            items(forms) { form ->
                FilterChip(
                    selected = formFilter == form,
                    onClick = { formFilter = if (formFilter == form) null else form },
                    label = { Text(form) }
                )
            }
            items(ranks) { rk ->
                FilterChip(
                    selected = rankFilter == rk,
                    onClick = { rankFilter = if (rankFilter == rk) null else rk },
                    label = { Text(rk) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.relativePath }) { tr ->
                WorkspaceCard(onClick = { selected = tr }) {
                    Text(tr.name, style = MaterialTheme.typography.titleMedium)
                    UnboxedMetadataLine(listOf(tr.rank, tr.layer, tr.subcategory, tr.form, tr.activation))
                    if (tr.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(tr.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
