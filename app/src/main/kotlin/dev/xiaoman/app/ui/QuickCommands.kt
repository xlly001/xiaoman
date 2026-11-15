package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.quick.QuickCommand

/** Two-column shortcut cards; the trailing row stays aligned when the count is odd. */
@Composable
internal fun QuickCommandGrid(
    commands: List<QuickCommand>,
    onRun: (QuickCommand) -> Unit,
    onEdit: (QuickCommand) -> Unit,
    onDelete: (QuickCommand) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        commands.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickCommandCard(row[0], onRun, onEdit, onDelete, Modifier.weight(1f))
                if (row.size > 1) {
                    QuickCommandCard(row[1], onRun, onEdit, onDelete, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickCommandCard(
    command: QuickCommand,
    onRun: (QuickCommand) -> Unit,
    onEdit: (QuickCommand) -> Unit,
    onDelete: (QuickCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by rememberSaveable(command.id) { mutableStateOf(false) }
    Surface(
        onClick = { onRun(command) },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 108.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(26.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(quickCommandIcon(command.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit(command) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete(command) })
                    }
                }
            }
            Text(command.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                command.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun QuickCommandDialog(
    initial: QuickCommand?,
    initialPrompt: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title.orEmpty().ifEmpty { initialPrompt.take(12) }) }
    var prompt by rememberSaveable(initial?.id) { mutableStateOf(initial?.prompt ?: initialPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(if (initial == null) R.string.quick_dialog_add_title else R.string.quick_dialog_edit_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(QuickCommandTitleLimit) },
                    label = { Text(stringResource(R.string.quick_dialog_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.quick_dialog_prompt_label)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.quick_dialog_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, prompt) }, enabled = title.isNotBlank() && prompt.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun DeleteQuickCommandDialog(command: QuickCommand, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_delete_title), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(R.string.quick_delete_message, command.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun quickCommandIcon(key: String): ImageVector = when (key) {
    QuickCommand.ICON_SHOPPING -> Icons.Filled.ShoppingBag
    QuickCommand.ICON_BELL -> Icons.Filled.NotificationsNone
    QuickCommand.ICON_MEMORY -> Icons.Filled.Psychology
    QuickCommand.ICON_HEALTH -> Icons.Filled.FavoriteBorder
    QuickCommand.ICON_PLAN -> Icons.Filled.Checklist
    else -> Icons.Filled.AutoAwesome
}

private const val QuickCommandTitleLimit = 24
