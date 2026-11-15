package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.session.TaskThread

private sealed interface SearchHit {
    val text: String
    data class Main(val item: ChatItem, override val text: String) : SearchHit
    data class Task(val thread: TaskThread) : SearchHit {
        override val text: String get() = buildString {
            append(thread.goal)
            if (thread.summary.isNotBlank()) append("\n").append(thread.summary)
            thread.items.forEach { appendLineOrNull(it) }
        }
    }
}

private fun StringBuilder.appendLineOrNull(item: ChatItem) {
    itemText(item)?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
}

private fun itemText(item: ChatItem): String? = when (item) {
    is ChatItem.User -> item.text
    is ChatItem.Assistant -> item.text
    is ChatItem.Progress -> item.text
    is ChatItem.Question -> item.text
    is ChatItem.Error -> item.text
    is ChatItem.TaskEntry -> listOf(item.goal, item.summary).filter { it.isNotBlank() }.joinToString(" ")
}

private fun snippet(text: String): String = text.replace('\n', ' ').trim().take(140)

/** Global search over the main conversation and every task sub-conversation. */
@Composable
fun SearchScreen(graph: AppGraph, onClose: () -> Unit, onOpenTask: (String) -> Unit) {
    val items by graph.session.items.collectAsStateWithLifecycle()
    val threads by graph.taskThreads.threads.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val hits = remember(query, items, threads) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            emptyList()
        } else {
            buildList {
                items.forEach { item ->
                    if (item is ChatItem.User || item is ChatItem.Assistant || item is ChatItem.Question || item is ChatItem.Error) {
                        val text = itemText(item).orEmpty()
                        if (text.contains(needle, ignoreCase = true)) add(SearchHit.Main(item, text))
                    }
                }
                threads.forEach { thread ->
                    val searchable = SearchHit.Task(thread).text
                    if (searchable.contains(needle, ignoreCase = true)) add(SearchHit.Task(thread))
                }
            }.take(60)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.search_close),
                )
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        if (query.isNotBlank() && hits.isEmpty()) {
            Text(
                stringResource(R.string.search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = DockSpace + 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hits, key = { hit -> hitKey(hit) }) { hit ->
                when (hit) {
                    is SearchHit.Main -> SearchRow(
                        label = stringResource(R.string.search_main),
                        text = snippet(hit.text),
                        onClick = onClose,
                    )
                    is SearchHit.Task -> SearchRow(
                        label = stringResource(R.string.search_task),
                        text = snippet(hit.thread.goal.ifBlank { hit.thread.summary }),
                        onClick = { onOpenTask(hit.thread.id) },
                        trailing = true,
                    )
                }
            }
        }
    }
}

private fun hitKey(hit: SearchHit): String = when (hit) {
    is SearchHit.Main -> "m-${hit.item.id}"
    is SearchHit.Task -> "t-${hit.thread.id}"
}

@Composable
private fun SearchRow(label: String, text: String, onClick: () -> Unit, trailing: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (trailing) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
