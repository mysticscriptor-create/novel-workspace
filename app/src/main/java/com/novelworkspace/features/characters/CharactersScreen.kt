package com.novelworkspace.features.characters

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.data.models.CharacterRecord
import com.novelworkspace.ui.components.UnboxedMetadataLine
import com.novelworkspace.ui.components.WikilinkChipGroup
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun CharactersScreen(
    characters: List<CharacterRecord>,
    config: VaultConfiguration,
    gateway: VaultGateway?,
    onToggleFavourite: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onFollowWikilink: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CharacterRecord?>(null) }

    if (selected != null) {
        val ch = selected!!
        var imageBytes by remember(ch.relativePath) { mutableStateOf<ByteArray?>(null) }
        LaunchedEffect(ch.profileImage) {
            val img = ch.profileImage?.removePrefix("[[")?.removeSuffix("]]")?.trim()
            if (!img.isNullOrBlank() && gateway != null) {
                imageBytes = gateway.readBytes(img)
            }
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to characters")
                    }
                    Text(ch.name, style = MaterialTheme.typography.headlineSmall)
                }
                val isFav = config.favouriteNotePaths.contains(ch.relativePath)
                IconButton(onClick = { onToggleFavourite(ch.relativePath) }) {
                    Icon(if (isFav) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = "Favourite")
                }
            }
            UnboxedMetadataLine(listOf(ch.rank, ch.species, ch.gender, ch.status))
            Spacer(Modifier.height(10.dp))
            val bmp = remember(imageBytes) { imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = ch.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(10.dp))
            }
            WikilinkChipGroup("Traits", ch.traits, onFollowWikilink)
            WikilinkChipGroup("Factions", ch.factions, onFollowWikilink)
            WikilinkChipGroup("Relationships", ch.relationships, onFollowWikilink)
            WikilinkChipGroup("Chapter Appearances", ch.chapters, onFollowWikilink)

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text("Discovered Properties", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            ch.properties.forEach { (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onOpenNote(ch.relativePath) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Original Markdown Note")
            }
        }
        return
    }

    val filtered = remember(characters, query) {
        if (query.isBlank()) characters
        else characters.filter {
            it.name.contains(query, true) ||
                it.species.contains(query, true) ||
                it.rank.contains(query, true) ||
                it.factions.any { f -> f.contains(query, true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Characters (${filtered.size})", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search name, rank, species, faction...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        if (filtered.isEmpty()) {
            Text(
                "No characters discovered. Configure character folders in Settings or tag notes with #character.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.relativePath }) { ch ->
                    WorkspaceCard(onClick = { selected = ch }) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ch.name, style = MaterialTheme.typography.titleMedium)
                                UnboxedMetadataLine(listOf(ch.rank, ch.species, ch.status))
                            }
                            val isFav = config.favouriteNotePaths.contains(ch.relativePath)
                            IconButton(onClick = { onToggleFavourite(ch.relativePath) }) {
                                Icon(if (isFav) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = "Favourite")
                            }
                        }
                    }
                }
            }
        }
    }
}
