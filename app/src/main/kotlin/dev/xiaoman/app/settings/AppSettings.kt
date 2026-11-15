package dev.xiaoman.app.settings

import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.MediaEngineMode
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.OcrPurpose
import dev.xiaoman.api.TtsVoice

/** One OpenAI-compatible endpoint as typed into Settings. [toString] never shows [apiKey]. */
data class ModelSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val displayName: String = "",
    val timeoutSeconds: Long = 120,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun toModelConfig(role: ModelRole) = ModelConfig(
        role = role,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
        timeoutSeconds = timeoutSeconds,
    )

    override fun toString() =
        "ModelSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

    companion object {
        /** Volcano Ark. */
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

data class AppSettings(
    val chat: ModelSettings = ModelSettings(),
    val operator: ModelSettings = ModelSettings(),
    val maxSteps: Int = AgentConfig().maxSteps,
    val learnFromSuccess: Boolean = AgentConfig().learnFromSuccess,
    /** Last provider selected in Settings, used to render the provider picker. */
    val providerId: String = Providers.ark.id,
    /** All models fetched from the provider; kept locally so the list survives a restart. */
    val models: List<ModelProfile> = emptyList(),
    /** system / light / dark. */
    val themeMode: String = THEME_SYSTEM,
    /** system / zh / en; applied by [dev.xiaoman.app.AppLocale]. */
    val language: String = LANGUAGE_SYSTEM,
    /** Send a voice transcript immediately instead of only filling the composer. */
    val voiceAutoSend: Boolean = false,
    val requestTimeoutSeconds: Int = 120,
    val notificationsEnabled: Boolean = true,
    val chatHistoryLimit: Int = 500,
    /** Which implementation serves the built-in ASR / TTS / OCR capabilities by default. */
    val mediaEngineMode: MediaEngineMode = MediaEngineMode.AUTO,
    /** Per-capability overrides; null follows [mediaEngineMode]. */
    val asrEngineMode: MediaEngineMode? = null,
    val ttsEngineMode: MediaEngineMode? = null,
    val ocrEngineMode: MediaEngineMode? = null,
    /** Optional override for the DeepSeek tools base URL; blank derives it from [chat].baseUrl. */
    val mediaToolsBaseUrl: String = "",
    /** Voice used by DeepSeek TTS; the system engine ignores it. */
    val ttsVoice: TtsVoice = TtsVoice.STELLA,
    /** Speak the final assistant reply when it arrives. */
    val readRepliesAloud: Boolean = false,
    /** `purpose` sent to /v1/tools/ocr. */
    val ocrPurpose: OcrPurpose = OcrPurpose.TEXT,
) {
    val configured: Boolean get() = chat.configured && operator.configured

    /** Values to mask in traces and logs. */
    val secrets: List<String> get() = listOf(chat.apiKey, operator.apiKey).filter { it.isNotBlank() }

    fun agentConfig() = AgentConfig(maxSteps = maxSteps, learnFromSuccess = learnFromSuccess)

    companion object {
        val MAX_STEPS_RANGE = 1..200
        val REQUEST_TIMEOUT_RANGE = 10..300
        val HISTORY_LIMIT_RANGE = 50..2_000
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
    }
}

/**
 * The editable text of the Settings screen. Parsing lives here, not in the Composable.
 */
data class SettingsForm(
    val chat: ModelForm = ModelForm(),
    val operator: ModelForm = ModelForm(),
    val maxSteps: String = AgentConfig().maxSteps.toString(),
    val learnFromSuccess: Boolean = true,
    val providerId: String = Providers.ark.id,
    val models: List<ModelProfile> = emptyList(),
    val themeMode: String = AppSettings.THEME_SYSTEM,
    val language: String = AppSettings.LANGUAGE_SYSTEM,
    val voiceAutoSend: Boolean = false,
    val requestTimeout: String = "120",
    val notificationsEnabled: Boolean = true,
    val chatHistoryLimit: String = "500",
    val mediaEngineMode: String = MediaEngineMode.AUTO.name,
    val asrEngineMode: String = "",
    val ttsEngineMode: String = "",
    val ocrEngineMode: String = "",
    val mediaToolsBaseUrl: String = "",
    val ttsVoice: String = TtsVoice.STELLA.name,
    val readRepliesAloud: Boolean = false,
    val ocrPurpose: String = OcrPurpose.TEXT.name,
) {
    data class ModelForm(
        val baseUrl: String = ModelSettings.DEFAULT_BASE_URL,
        val model: String = "",
        val apiKey: String = "",
        val temperature: String = "",
        val maxTokens: String = "",
        val contextWindow: String = "",
        val supportsVision: Boolean = false,
        val displayName: String = "",
    ) {
        val temperatureValid: Boolean get() = parseTemperature(temperature) != null || temperature.isBlank()
        val maxTokensValid: Boolean get() = maxTokens.isBlank() || maxTokens.trim().toIntOrNull()?.let { it in 1..1_000_000 } == true
        val contextWindowValid: Boolean get() =
            contextWindow.isBlank() || contextWindow.trim().toIntOrNull()?.let { it in 1_000..10_000_000 } == true

        fun toSettings(): ModelSettings? {
            val t = if (temperature.isBlank()) null else parseTemperature(temperature) ?: return null
            val output = if (maxTokens.isBlank()) null else maxTokens.trim().toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return null
            val context = if (contextWindow.isBlank()) null else contextWindow.trim().toIntOrNull()?.takeIf { it in 1_000..10_000_000 } ?: return null
            return ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), t, output, context, supportsVision, displayName.trim())
        }

        override fun toString() =
            "ModelForm(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

        companion object {
            fun of(s: ModelSettings) = ModelForm(
                baseUrl = s.baseUrl,
                model = s.model,
                apiKey = s.apiKey,
                temperature = s.temperature?.toString().orEmpty(),
                maxTokens = s.maxTokens?.toString().orEmpty(),
                contextWindow = s.contextWindow?.toString().orEmpty(),
                supportsVision = s.supportsVision,
                displayName = s.displayName,
            )
        }
    }

    val maxStepsValid: Boolean get() = maxSteps.trim().toIntOrNull()?.let { it in AppSettings.MAX_STEPS_RANGE } == true
    val requestTimeoutValid: Boolean get() =
        requestTimeout.trim().toIntOrNull()?.let { it in AppSettings.REQUEST_TIMEOUT_RANGE } == true
    val historyLimitValid: Boolean get() =
        chatHistoryLimit.trim().toIntOrNull()?.let { it in AppSettings.HISTORY_LIMIT_RANGE } == true

    /** Null while a field is invalid. */
    fun toSettings(): AppSettings? {
        val steps = maxSteps.trim().toIntOrNull()?.takeIf { it in AppSettings.MAX_STEPS_RANGE } ?: return null
        val timeout = requestTimeout.trim().toIntOrNull()?.takeIf { it in AppSettings.REQUEST_TIMEOUT_RANGE } ?: return null
        val historyLimit = chatHistoryLimit.trim().toIntOrNull()?.takeIf { it in AppSettings.HISTORY_LIMIT_RANGE } ?: return null
        return AppSettings(
            chat = (chat.toSettings() ?: return null).copy(timeoutSeconds = timeout.toLong()),
            operator = (operator.toSettings() ?: return null).copy(timeoutSeconds = timeout.toLong()),
            maxSteps = steps,
            learnFromSuccess = learnFromSuccess,
            providerId = providerId,
            models = models,
            themeMode = themeMode,
            language = language,
            voiceAutoSend = voiceAutoSend,
            requestTimeoutSeconds = timeout,
            notificationsEnabled = notificationsEnabled,
            chatHistoryLimit = historyLimit,
            mediaEngineMode = enumValue(mediaEngineMode, MediaEngineMode.AUTO),
            asrEngineMode = enumValueOrNull(asrEngineMode),
            ttsEngineMode = enumValueOrNull(ttsEngineMode),
            ocrEngineMode = enumValueOrNull(ocrEngineMode),
            mediaToolsBaseUrl = mediaToolsBaseUrl.trim(),
            ttsVoice = enumValue(ttsVoice, TtsVoice.STELLA),
            readRepliesAloud = readRepliesAloud,
            ocrPurpose = enumValue(ocrPurpose, OcrPurpose.TEXT),
        )
    }

    companion object {
        fun of(s: AppSettings) = SettingsForm(
            chat = ModelForm.of(s.chat),
            operator = ModelForm.of(s.operator),
            maxSteps = s.maxSteps.toString(),
            learnFromSuccess = s.learnFromSuccess,
            providerId = s.providerId,
            models = s.models,
            themeMode = s.themeMode,
            language = s.language,
            voiceAutoSend = s.voiceAutoSend,
            requestTimeout = s.requestTimeoutSeconds.toString(),
            notificationsEnabled = s.notificationsEnabled,
            chatHistoryLimit = s.chatHistoryLimit.toString(),
            mediaEngineMode = s.mediaEngineMode.name,
            asrEngineMode = s.asrEngineMode?.name.orEmpty(),
            ttsEngineMode = s.ttsEngineMode?.name.orEmpty(),
            ocrEngineMode = s.ocrEngineMode?.name.orEmpty(),
            mediaToolsBaseUrl = s.mediaToolsBaseUrl,
            ttsVoice = s.ttsVoice.name,
            readRepliesAloud = s.readRepliesAloud,
            ocrPurpose = s.ocrPurpose.name,
        )

        fun parseTemperature(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it in 0.0..2.0 }

        private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
            runCatching { enumValueOf<T>(raw.trim()) }.getOrDefault(fallback)

        /** Empty means "follow the default media engine mode". */
        private fun enumValueOrNull(raw: String): MediaEngineMode? =
            raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { MediaEngineMode.valueOf(it) }.getOrNull() }
    }
}
