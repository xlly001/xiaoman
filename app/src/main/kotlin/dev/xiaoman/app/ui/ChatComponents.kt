package dev.xiaoman.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.quick.QuickCommand
import dev.xiaoman.app.session.ChatItem

/** Open Muse style header: a glass circle, a centered name pill with a status subtitle, and a menu. */
@Composable
internal fun ChatHeader(
    ready: Boolean,
    busy: Boolean,
    attention: Boolean,
    onOpenSettings: () -> Unit,
    onClearHistory: () -> Unit,
    onSearch: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            GlassCircle(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.nav_settings),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
            Box {
                GlassCircle(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        onClick = { menu = false; onSearch() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_clear)) },
                        leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                        onClick = { menu = false; onClearHistory() },
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Round mascot avatar above the name pill, matching the Open Muse header.
            Image(
                painter = painterResource(R.drawable.avatar_xiaoman),
                contentDescription = null,
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
            Surface(
                modifier = Modifier.offset(y = (-9).dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 2.dp,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                stringResource(
                    when {
                        attention -> R.string.chat_status_attention
                        busy -> R.string.chat_status_busy
                        ready -> R.string.chat_status_ready
                        else -> R.string.chat_status_setup
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (attention) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(y = (-7).dp).padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun GlassCircle(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = CircleShape
    val color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = color,
            border = border,
            shadowElevation = 2.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
        }
    } else {
        Surface(
            shape = shape,
            color = color,
            border = border,
            shadowElevation = 2.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
internal fun ChatWelcome(
    commands: List<QuickCommand>,
    onRun: (QuickCommand) -> Unit,
    onEdit: (QuickCommand) -> Unit,
    onDelete: (QuickCommand) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.chat_greeting), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.chat_greeting_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.quick_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.quick_add), modifier = Modifier.padding(start = 4.dp))
            }
        }
        QuickCommandGrid(commands, onRun, onEdit, onDelete)
        Text(
            stringResource(R.string.chat_greeting_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
internal fun ProcessCard(entries: List<ChatItem.Progress>, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(entries.first().id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.chat_process, entries.size),
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.chat_process_collapse else R.string.chat_process_expand),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach {
                        Text(
                            it.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessage(item: ChatItem, onOpenTask: (String) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.chat_copied)
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(0.87f).combinedClickable(
                    onClick = {},
                    onLongClick = { copy(item.text) },
                ),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (item.imageCount > 0) {
                        Text(
                            stringResource(R.string.chat_image_count, item.imageCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (item.text.isNotBlank()) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = if (item.imageCount > 0) 4.dp else 0.dp),
                        )
                    }
                }
            }
        }
        is ChatItem.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(0.94f).combinedClickable(
                    onClick = {},
                    onLongClick = { copy(item.text) },
                ),
            ) {
                Text(chatRichText(item.text), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { copy(item.text) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        is ChatItem.Question -> NoticeCard(
            if (item.takeover) R.string.chat_takeover_label else R.string.chat_question_label,
            item.text,
            error = false,
        )
        is ChatItem.Error -> NoticeCard(R.string.chat_error_label, item.text, error = true)
        is ChatItem.TaskEntry -> TaskEntryCard(item, onOpenTask)
        is ChatItem.Progress -> Unit
    }
}

/** Entry under the main chat text: tapping opens the task's own sub-conversation. */
@Composable
private fun TaskEntryCard(item: ChatItem.TaskEntry, onOpenTask: (String) -> Unit) {
    val status = when {
        item.running -> stringResource(R.string.task_entry_running)
        item.success -> stringResource(R.string.task_entry_done)
        else -> stringResource(R.string.task_entry_failed)
    }
    val statusColor = when {
        item.running -> MaterialTheme.colorScheme.primary
        item.success -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable { onOpenTask(item.taskId) },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Outlined.ListAlt,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.goal.ifBlank { stringResource(R.string.task_detail_title) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (!item.running && item.summary.isNotBlank()) {
                    Text(
                        item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.task_entry_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NoticeCard(titleRes: Int, body: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                chatRichText(body),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Basic emphasis and lists from model replies, rendered as text rather than raw Markdown markers. */
internal fun chatRichText(raw: String) = buildAnnotatedString {
    val text = raw.replace(Regex("(?m)^#{1,6}\\s+"), "").replace(Regex("(?m)^[-*]\\s+"), "• ")
    var position = 0
    Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach { match ->
        append(text.substring(position, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
        position = match.range.last + 1
    }
    append(text.substring(position))
}
