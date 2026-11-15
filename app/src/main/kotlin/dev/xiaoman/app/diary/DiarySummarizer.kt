package dev.xiaoman.app.diary

import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.llm.LlmClientFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The chat model is not set up yet; the UI should point the user at Settings. */
class ModelNotConfiguredException : IllegalStateException("Chat model is not configured")

/**
 * Writes one short recap per diary day by calling the configured OpenAI-compatible chat API.
 *
 * This is a real network call through [LlmClientFactory] (the same `/chat/completions`
 * endpoint the chat screen uses); it is deliberately free of Android types so a fake
 * factory can exercise it in a plain JVM unit test.
 */
class DiarySummarizer(
    private val llm: LlmClientFactory,
    /** Null when the user has not configured a chat model. */
    private val chatConfig: () -> ModelConfig?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun summarize(date: String, entries: List<DiaryEntry>): Result<String> {
        if (entries.isEmpty()) return Result.failure(IllegalArgumentException("No diary entries for $date"))
        val config = chatConfig() ?: return Result.failure(ModelNotConfiguredException())
        return runCatching {
            val response = llm.create(config).complete(
                ChatRequest(
                    messages = listOf(
                        ChatMessage.system(SYSTEM_PROMPT),
                        ChatMessage.user(userPrompt(date, entries)),
                    ),
                ),
            )
            clean(response.text).takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Model returned an empty summary")
        }
    }

    /** Kept visible for tests: the request must carry every entry of the day. */
    internal fun userPrompt(date: String, entries: List<DiaryEntry>): String = buildString {
        append("日期：").append(date).append('\n')
        append("共 ").append(entries.size).append(" 条记录：\n")
        entries.sortedBy { it.at }.forEachIndexed { index, entry ->
            val time = Instant.ofEpochMilli(entry.at).atZone(zone).format(TIME)
            append(index + 1).append(". [").append(kindText(entry.kind)).append("] ")
            append(time).append(' ').append(entry.title.flatten().take(200))
            if (entry.detail.isNotBlank()) append(" — ").append(entry.detail.flatten().take(300))
            append('\n')
        }
    }

    private fun String.flatten() = replace('\n', ' ').replace('\r', ' ').trim()

    private fun kindText(kind: String) = when (kind) {
        "task_done" -> "完成任务"
        "task_failed" -> "任务未完成"
        "memory" -> "记忆"
        else -> "记录"
    }

    companion object {
        const val SYSTEM_PROMPT =
            "你是「小满」的日记助手。请把下面一天的活动记录整理成一段简洁的中文摘要，2 到 4 句话，" +
                "不超过 120 字，用第一人称「我」或「小满」叙述，点出完成了什么、记住了什么、有没有失败的任务。" +
                "只输出摘要正文，不要标题、不要列表符号、不要 Markdown、不要解释。"

        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        /** Strip the markdown/quotes some models add despite being told not to. */
        fun clean(raw: String): String = raw.trim()
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("#") || t == "```" || t.startsWith("```")
            }
            .joinToString("\n")
            .trim()
            .removeSurrounding("\"")
            .removePrefix("摘要：")
            .removePrefix("摘要:")
            .trim()
            .take(600)
    }
}
