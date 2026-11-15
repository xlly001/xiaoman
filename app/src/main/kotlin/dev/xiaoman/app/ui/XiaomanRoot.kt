package dev.xiaoman.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

/** Open Muse wash: cool blue at the top fading through lilac into warm peach at the bottom. */
private val MuseLightWash = listOf(
    Color(0xFFBBD8F2),
    Color(0xFFD5E4F4),
    Color(0xFFECE7EC),
    Color(0xFFF8E3CE),
)
private val MuseDarkWash = listOf(
    Color(0xFF16293B),
    Color(0xFF1B2836),
    Color(0xFF26242E),
    Color(0xFF302628),
)

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
fun XiaomanRoot(graph: AppGraph, darkTheme: Boolean = isSystemInDarkTheme()) {
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
    // Follow the app's own theme, not the system one: picking "深色" in Settings must
    // switch the full-screen wash too, otherwise dark cards sit on a light blue background.
    val wash = if (darkTheme) MuseDarkWash else MuseLightWash
    Scaffold(containerColor = Color.Transparent) { padding ->
        Box(
            Modifier.fillMaxSize()
                // One continuous wash behind every tab: blue -> lilac -> peach. Nothing is
                // painted over it, so cards and scrolling messages sit directly on the gradient.
                .background(
                    Brush.verticalGradient(
                        0.0f to wash[0],
                        0.42f to wash[1],
                        0.74f to wash[2],
                        1.0f to wash[3],
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
