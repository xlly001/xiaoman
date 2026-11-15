package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.TaskThread

/** A phone task's own conversation, opened from the task card in the main chat. */
@Composable
fun TaskDetailScreen(graph: AppGraph, taskId: String, onBack: () -> Unit) {
    val threads by graph.taskThreads.threads.collectAsStateWithLifecycle()
    val thread = threads.firstOrNull { it.id == taskId }
    val rows = remember(thread?.items) { chatTimeline(thread?.items.orEmpty()) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                stringResource(R.string.task_detail_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        if (thread == null) {
            Text(
                stringResource(R.string.task_detail_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp),
            )
            return@Column
        }
        TaskSummary(thread, onStop = if (thread.running) graph.session::stop else null)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = DockSpace + 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is TimelineRow.Message -> ChatMessage(row.item)
                    is TimelineRow.Process -> ProcessCard(row.entries, initiallyExpanded = true)
                }
            }
        }
    }
}

@Composable
private fun TaskSummary(thread: TaskThread, onStop: (() -> Unit)? = null) {
    val status = when {
        thread.running -> stringResource(R.string.task_entry_running)
        thread.success == true -> stringResource(R.string.task_entry_done)
        else -> stringResource(R.string.task_entry_failed)
    }
    val color = when {
        thread.running || thread.success == true -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.AutoMirrored.Outlined.ListAlt,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(thread.goal, style = MaterialTheme.typography.titleSmall)
                Text(
                    status + " · " + stringResource(R.string.task_detail_steps, thread.steps),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                if (thread.summary.isNotBlank()) {
                    Text(
                        thread.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onStop != null) {
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.chat_stop), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
