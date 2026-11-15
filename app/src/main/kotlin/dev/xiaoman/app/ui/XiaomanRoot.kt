package dev.xiaoman.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    SKILLS(R.string.nav_skills, Icons.Filled.Extension),
    SERVICES(R.string.nav_services, Icons.Filled.Hub),
    MEMORY(R.string.nav_memory, Icons.Filled.Psychology),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun XiaomanRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    // Set by the skills screen when the user starts authoring; ChatScreen prefills its input once.
    var pendingChatDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!keyboardOpen) {
                MuseNavigationBar(tab) { tab = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (tab) {
                Tab.CHAT -> ChatScreen(
                    graph,
                    onOpenSettings = { tab = Tab.SETTINGS },
                    pendingDraft = pendingChatDraft,
                    onDraftConsumed = { pendingChatDraft = null },
                )
                Tab.SKILLS -> SkillsScreen(graph) { draft ->
                    pendingChatDraft = draft
                    tab = Tab.CHAT
                }
                Tab.SERVICES -> McpScreen(graph)
                Tab.MEMORY -> MemoryScreen(graph)
                Tab.SETTINGS -> SettingsScreen(graph)
            }
        }
    }
}

/** A soft floating pill instead of a hard Material navigation bar. */
@Composable
private fun MuseNavigationBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 10.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab.entries.forEach { item ->
                    val active = item == selected
                    val background by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        label = "nav-background",
                    )
                    val tint by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "nav-tint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(background)
                            .selectable(selected = active, onClick = { onSelect(item) }, role = Role.Tab)
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(item.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
