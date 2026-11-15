package dev.xiaoman.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.PendingApproval
import dev.xiaoman.app.session.PendingQuestion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "小满" capsule itself, grown downward into an agent confirmation.
 *
 * The compact state is exactly the name pill under the avatar; when an approval or question
 * arrives it keeps the same anchor, widens and expands downward with the prompt and actions.
 * It is rendered once in [XiaomanRoot] above every page, so a kept-alive tab can never hide it.
 */
@Composable
internal fun XiaomanIsland(
    approval: PendingApproval?,
    question: PendingQuestion?,
    session: AgentSession,
    modifier: Modifier = Modifier,
    onAnswerQuestion: () -> Unit = {},
    onAnswer: (String) -> Unit = {},
) {
    val state: IslandState? = approval?.let(IslandState::Approval)
        ?: question?.let(IslandState::Question)
    var lastState by remember { mutableStateOf<IslandState?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state != null) {
            lastState = state
            expanded = false
            // One frame as the compact capsule, then grow downward. Without the pause the
            // surface is measured at its final size and the "island" pops instead of expanding.
            delay(24)
            expanded = true
        } else {
            expanded = false
        }
    }
    AnimatedVisibility(
        visible = state != null,
        modifier = modifier,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
    ) {
        when (val shown = state ?: lastState) {
            is IslandState.Approval -> ApprovalContent(shown.pending, session, expanded)
            is IslandState.Question -> QuestionContent(shown.pending, onAnswerQuestion, onAnswer, expanded)
            null -> Unit
        }
    }
}

private sealed interface IslandState {
    data class Approval(val pending: PendingApproval) : IslandState
    data class Question(val pending: PendingQuestion) : IslandState
}

/**
 * The capsule: avatar + "小满". When [expanded] it widens, rounds into a card and reveals
 * [content] downward from the top, so the motion reads as the same pill growing.
 */
@Composable
private fun IslandShell(
    title: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val expandedWidth = (screenWidth - 28.dp).coerceAtMost(380.dp)
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidth else 96.dp,
        animationSpec = tween(220),
        label = "island-width",
    )
    val radius by animateDpAsState(
        targetValue = if (expanded) 26.dp else 50.dp,
        animationSpec = tween(220),
        label = "island-radius",
    )
    Surface(
        modifier = modifier.width(width).animateContentSize(),
        shape = RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.avatar_xiaoman),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
                if (expanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)) + expandVertically(tween(200), expandFrom = Alignment.Top),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Top),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun ApprovalContent(pending: PendingApproval, session: AgentSession, expanded: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var responding by remember(pending.id) { mutableStateOf(false) }
    var more by remember(pending.id) { mutableStateOf(false) }

    fun approve(approvalScope: ApprovalScope) {
        if (responding) return
        responding = true
        scope.launch {
            // Keep the screen-match guard: approval belongs to the observed app, never this card.
            if (context.findActivity()?.moveTaskToBack(true) == true) delay(650)
            session.answerApproval(ApprovalAnswer(true, approvalScope), pending.id)
        }
    }

    IslandShell(
        title = stringResource(R.string.approval_title),
        expanded = expanded,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(
            pending.request.prompt,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    responding = true
                    session.answerApproval(ApprovalAnswer(false), pending.id)
                },
                enabled = !responding,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.approval_deny)) }
            Button(
                onClick = { approve(ApprovalScope.ONCE) },
                enabled = !responding,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.approval_once)) }
        }
        if (more) {
            TextButton(onClick = { approve(ApprovalScope.TASK) }, enabled = !responding) {
                Text(stringResource(R.string.approval_task), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            TextButton(
                onClick = { more = true },
                enabled = !responding,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.approval_more), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun QuestionContent(
    pending: PendingQuestion,
    onAnswerQuestion: () -> Unit,
    onAnswer: (String) -> Unit,
    expanded: Boolean,
) {
    // Skill save/delete asks for a literal "确认"; real buttons avoid the user typing "确认"
    // into a fresh chat turn while the tool question arrives a moment later.
    val confirmation = pending.question.contains("回复“确认”保存") || pending.question.contains("回复“确认”删除")
    if (confirmation) {
        var responding by remember(pending.id) { mutableStateOf(false) }
        IslandShell(title = stringResource(R.string.question_island_title), expanded = expanded) {
            Text(
                pending.question,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { responding = true; onAnswer("取消") },
                    enabled = !responding,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.question_island_cancel)) }
                Button(
                    onClick = { responding = true; onAnswer("确认") },
                    enabled = !responding,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.question_island_confirm)) }
            }
        }
    } else {
        IslandShell(title = stringResource(R.string.question_island_title), expanded = expanded) {
            Text(
                pending.question,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onAnswerQuestion, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.question_island_answer), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
