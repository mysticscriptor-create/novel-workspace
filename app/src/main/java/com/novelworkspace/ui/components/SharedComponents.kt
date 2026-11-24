package com.novelworkspace.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelworkspace.core.vault.WriteResult
import com.novelworkspace.ui.theme.TabularNumberStyle

@Composable
fun WorkspaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val mod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(
        modifier = mod.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
fun UnboxedMetadataLine(
    parts: List<String>,
    modifier: Modifier = Modifier
) {
    val clean = parts.filter { it.isNotBlank() }
    if (clean.isEmpty()) return
    Text(
        text = clean.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun StatBarRow(
    label: String,
    baseValue: Double,
    boostValue: Double = 0.0,
    maxScale: Double = 60.0
) {
    val total = baseValue + boostValue
    val fraction = (total / maxScale).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val txt = if (boostValue != 0.0) {
                "${formatNum(baseValue)} (+${formatNum(boostValue)})"
            } else {
                formatNum(baseValue)
            }
            Text(txt, style = TabularNumberStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize))
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(5.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WikilinkChipGroup(
    label: String,
    targets: List<String>,
    onLinkClick: (String) -> Unit
) {
    if (targets.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            targets.forEach { raw ->
                val clean = raw.removePrefix("[[").removeSuffix("]]").substringBefore('|').trim()
                if (clean.isNotBlank()) {
                    AssistChip(
                        onClick = { onLinkClick(clean) },
                        label = { Text(clean, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    itemName: String,
    detail: String = "This removes the item from your connected Obsidian vault.",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $itemName?") },
        text = { Text(detail) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ConflictResolutionDialog(
    conflict: WriteResult.Conflict,
    onKeepExternal: () -> Unit,
    onOverwriteMine: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("External Change Detected") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "\"${conflict.relativePath}\" was modified externally (for example in Obsidian) while you were editing it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Text("External Version (Vault)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                Text(
                    conflict.externalContent.take(600),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 120.dp).padding(vertical = 4.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text("Your Pending Changes", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                Text(
                    conflict.pendingContent.take(600),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 120.dp).padding(vertical = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = onOverwriteMine) { Text("Save Mine") }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onKeepExternal) { Text("Load External") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

fun formatNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
