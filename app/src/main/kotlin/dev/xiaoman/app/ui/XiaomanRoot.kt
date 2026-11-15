package dev.xiaoman.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    CAPABILITIES(R.string.nav_capabilities, Icons.Filled.Extension),
    TASKS(R.string.nav_tasks, Icons.Filled.CheckCircle),
    MEMORY(R.string.nav_memory, Icons.Filled.Psychology),
    XIAOMAN(R.string.nav_xiaoman, Icons.Filled.AutoAwesome),

    /** Reached from the header gear, not from the bottom bar. */
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun XiaomanRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    /** One draft shared by the dock on every tab. */
    var draft by rememberSaveable { mutableStateOf("") }
    // Set by the skills screen when the user starts authoring; the shared composer picks it up.
    var pendingChatDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingChatDraft) {
        pendingChatDraft?.takeIf { it.isNotEmpty() }?.let {
            draft = it
            pendingChatDraft = null
        }
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            Modifier.fillMaxSize()
                // Soft blue wash at the top, like the reference app; cards stay white on top.
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val taskId = openTaskId
            if (taskId != null) {
                TaskDetailScreen(graph, taskId, onBack = { openTaskId = null })
            } else {
                when (tab) {
                    Tab.CHAT -> ChatScreen(
                        graph,
                        onOpenSettings = { tab = Tab.SETTINGS },
                        onFillDraft = { draft = it },
                        onOpenTask = { openTaskId = it },
                        onSearch = { searchOpen = true },
                    )
                    Tab.CAPABILITIES -> CapabilitiesScreen(graph) { pending ->
                        pendingChatDraft = pending
                        tab = Tab.CHAT
                    }
                    Tab.TASKS -> TaskHistoryScreen(graph) { openTaskId = it }
                    Tab.MEMORY -> MemoryScreen(graph)
                    Tab.XIAOMAN -> DiaryScreen(graph)
                    Tab.SETTINGS -> SettingsScreen(graph)
                }
            }
            if (searchOpen) {
                SearchScreen(
                    graph,
                    onClose = { searchOpen = false },
                    onOpenTask = {
                        searchOpen = false
                        openTaskId = it
                        tab = Tab.TASKS
                    },
                )
            } else {
                // The composer and navigation are separate opaque pills on a transparent layer,
                // so the page stays visible in the gaps and margins around them.
                ChatDock(
                    graph = graph,
                    selected = tab,
                    onSelect = {
                        openTaskId = null
                        tab = it
                    },
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSent = {
                        // A task thread keeps the user in that conversation; the main chat
                        // returns to the chat tab after sending.
                        if (openTaskId == null) tab = Tab.CHAT
                    },
                    keyboardOpen = keyboardOpen,
                    activeTaskId = openTaskId,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
