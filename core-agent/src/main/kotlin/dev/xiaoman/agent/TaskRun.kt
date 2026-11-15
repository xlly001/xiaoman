package dev.xiaoman.agent

import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.Decision
import dev.xiaoman.api.Skill
import dev.xiaoman.api.StopSignal
import dev.xiaoman.api.TaskResume

/** State of one phone task, shared by its flow steps and every operator loop they run. */
internal class TaskRun(
    val taskId: String,
    val goal: String,
    val skill: Skill?,
    val listener: AgentListener,
    val stop: StopSignal,
    resume: TaskResume? = null,
) {
    /** Device actions executed so far; also the step index of the next one. */
    var steps = resume?.steps ?: 0
    /** Seed for the first operator loop; consumed once so nested loops do not repeat it. */
    private var pendingResume: TaskResume? = resume
    /** Labels of the successful actions, for the learning note. */
    val path = mutableListOf<String>()
    /** Skill memory refs, rendered for prompts. */
    var references: String = ""
    /** Earlier successful paths (howto notes), rendered for prompts. */
    var howto: String = ""
    /** Placeholder values already resolved in this task. */
    val values = mutableMapOf<String, String>()
    var launchedPackage: String? = null
    var lastPackage: String? = null

    fun emit(event: AgentEvent) = listener.onEvent(event)

    fun addPath(label: String?) {
        val l = label?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (path.lastOrNull() != l) path += l
    }

    fun takeResume(): TaskResume? = pendingResume.also { pendingResume = null }
}

internal data class LoopOutcome(val success: Boolean, val summary: String)

/** Restricts a loop to one action of [kind] (`tap` or `type`) aimed at [target]; [text] overrides the typed text. */
internal data class StepConstraint(val kind: String, val target: String, val text: String? = null)

/** Outcome of [OperatorLoop.execute]. */
internal sealed class Executed {
    data class Done(val result: ActionResult) : Executed()
    data class Blocked(val decision: Decision) : Executed()
}
