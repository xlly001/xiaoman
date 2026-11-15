package dev.xiaoman.app.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/**
 * Pages mounted in the background right after launch, in the order they are most likely to be
 * opened. Their contents are built off screen so the first tap on each tab only has to fade it in.
 */
private val WarmUpTabs = listOf(Tab.TASKS, Tab.MEMORY, Tab.CAPABILITIES, Tab.XIAOMAN, Tab.SETTINGS)

private const val OverlayInMillis = 280
private const val OverlayOutMillis = 240
private const val DockInMillis = 260
private const val DockOutMillis = 200

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
    // Never build the other pages while a reply is streaming: that is the one moment the main
    // thread is already busy and an extra composition would show up as a stutter.
    val busy by graph.session.busy.collectAsStateWithLifecycle()
    val approval by graph.session.approval.collectAsStateWithLifecycle()
    val question by graph.session.question.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A failed read-aloud used to be log-only, so a phone with no TTS voice data looked like a
    // silent no-op. Surface the reason once and let the user act on it.
    val speakError by graph.speakError.collectAsStateWithLifecycle()
    LaunchedEffect(speakError) {
        val message = speakError ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(R.string.media_tts_error, message), Toast.LENGTH_LONG).show()
        graph.clearSpeakError()
    }
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
            // Pages stay composed; only the layer that is on top is drawn and reachable.
            TabPager(
                selected = tab,
                active = taskId == null && !searchOpen,
                warmUp = if (busy) emptyList() else WarmUpTabs,
            ) { target ->
                when (target) {
                    Tab.CHAT -> ChatScreen(
                        graph,
                        onOpenSettings = { tab = Tab.SETTINGS },
                        onOpenDiary = { tab = Tab.XIAOMAN },
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
            // A task thread slides over the tabs instead of replacing them, so closing it returns
            // to a page that was never torn down. AnimatedContent keeps the outgoing thread around
            // while it leaves, which also makes an early "back" a reverse, not a jump cut.
            AnimatedContent(
                targetState = taskId,
                transitionSpec = {
                    if (targetState != null) {
                        (
                            slideInHorizontally(tween(OverlayInMillis, easing = FastOutSlowInEasing)) { it / 5 } +
                                fadeIn(tween(OverlayInMillis - 60))
                            ) togetherWith fadeOut(tween(140))
                    } else {
                        fadeIn(tween(120)) togetherWith
                            (
                                slideOutHorizontally(tween(OverlayOutMillis, easing = FastOutSlowInEasing)) { it / 5 } +
                                    fadeOut(tween(OverlayOutMillis - 40))
                                )
                    }
                },
                label = "task-thread",
            ) { id ->
                if (id == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().blockPointerInput()) {
                        TaskDetailScreen(graph, id, onBack = { openTaskId = null })
                    }
                }
            }
            // Search is a sheet over the current page: it rises from the bottom while the dock
            // ducks away, so the two motions read as one gesture.
            AnimatedVisibility(
                visible = searchOpen,
                enter = slideInVertically(tween(OverlayInMillis, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(140)),
                exit = slideOutVertically(tween(OverlayOutMillis, easing = FastOutSlowInEasing)) { it } +
                    fadeOut(tween(DockOutMillis)),
            ) {
                Box(Modifier.fillMaxSize().blockPointerInput()) {
                    SearchScreen(
                        graph,
                        onClose = { searchOpen = false },
                        onOpenTask = {
                            searchOpen = false
                            openTaskId = it
                            tab = Tab.TASKS
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = !searchOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(DockInMillis, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(DockInMillis)),
                exit = slideOutVertically(tween(DockOutMillis, easing = FastOutSlowInEasing)) { it } +
                    fadeOut(tween(DockOutMillis)),
            ) {
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
                )
            }
            // Agent-initiated interruptions grow out of the top "小满" capsule and sit above
            // every page, task thread and the search sheet, so an approval can never hide behind
            // a kept-alive tab again.
            XiaomanIsland(
                approval = approval,
                question = question,
                session = graph.session,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(20f),
                onAnswerQuestion = { searchOpen = false },
            )
        }
    }
}
