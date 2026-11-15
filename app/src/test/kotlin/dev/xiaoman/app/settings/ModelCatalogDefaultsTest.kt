package dev.xiaoman.app.settings

import dev.xiaoman.llm.RemoteModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogDefaultsTest {
    @Test fun `vision and output defaults follow the model family`() {
        val gpt = ModelDefaults.profile("gpt-4o")
        assertTrue(gpt.supportsVision)
        assertEquals(16_384, gpt.maxTokens)
        assertEquals(128_000, gpt.contextWindow)

        val deepseek = ModelDefaults.profile("deepseek-chat")
        assertFalse(deepseek.supportsVision)
        assertEquals(8_192, deepseek.maxTokens)
    }

    @Test fun `embedding and rerank models cannot be selected for chat`() {
        assertFalse(ModelDefaults.profile("text-embedding-3-large").chatCapable)
        assertFalse(ModelDefaults.profile("bge-reranker-v2-m3").chatCapable)
        assertTrue(ModelDefaults.profile("qwen-vl-max").supportsVision)
    }

    @Test fun `reasoning models leave temperature and output unset`() {
        val o3 = ModelDefaults.profile("o3-mini")
        assertNull(o3.temperature)
        assertNull(o3.maxTokens)
    }

    @Test fun `provider metadata overrides family estimates`() {
        val profile = ModelDefaults.profile(
            RemoteModel("custom-model", contextLength = 1_000_000, maxOutputTokens = 64_000, supportsVision = true),
        )
        assertEquals(1_000_000, profile.contextWindow)
        assertEquals(64_000, profile.maxTokens)
        assertTrue(profile.supportsVision)
    }

    @Test fun `provider detection matches known base urls and falls back to custom`() {
        assertEquals("openai", Providers.detect("https://api.openai.com/v1").id)
        assertEquals("deepseek", Providers.detect("https://api.deepseek.com/v1").id)
        assertEquals("custom", Providers.detect("http://127.0.0.1:11434/v1").id)
        assertEquals("custom", Providers.detect("").id)
    }
}
