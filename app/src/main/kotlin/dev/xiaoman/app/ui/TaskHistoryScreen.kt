package dev.xiaoman.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.TaskThread
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TaskFilter(@StringRes val label: Int) {
    ALL(R.string.tasks_filter_all),
    RUNNING(R.string.tasks_filter_running),
    DONE(R.string.tasks_filter_done),
    FAILED(R.string.tasks_filter_failed),
}

/** Every phone task Xiaoman has run, newest first. */
@Composable
fun TaskHistoryScreen(graph: AppGraph, onOpenTask: (String) -> Unit) {
    val threads by graph.taskThreads.threads.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }
    val visible = remember(threads, filter) {
        threads
            .filter {
                when (filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.RUNNING -> it.running
                    TaskFilter.DONE -> !it.running && it.success == true
                    TaskFilter.FAILED -> !it.running && it.success != true
                }
            }
            .sortedByDescending { it.startedAt }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.nav_tasks), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.tasks_count, threads.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { filter = item },
                    label = { Text(stringResource(item.label)) },
                )
            }
        }
        if (visible.isEmpty()) {
            EmptyTasks(Modifier.fillMaxWidth().padding(18.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = DockSpace + 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { thread ->
                    TaskHistoryCard(thread) { onOpenTask(thread.id) }
                }
            }
        }
    }
}

@Composable
private fun EmptyTasks(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tasks_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.tasks_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskHistoryCard(thread: TaskThread, onClick: () -> Unit) {
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
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(9.dp)) {}
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    thread.goal.ifBlank { stringResource(R.string.task_detail_title) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status + " · " + stringResource(R.string.task_detail_steps, thread.steps) + " · " + timeOf(thread.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                if (thread.summary.isNotBlank()) {
                    Text(
                        thread.summary,
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

private fun timeOf(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
