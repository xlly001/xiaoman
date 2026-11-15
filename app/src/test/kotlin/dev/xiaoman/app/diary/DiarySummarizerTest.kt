package dev.xiaoman.app.diary

import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import dev.xiaoman.llm.LlmClientFactory
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiarySummarizerTest {
    private val config = ModelConfig(ModelRole.CHAT, "https://example.test/v1", "test-model", "secret")

    private class FakeClient(
        override val config: ModelConfig,
        private val reply: String,
        private val failure: Throwable? = null,
    ) : LlmClient {
        val requests = mutableListOf<ChatRequest>()

        override suspend fun complete(request: ChatRequest): ChatResponse {
            requests += request
            failure?.let { throw it }
            return ChatResponse(reply)
        }
    }

    private val entries = listOf(
        DiaryEntry(1, 1_700_000_000_000, "task_done", "订了一份外卖", "宫保鸡丁，已下单"),
        DiaryEntry(2, 1_700_000_300_000, "memory", "记住不爱吃香菜"),
    )

    @Test fun `summary is produced by a real model API call carrying every entry`() = runTest {
        val client = FakeClient(config, "```\n# 今日摘要\n今天帮你订了外卖，也记住了你不爱吃香菜。\n```")
        var usedConfig: ModelConfig? = null
        val summarizer = DiarySummarizer(
            LlmClientFactory { usedConfig = it; client },
            { config },
            ZoneId.of("Asia/Shanghai"),
        )

        val result = summarizer.summarize("2026-11-15", entries)

        assertEquals("今天帮你订了外卖，也记住了你不爱吃香菜。", result.getOrThrow())
        assertEquals(config, usedConfig)
        assertEquals(1, client.requests.size)
        val messages = client.requests.single().messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.SYSTEM, messages[0].role)
        val user = messages[1].text
        assertTrue(user.contains("2026-11-15"))
        assertTrue(user.contains("订了一份外卖"))
        assertTrue(user.contains("宫保鸡丁，已下单"))
        assertTrue(user.contains("记住不爱吃香菜"))
        assertTrue(user.contains("完成任务") && user.contains("记忆"))
    }

    @Test fun `no configured model fails without calling the API`() = runTest {
        var called = false
        val summarizer = DiarySummarizer(
            LlmClientFactory { called = true; FakeClient(config, "unused") },
            { null },
            ZoneId.of("Asia/Shanghai"),
        )
        val error = summarizer.summarize("2026-11-15", entries).exceptionOrNull()
        assertTrue(error is ModelNotConfiguredException)
        assertEquals(false, called)
    }

    @Test fun `model errors and empty replies are surfaced to the UI`() = runTest {
        val failing = DiarySummarizer(
            LlmClientFactory { FakeClient(config, "", LlmException("HTTP 401 from example.test", 401)) },
            { config },
            ZoneId.of("Asia/Shanghai"),
        )
        val error = failing.summarize("2026-11-15", entries).exceptionOrNull()
        assertTrue(error is LlmException)
        assertTrue(error.message!!.contains("401"))

        val blank = DiarySummarizer(
            LlmClientFactory { FakeClient(config, "  \n # 摘要 \n ``` ") },
            { config },
            ZoneId.of("Asia/Shanghai"),
        )
        assertTrue(blank.summarize("2026-11-15", entries).isFailure)
    }

    @Test fun `an empty day is rejected before any network call`() = runTest {
        var called = false
        val summarizer = DiarySummarizer(
            LlmClientFactory { called = true; FakeClient(config, "unused") },
            { config },
            ZoneId.of("Asia/Shanghai"),
        )
        assertFailsWith<IllegalArgumentException> {
            summarizer.summarize("2026-11-15", emptyList()).getOrThrow()
        }
        assertEquals(false, called)
    }
}
