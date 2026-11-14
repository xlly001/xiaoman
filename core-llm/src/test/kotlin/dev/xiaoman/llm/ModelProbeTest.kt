package dev.xiaoman.llm

import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelProbeTest {
    private val config = ModelConfig(ModelRole.CHAT, "https://example.com/v1", "demo", "secret")

    private class FakeClient(
        override val config: ModelConfig,
        private val response: ChatResponse? = null,
        private val error: Throwable? = null,
        private val capture: (ChatRequest) -> Unit = {},
    ) : LlmClient {
        override suspend fun complete(request: ChatRequest): ChatResponse {
            capture(request)
            error?.let { throw it }
            return response ?: ChatResponse("OK")
        }
    }

    @Test fun `chat probe sends text only`() = runTest {
        var request: ChatRequest? = null
        val result = ModelProbe.probe(FakeClient(config) { request = it }, ModelRole.CHAT)
        assertIs<ProbeResult.Ok>(result)
        assertTrue(request!!.messages.single().parts.all { it is ContentPart.Text })
    }

    @Test fun `operator probe includes an image so vision support is verified`() = runTest {
        var request: ChatRequest? = null
        val result = ModelProbe.probe(FakeClient(config) { request = it }, ModelRole.OPERATOR)
        assertIs<ProbeResult.Ok>(result)
        val parts = request!!.messages.single().parts
        assertEquals(1, parts.count { it is ContentPart.Text })
        assertEquals(1, parts.count { it is ContentPart.ImagePng })
    }

    @Test fun `http failures keep the status and provider code`() = runTest {
        val result = ModelProbe.probe(
            FakeClient(config, error = LlmException("HTTP 401", 401, providerCode = "AuthFailed")),
            ModelRole.CHAT,
        )
        val failed = assertIs<ProbeResult.Failed>(result)
        assertEquals(401, failed.statusCode)
        assertEquals("AuthFailed", failed.providerCode)
    }

    @Test fun `unexpected exceptions become a failed probe`() = runTest {
        val result = ModelProbe.probe(FakeClient(config, error = IllegalStateException("boom")), ModelRole.CHAT)
        val failed = assertIs<ProbeResult.Failed>(result)
        assertEquals("boom", failed.message)
    }
}
