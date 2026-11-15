package dev.xiaoman.app.settings

import androidx.annotation.StringRes
import dev.xiaoman.app.R
import kotlinx.serialization.Serializable

/** A model locally saved from a provider's `/models` response, with editable defaults. */
@Serializable
data class ModelProfile(
    val id: String,
    val displayName: String = "",
    val maxTokens: Int? = 8192,
    val temperature: Double? = 0.7,
    val contextWindow: Int? = 131_072,
    val supportsVision: Boolean = false,
    /** Embedding, rerank, TTS and moderation models are listed but cannot be selected for chat. */
    val chatCapable: Boolean = true,
) {
    val title: String get() = displayName.ifBlank { id }
}

/** Best-effort defaults; the user can expand each row and correct them before saving. */
object ModelDefaults {
    fun profile(id: String): ModelProfile {
        val lower = id.lowercase()
        val reasoning = lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")
        val chatCapable = listOf("embed", "rerank", "tts", "whisper", "dall-e", "moderation", "audio", "speech", "transcribe")
            .none { lower.contains(it) }
        val vision = listOf(
            "vision", "-vl", "vl-", "4v", "gpt-4o", "gpt-4.1", "o3", "o4", "claude", "gemini",
            "internvl", "llava", "pixtral", "qvq",
        ).any { lower.contains(it) }
        val maxTokens = when {
            !chatCapable || reasoning -> null
            lower.contains("gpt-4.1") -> 32_768
            lower.contains("gpt-4o") -> 16_384
            lower.contains("claude") -> 16_384
            lower.contains("gemini") -> 8_192
            else -> 8_192
        }
        val context = when {
            lower.contains("gpt-4.1") -> 1_047_576
            lower.contains("gpt-4o") -> 128_000
            lower.contains("o3") || lower.contains("o4") -> 200_000
            lower.contains("claude") -> 200_000
            lower.contains("gemini") -> 1_048_576
            lower.contains("deepseek") -> 128_000
            lower.contains("qwen") || lower.contains("kimi") || lower.contains("moonshot") -> 131_072
            lower.contains("glm") -> 131_072
            else -> 32_768
        }
        return ModelProfile(
            id = id,
            displayName = id,
            maxTokens = maxTokens,
            temperature = if (reasoning || !chatCapable) null else 0.7,
            contextWindow = context,
            supportsVision = vision,
            chatCapable = chatCapable,
        )
    }
}

/** One selectable OpenAI-compatible provider. The base URL stays editable for proxies. */
data class ProviderPreset(
    val id: String,
    @StringRes val nameRes: Int,
    val baseUrl: String,
    @StringRes val keyHintRes: Int,
)

object Providers {
    val ark = ProviderPreset(
        "ark",
        R.string.provider_ark,
        "https://ark.cn-beijing.volces.com/api/v3",
        R.string.provider_ark_hint,
    )
    val openai = ProviderPreset(
        "openai",
        R.string.provider_openai,
        "https://api.openai.com/v1",
        R.string.provider_openai_hint,
    )
    val deepseek = ProviderPreset(
        "deepseek",
        R.string.provider_deepseek,
        "https://api.deepseek.com/v1",
        R.string.provider_deepseek_hint,
    )
    val dashscope = ProviderPreset(
        "dashscope",
        R.string.provider_dashscope,
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        R.string.provider_dashscope_hint,
    )
    val zhipu = ProviderPreset(
        "zhipu",
        R.string.provider_zhipu,
        "https://open.bigmodel.cn/api/paas/v4",
        R.string.provider_zhipu_hint,
    )
    val moonshot = ProviderPreset(
        "moonshot",
        R.string.provider_moonshot,
        "https://api.moonshot.cn/v1",
        R.string.provider_moonshot_hint,
    )
    val siliconflow = ProviderPreset(
        "siliconflow",
        R.string.provider_siliconflow,
        "https://api.siliconflow.cn/v1",
        R.string.provider_siliconflow_hint,
    )
    val openrouter = ProviderPreset(
        "openrouter",
        R.string.provider_openrouter,
        "https://openrouter.ai/api/v1",
        R.string.provider_openrouter_hint,
    )
    val custom = ProviderPreset(
        "custom",
        R.string.provider_custom,
        "",
        R.string.provider_custom_hint,
    )

    val all = listOf(ark, openai, deepseek, dashscope, zhipu, moonshot, siliconflow, openrouter, custom)

    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id } ?: ark

    fun detect(baseUrl: String): ProviderPreset {
        val url = baseUrl.trim()
        return all.firstOrNull { it.id != custom.id && it.baseUrl.isNotBlank() && url.startsWith(it.baseUrl) } ?: custom
    }
}
