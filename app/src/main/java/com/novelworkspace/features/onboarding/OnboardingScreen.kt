package com.novelworkspace.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelworkspace.ui.components.WorkspaceCard

@Composable
fun OnboardingScreen(
    onSelectVault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Novel Workspace", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect your existing Obsidian vault to browse characters, traits, worldbuilding lore, and custom DataviewJS dashboards natively.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        WorkspaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Vault Remains Authoritative", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Files stay in your original Obsidian folder. Nothing is moved, renamed, or duplicated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        WorkspaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Incremental Sync & Conflict Safety", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "External changes from Obsidian or Syncthing are detected automatically before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        WorkspaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Persistent SAF Permissions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android grants scoped read/write access strictly to the folder you select.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onSelectVault,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Select Obsidian Vault Folder")
        }
    }
}
