package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.memory.BasicMemoryFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A row of the memory list: every note when the query is blank, search hits otherwise. */
data class MemoryRow(val permalink: String, val title: String, val detail: String)

data class MemoryState(
    val query: String = "",
    val rows: List<MemoryRow> = emptyList(),
    val loading: Boolean = true,
    /** The opened note, rendered as Markdown text. */
    val open: Note? = null,
    val openMarkdown: String = "",
    val openMissing: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class MemoryViewModel(private val memory: MemoryStore) : ViewModel() {
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch { query.debounce(250).collect { refresh() } }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        query.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            // The first load paints a spinner; a later re-sync keeps the rows on screen so
            // returning to this tab never flashes an empty state.
            _state.update { it.copy(loading = it.rows.isEmpty()) }
            val q = _state.value.query.trim()
            // The store walks the note directory, so this must never run on the main thread.
            val rows = withContext(Dispatchers.IO) {
                runCatching {
                    if (q.isEmpty()) {
                        memory.list().sortedByDescending { it.updatedAtMillis }.map { MemoryRow(it.permalink, it.title, it.permalink) }
                    } else {
                        memory.search(q, limit = 50).map { MemoryRow(it.permalink, it.title, it.snippet.replace('\n', ' ')) }
                    }
                }.getOrDefault(emptyList())
            }
            _state.update { it.copy(rows = rows, loading = false) }
        }
    }

    fun open(permalink: String) {
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) { runCatching { memory.read(permalink) }.getOrNull() }
            _state.update { it.copy(open = note, openMarkdown = note?.let(BasicMemoryFormat::render).orEmpty(), openMissing = note == null) }
        }
    }

    fun close() = _state.update { it.copy(open = null, openMarkdown = "", openMissing = false) }

    fun delete(permalink: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { memory.delete(permalink) } }
            close()
            refresh()
        }
    }

    fun create(title: String, permalink: String, type: String, tags: String, body: String) {
        viewModelScope.launch {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) {
                _state.update { it.copy(error = "empty") }
                return@launch
            }
            val path = permalink.trim().ifEmpty { cleanTitle }
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = Note(
                        title = cleanTitle,
                        permalink = path,
                        type = type.trim().ifEmpty { "note" },
                        tags = parseTags(tags),
                        body = body,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    val text = BasicMemoryFormat.render(note)
                    memory.write(BasicMemoryFormat.parse(text, path, System.currentTimeMillis()))
                }
            }.onSuccess {
                _state.update { it.copy(error = null) }
                close()
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: "save") }
            }
        }
    }

    fun update(original: Note, title: String, permalink: String, type: String, tags: String, body: String) {
        viewModelScope.launch {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) {
                _state.update { it.copy(error = "empty") }
                return@launch
            }
            val path = permalink.trim().ifEmpty { original.permalink }
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = Note(
                        title = cleanTitle,
                        permalink = path,
                        type = type.trim().ifEmpty { "note" },
                        tags = parseTags(tags),
                        body = body,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    val text = BasicMemoryFormat.render(note)
                    memory.write(BasicMemoryFormat.parse(text, path, System.currentTimeMillis()))
                }
            }.onSuccess {
                _state.update { it.copy(error = null) }
                close()
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: "save") }
            }
        }
    }

    private fun parseTags(text: String): List<String> =
        text.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
}

@Composable
fun MemoryScreen(graph: AppGraph) {
    val vm = viewModel { MemoryViewModel(graph.memory) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    val note = state.open
    if (note != null) {
        NoteView(
            note = note,
            markdown = state.openMarkdown,
            active = LocalTabActive.current,
            onBack = vm::close,
            onEdit = { editing = note },
            onDelete = { vm.delete(note.permalink) },
        )
    } else {
        MemoryList(state, vm, active = LocalTabActive.current, onNew = { creating = true })
    }
    if (creating) {
        MemoryEditorDialog(
            note = null,
            onDismiss = { creating = false },
            onSave = { title, permalink, type, tags, body ->
                vm.create(title, permalink, type, tags, body)
                creating = false
            },
        )
    }
    editing?.let { current ->
        MemoryEditorDialog(
            note = current,
            onDismiss = { editing = null },
            onSave = { title, permalink, type, tags, body ->
                vm.update(current, title, permalink, type, tags, body)
                editing = null
            },
        )
    }
}

@Composable
private fun MemoryList(state: MemoryState, vm: MemoryViewModel, active: Boolean, onNew: () -> Unit) {
    // This page stays composed while other tabs are shown, so re-sync on every visit instead of
    // only once: the agent may have written notes in the meantime. The refresh now runs off the
    // main thread and keeps the current rows, so it costs no visible reload.
    LaunchedEffect(active) { if (active) vm.refresh() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.nav_memory), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.memory_new), modifier = Modifier.padding(start = 4.dp))
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear)) }
                }
            },
            placeholder = { Text(stringResource(R.string.memory_search_hint)) },
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.rows.isEmpty()) {
            Text(
                stringResource(if (state.query.isBlank()) R.string.memory_empty else R.string.memory_no_hits),
                modifier = Modifier.padding(18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.openMissing) {
            Text(stringResource(R.string.memory_not_found), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        if (state.error != null && state.error != "empty") {
            Text(state.error, modifier = Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = DockSpace + 8.dp),
        ) {
            items(state.rows, key = { it.permalink }) { row ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    ListItem(
                        headlineContent = { Text(row.title, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = { Text(row.detail, maxLines = 2, style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.clickable { vm.open(row.permalink) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteView(
    note: Note,
    markdown: String,
    active: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    // The note also stays composed when another tab is shown; the back key must belong to
    // whichever page is actually on screen.
    BackHandler(enabled = active, onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(6.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(note.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.memory_edit)) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.memory_delete)) }
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(markdown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_message, note.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(stringResource(R.string.memory_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MemoryEditorDialog(
    note: Note?,
    onDismiss: () -> Unit,
    onSave: (title: String, permalink: String, type: String, tags: String, body: String) -> Unit,
) {
    var title by remember(note?.permalink) { mutableStateOf(note?.title.orEmpty()) }
    var permalink by remember(note?.permalink) { mutableStateOf(note?.permalink.orEmpty()) }
    var type by remember(note?.permalink) { mutableStateOf(note?.type ?: "note") }
    var tags by remember(note?.permalink) { mutableStateOf(note?.tags?.joinToString(", ").orEmpty()) }
    var body by remember(note?.permalink) { mutableStateOf(note?.body.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(if (note == null) R.string.memory_new_title else R.string.memory_edit_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.memory_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    permalink,
                    { permalink = it },
                    label = { Text(stringResource(R.string.memory_permalink_label)) },
                    singleLine = true,
                    enabled = note == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(type, { type = it }, label = { Text(stringResource(R.string.memory_type_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.memory_tags_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.memory_body_label)) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.memory_edit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (title.isBlank()) Text(stringResource(R.string.memory_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, permalink, type, tags, body) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.memory_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
