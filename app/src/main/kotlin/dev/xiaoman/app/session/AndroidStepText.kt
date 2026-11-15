package dev.xiaoman.app.session

import android.content.Context
import androidx.annotation.StringRes
import dev.xiaoman.api.Action
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.app.R

/** Android implementation of [StepTextFormatter]; every agent-facing sentence goes through resources. */
class AndroidStepText(private val context: Context) : StepTextFormatter {
    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun where(target: String?, nodeId: Int?): String = when {
        !target.isNullOrBlank() -> " $target"
        nodeId != null -> " #$nodeId"
        else -> ""
    }

    override fun action(action: Action): String = when (action) {
        is Action.Tap -> text(R.string.step_tap) + where(action.target, action.nodeId)
        is Action.LongPress -> text(R.string.step_long_press) + where(action.target, action.nodeId)
        is Action.Swipe -> text(R.string.step_swipe) + where(action.target, null)
        is Action.TypeText -> text(R.string.step_type, action.text) + where(action.target, action.nodeId)
        is Action.Back -> text(R.string.step_back)
        is Action.Home -> text(R.string.step_home)
        is Action.LaunchApp -> text(R.string.step_launch, action.packageName)
        is Action.OpenDeeplink -> text(R.string.step_open_link, action.uri.substringBefore('?'))
        is Action.Wait -> text(R.string.step_wait, action.millis)
        is Action.AskUser -> text(R.string.step_ask, action.question)
        is Action.Finish -> text(R.string.step_finish, action.summary)
    }

    override fun status(event: AgentEvent): String? = when (event) {
        is AgentEvent.PhoneTaskStarted -> text(R.string.status_start, event.goal)
        is AgentEvent.StepPlanned -> text(R.string.status_step, event.stepIndex + 1, action(event.action))
        is AgentEvent.StepBlocked -> text(R.string.status_blocked, event.stepIndex + 1)
        is AgentEvent.NeedsUser -> if (event.takeover) {
            text(R.string.status_takeover)
        } else {
            text(R.string.status_question, event.question)
        }
        is AgentEvent.PhoneTaskFinished -> text(if (event.success) R.string.status_done else R.string.status_not_done)
        else -> null
    }

    override fun chatItem(event: AgentEvent, id: Long): ChatItem? = when (event) {
        is AgentEvent.Reply -> ChatItem.Assistant(id, event.text)
        is AgentEvent.SkillChosen -> ChatItem.Progress(id, text(R.string.chat_skill_chosen, event.skillName))
        is AgentEvent.PhoneTaskStarted -> ChatItem.Progress(id, text(R.string.chat_phone_start, event.goal))
        is AgentEvent.StepPlanned -> null
        is AgentEvent.StepExecuted -> {
            val failure = event.result.message?.takeIf { !event.result.ok }?.let { text(R.string.chat_step_failure, it) }.orEmpty()
            ChatItem.Progress(id, text(R.string.chat_step, event.stepIndex + 1, action(event.action), failure), event.result.ok)
        }
        is AgentEvent.StepBlocked -> ChatItem.Progress(
            id,
            text(R.string.chat_step_blocked, event.stepIndex + 1, action(event.action), event.decision.reason),
            false,
        )
        is AgentEvent.NeedsUser -> ChatItem.Question(id, event.question, event.takeover)
        is AgentEvent.MemoryUpdated -> ChatItem.Progress(id, text(R.string.chat_memory_updated, event.permalink))
        is AgentEvent.ToolCalled -> ChatItem.Progress(id, event.summary, event.ok)
        is AgentEvent.PhoneTaskFinished -> ChatItem.Progress(
            id,
            text(
                R.string.chat_task_result,
                text(if (event.success) R.string.chat_task_done else R.string.chat_task_not_done),
                event.steps,
                event.summary,
            ),
            event.success,
        )
        is AgentEvent.Failed -> ChatItem.Error(id, event.message)
    }
}
