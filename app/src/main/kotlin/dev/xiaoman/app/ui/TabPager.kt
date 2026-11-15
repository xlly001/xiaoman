package dev.xiaoman.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * True for the page that is currently on screen. Pages that stay composed in the background
 * (see [TabPager]) use it to skip work that only makes sense while visible — swallowing the
 * hardware back button, or firing a model request the user cannot see.
 */
val LocalTabActive = staticCompositionLocalOf { true }

private const val EnterMillis = 260
private const val ExitMillis = 180

/** Horizontal travel of a page entering or leaving, as a nudge rather than a full page slide. */
private val TabTravel = 30.dp

/**
 * Swaps pages without rebuilding them.
 *
 * `when (tab)` used to drop the whole screen out of composition on every switch, so coming back
 * re-ran its loading effects (memory index sync, diary summary check, first-frame measurement of
 * long lists) while the user stared at an empty frame. Here every page the user has visited stays
 * composed: scroll positions, loaded rows and pager state survive, and a switch is a cheap
 * alpha/offset layer update.
 *
 * Pages that were never visited are mounted on first selection, then [warmUp] mounts the rest in
 * the background shortly after the first screen settles, so even the first visit is instant.
 *
 * The transition is built on a single [Animatable] per page, retargeted on every selection. Rapid
 * taps therefore interrupt the running motion and continue from the current offset instead of
 * restarting it from zero.
 */
@Composable
fun TabPager(
    selected: Tab,
    modifier: Modifier = Modifier,
    /** False while a full-screen overlay (task thread, search) covers the pages. */
    active: Boolean = true,
    warmUp: List<Tab> = emptyList(),
    warmUpDelayMillis: Long = 1200L,
    content: @Composable (Tab) -> Unit,
) {
    val mounted = remember { mutableStateListOf(selected) }
    // Read through a virtual copy: a freshly selected page must be drawn on the very first frame,
    // while the list itself is only mutated from a coroutine (never during composition).
    val layers = if (selected in mounted) mounted else mounted + selected
    LaunchedEffect(selected) { if (selected !in mounted) mounted.add(selected) }
    LaunchedEffect(warmUp) {
        if (warmUp.isEmpty()) return@LaunchedEffect
        delay(warmUpDelayMillis)
        warmUp.forEach { tab ->
            if (tab !in mounted) {
                mounted.add(tab)
                // One page per tick: building five screens back to back lands as a single long
                // frame, which is exactly the stutter this whole file exists to avoid.
                delay(220)
            }
        }
    }
    val motion = remember { TabMotion(selected) }
    if (motion.last != selected) {
        motion.forward = selected.ordinal > motion.last.ordinal
        motion.last = selected
    }
    Box(modifier.fillMaxSize()) {
        layers.forEach { tab ->
            key(tab) {
                TabLayer(
                    visible = tab == selected,
                    forward = motion.forward,
                    active = active,
                ) {
                    content(tab)
                }
            }
        }
    }
}

/** Direction of the last switch; pages enter from the side the user is travelling towards. */
private class TabMotion(var last: Tab) {
    var forward: Boolean = true
}

@Composable
private fun TabLayer(
    visible: Boolean,
    forward: Boolean,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    // Starts at 0 so a page composes hidden and fades in even on its very first mount.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (visible) EnterMillis else ExitMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    val travel = with(LocalDensity.current) { TabTravel.toPx() } * if (forward) 1f else -1f
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer {
                val shown = progress.value
                alpha = shown
                translationX = travel * (1f - shown)
                val scale = 0.985f + 0.015f * shown
                scaleX = scale
                scaleY = scale
            }
            // Pages are kept composed but must cost nothing to draw once faded out.
            .drawWithContent { if (progress.value > 0.002f) drawContent() },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        ) {
            CompositionLocalProvider(LocalTabActive provides (visible && active)) { content() }
        }
        // A kept-alive page is still in the tree, so it has to be kept out of the touch path.
        if (!visible) {
            Box(Modifier.matchParentSize().blockPointerInput())
        }
    }
}

/**
 * Swallows every pointer event at this node. Children still receive their events first (Compose
 * dispatches from the leaf outwards), so a full-screen overlay stays interactive while the page
 * stack underneath it is unreachable.
 */
internal fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
