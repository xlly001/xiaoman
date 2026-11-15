package dev.xiaoman.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.quick.QuickCommand
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.hands.XiaomanAccessibilityService

/** Height of the floating header scrim (avatar + name pill + status); the conversation scrolls underneath it. */
private val HeaderOverlayHeight = 160.dp

@Composable
fun ChatScreen(
    graph: AppGraph,
    onOpenSettings: () -> Unit,
    onOpenDiary: () -> Unit,
    onFillDraft: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val session = graph.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val speaking by graph.speaking.collectAsStateWithLifecycle()
    val commands by graph.quickCommands.commands.collectAsStateWithLifecycle()
    val service by XiaomanAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val notificationsGranted = rememberNotificationPermission()
    var addCommand by rememberSaveable { mutableStateOf(false) }
    var editCommand by remember { mutableStateOf<QuickCommand?>(null) }
    var deleteCommand by remember { mutableStateOf<QuickCommand?>(null) }
    var clearHistory by rememberSaveable { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    val rows = remember(items) { chatTimeline(items) }
    val listState = rememberLazyListState()
    // Re-scroll as the streaming reply grows (the last bubble's text changes without adding a row).
    val lastReplyLength = remember(items) { (items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0 }
    val showAccessibilityBanner = service == null
    val showModelsBanner = !settings.configured
    val showNotificationsBanner = !notificationsGranted.granted
    val bannerCount = listOf(showAccessibilityBanner, showModelsBanner, showNotificationsBanner).count { it }
    // Banners are list items now, so the last message's index must include them.
    val contentCount = bannerCount + if (items.isEmpty()) 1 else rows.size
    LaunchedEffect(rows.size, lastReplyLength, contentCount) {
        if (contentCount > 0) listState.animateScrollToItem(contentCount - 1)
    }
    LaunchedEffect(approval?.id) {
        if (approval != null) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }

    // Keep the last message above the two opaque pills; the space around them stays transparent.
    val bottomSpace = if (keyboardOpen) 64.dp else DockSpace - 4.dp

    // Frosted glass at the top fading to fully transparent, so the conversation that
    // scrolls underneath the header stays visible instead of being cut off by a solid bar.
    val headerScrim = Brush.verticalGradient(
        0.0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.10f),
        0.5f to MaterialTheme.colorScheme.surface.copy(alpha = 0.03f),
        1.0f to Color.Transparent,
    )

    Box(Modifier.fillMaxSize()) {
        // The conversation fills the whole screen and scrolls *under* the floating header.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = HeaderOverlayHeight,
                bottom = bottomSpace,
            ),
            verticalArrangement = if (items.isEmpty()) {
                Arrangement.spacedBy(8.dp)
            } else {
                // Keep the newest message close to the composer instead of leaving a blank strip below it.
                Arrangement.spacedBy(8.dp, Alignment.Bottom)
            },
        ) {
            if (showAccessibilityBanner) {
                item(key = "banner-accessibility") {
                    MuseBanner(stringResource(R.string.banner_accessibility), stringResource(R.string.banner_accessibility_action)) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (showModelsBanner) {
                item(key = "banner-models") {
                    MuseBanner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
                }
            }
            if (showNotificationsBanner) {
                item(key = "banner-notifications") {
                    MuseBanner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)
                }
            }
            if (items.isEmpty()) {
                item {
                    ChatWelcome(
                        commands = commands,
                        onRun = { onFillDraft(it.prompt) },
                        onEdit = { editCommand = it },
                        onDelete = { deleteCommand = it },
                        onAdd = { addCommand = true },
                    )
                }
            }
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is TimelineRow.Message -> ChatMessage(
                        row.item,
                        onOpenTask,
                        speakingText = speaking,
                        onToggleSpeak = graph::toggleSpeaking,
                    )
                    is TimelineRow.Process -> ProcessCard(row.entries)
                }
            }
        }
        // Only the approval card floats above the dock. The composer already turns its
        // mic into a stop button while busy, so a separate "processing" strip is redundant.
        if (approval != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(bottom = if (keyboardOpen) 56.dp else DockSpace),
            ) {
                ApprovalCard(approval!!, session)
            }
        }
        // Only the three glass controls, floating on a frosted-to-transparent scrim.
        Box(
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(HeaderOverlayHeight)
                .background(headerScrim),
        ) {
            ChatHeader(
                ready = settings.configured && service != null,
                busy = busy,
                attention = approval != null || question != null,
                onOpenSettings = onOpenSettings,
                onOpenDiary = onOpenDiary,
                onClearHistory = { clearHistory = true },
                onSearch = onSearch,
            )
        }
    }

    if (addCommand) {
        QuickCommandDialog(
            initial = null,
            onDismiss = { addCommand = false },
            onSave = { title, prompt ->
                graph.quickCommands.add(title, prompt)
                addCommand = false
            },
        )
    }
    editCommand?.let { command ->
        QuickCommandDialog(
            initial = command,
            onDismiss = { editCommand = null },
            onSave = { title, prompt ->
                graph.quickCommands.edit(command.id, title, prompt)
                editCommand = null
            },
        )
    }
    deleteCommand?.let { command ->
        DeleteQuickCommandDialog(
            command = command,
            onDismiss = { deleteCommand = null },
            onConfirm = {
                graph.quickCommands.delete(command.id)
                deleteCommand = null
            },
        )
    }
    if (clearHistory) {
        AlertDialog(
            onDismissRequest = { clearHistory = false },
            title = { Text(stringResource(R.string.chat_clear_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.chat_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    session.clearHistory()
                    clearHistory = false
                }) { Text(stringResource(R.string.chat_clear_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearHistory = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MuseBanner(text: String, action: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private class NotificationPermission(val granted: Boolean, val request: () -> Unit)
@Composable
private fun rememberNotificationPermission(): NotificationPermission {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermission(true) {}
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return NotificationPermission(granted) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
