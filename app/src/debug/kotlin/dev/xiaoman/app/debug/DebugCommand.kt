package dev.xiaoman.app.debug

import dev.xiaoman.app.RunIds

/** A parsed debug-bridge broadcast (docs/ARCHITECTURE.md, Debug bridge). */
sealed class DebugCommand {
    data class Run(val goal: String, val skill: String?, val runId: String) : DebugCommand()

    /** A RUN that cannot run; the receiver still ends the run with Failed so `xiaoman run --follow` returns. */
    data class InvalidRun(val runId: String, val message: String) : DebugCommand()

    data object Stop : DebugCommand()

    data object ReloadSkills : DebugCommand()

    /** Types [text] into the chat as if the user sent it (recording demos without the soft keyboard). */
    data class Say(val text: String) : DebugCommand()

    /** Enqueues a phone task so E2E can observe the single execution slot without using the UI. */
    data class Enqueue(val goal: String, val skill: String?) : DebugCommand()

    /** Pause / resume / cancel a not-yet-started queue row. */
    data class QueueControl(val op: QueueOp, val id: String) : DebugCommand()

    data object QueueStatus : DebugCommand()

    /** Records a short clip with the built-in ASR engine; [autoStop] ends capture after [millis]. */
    data class MediaAsr(val autoStop: Boolean, val millis: Long) : DebugCommand()

    /** Speaks [text] through the built-in TTS engine. */
    data class MediaTts(val text: String, val voice: String) : DebugCommand()

    /** Runs the built-in OCR engine against a fixed 1x1 PNG. */
    data class MediaOcr(val purpose: String) : DebugCommand()

    /** Reports the resolved media routing decision. */
    data object MediaStatus : DebugCommand()

    enum class QueueOp { PAUSE, RESUME, CANCEL }

    companion object {
        const val ACTION_RUN = "dev.xiaoman.debug.RUN"
        const val ACTION_STOP = "dev.xiaoman.debug.STOP"
        const val ACTION_RELOAD_SKILLS = "dev.xiaoman.debug.RELOAD_SKILLS"
        const val ACTION_SAY = "dev.xiaoman.debug.SAY"
        const val ACTION_ENQUEUE = "dev.xiaoman.debug.ENQUEUE"
        const val ACTION_QUEUE_PAUSE = "dev.xiaoman.debug.QUEUE_PAUSE"
        const val ACTION_QUEUE_RESUME = "dev.xiaoman.debug.QUEUE_RESUME"
        const val ACTION_QUEUE_CANCEL = "dev.xiaoman.debug.QUEUE_CANCEL"
        const val ACTION_QUEUE_STATUS = "dev.xiaoman.debug.QUEUE_STATUS"
        const val ACTION_MEDIA_ASR = "dev.xiaoman.debug.MEDIA_ASR"
        const val ACTION_MEDIA_TTS = "dev.xiaoman.debug.MEDIA_TTS"
        const val ACTION_MEDIA_OCR = "dev.xiaoman.debug.MEDIA_OCR"
        const val ACTION_MEDIA_STATUS = "dev.xiaoman.debug.MEDIA_STATUS"
        const val EXTRA_TEXT = "text"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_SKILL = "skill"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_ID = "id"
        const val EXTRA_AUTO_STOP = "auto_stop"
        const val EXTRA_MILLIS = "millis"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PURPOSE = "purpose"

        /**
         * @param extra string extra lookup (`Intent::getStringExtra`)
         * @param newRunId used when `run_id` is missing; an invalid `run_id` is rejected.
         * @return null for an unknown action.
         */
        fun parse(action: String?, extra: (String) -> String?, newRunId: () -> String): DebugCommand? = when (action) {
            ACTION_RUN -> parseRun(extra, newRunId)
            ACTION_STOP -> Stop
            ACTION_RELOAD_SKILLS -> ReloadSkills
            ACTION_SAY -> extra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let(::Say)
            ACTION_ENQUEUE -> extra(EXTRA_GOAL)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Enqueue(it, extra(EXTRA_SKILL)?.trim()?.takeIf { s -> s.isNotEmpty() }) }
            ACTION_QUEUE_PAUSE -> queueControl(QueueOp.PAUSE, extra)
            ACTION_QUEUE_RESUME -> queueControl(QueueOp.RESUME, extra)
            ACTION_QUEUE_CANCEL -> queueControl(QueueOp.CANCEL, extra)
            ACTION_QUEUE_STATUS -> QueueStatus
            ACTION_MEDIA_ASR -> MediaAsr(
                autoStop = extra(EXTRA_AUTO_STOP)?.trim()?.lowercase() != "false",
                millis = extra(EXTRA_MILLIS)?.trim()?.toLongOrNull()?.coerceIn(200L, 120_000L) ?: 1_200L,
            )
            ACTION_MEDIA_TTS -> extra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { MediaTts(it, extra(EXTRA_VOICE)?.trim().orEmpty()) }
            ACTION_MEDIA_OCR -> MediaOcr(extra(EXTRA_PURPOSE)?.trim().orEmpty())
            ACTION_MEDIA_STATUS -> MediaStatus
            else -> null
        }

        private fun queueControl(op: QueueOp, extra: (String) -> String?): DebugCommand? =
            extra(EXTRA_ID)?.trim()?.takeIf { it.isNotEmpty() }?.let { QueueControl(op, it) }

        private fun parseRun(extra: (String) -> String?, newRunId: () -> String): DebugCommand {
            val rawId = extra(EXTRA_RUN_ID)?.trim()?.takeIf { it.isNotEmpty() }
            if (rawId != null && !RunIds.isValid(rawId)) {
                return InvalidRun(newRunId(), "invalid run_id '$rawId': use letters, digits, '.', '_' or '-'")
            }
            val runId = rawId ?: newRunId()
            val goal = extra(EXTRA_GOAL)?.trim()?.takeIf { it.isNotEmpty() } ?: return InvalidRun(runId, "missing extra 'goal'")
            val skill = extra(EXTRA_SKILL)?.trim()?.takeIf { it.isNotEmpty() }
            return Run(goal, skill, runId)
        }
    }
}
