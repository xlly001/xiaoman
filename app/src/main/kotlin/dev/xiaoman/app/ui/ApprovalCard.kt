package dev.xiaoman.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
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
 * Dynamic-island style surface that grows out of the top "小满" capsule.
 *
 * Agent-initiated interactions used to live inside [ChatScreen], so a pending approval was
 * invisible while the user was in a task thread, the search sheet or another tab. Rendering it
 * once above every page (from [XiaomanRoot]) makes the capsule the single place where Xiaoman
 * interrupts the user. Local, user-initiated dialogs (delete memory, clear history...) stay as
 * dialogs because they belong to the page that started them.
 */
@Composable
internal fun XiaomanIsland(
    approval: PendingApproval?,
    question: PendingQuestion?,
    session: AgentSession,
    modifier: Modifier = Modifier,
    onAnswerQuestion: () -> Unit = {},
) {
    val state: IslandState? = approval?.let(IslandState::Approval)
        ?: question?.let(IslandState::Question)
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            (
                fadeIn(tween(160)) +
                    scaleIn(tween(220), initialScale = 0.82f, transformOrigin = TransformOrigin(0.5f, 0f))
                ) togetherWith (
                fadeOut(tween(120)) +
                    scaleOut(tween(160), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0f))
                )
        },
        label = "xiaoman-island",
    ) { current ->
        when (current) {
            is IslandState.Approval -> ApprovalIsland(current.pending, session)
            is IslandState.Question -> QuestionIsland(current.pending, onAnswerQuestion)
            null -> Unit
        }
    }
}

private sealed interface IslandState {
    data class Approval(val pending: PendingApproval) : IslandState
    data class Question(val pending: PendingQuestion) : IslandState
}

/** Shared chrome: avatar + "小满" + a title, sized like an expanded capsule. */
@Composable
private fun IslandShell(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).animateContentSize(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.avatar_xiaoman),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ApprovalIsland(pending: PendingApproval, session: AgentSession) {
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
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(
            pending.request.prompt,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState()),
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
private fun QuestionIsland(pending: PendingQuestion, onAnswer: () -> Unit) {
    IslandShell(title = stringResource(R.string.question_island_title)) {
        Text(
            pending.question,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onAnswer, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.question_island_answer), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
