package dev.xiaoman.app.media

import android.content.Context
import dev.xiaoman.api.AsrEngine
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.MediaEngineKind
import dev.xiaoman.api.MediaEngineMode
import dev.xiaoman.api.OcrEngine
import dev.xiaoman.api.TtsEngine
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.llm.DeepSeekToolsClient

/** The engines currently in use, plus the routing decision for Settings and diagnostics. */
data class MediaEngines(
    val asr: AsrEngine,
    val tts: TtsEngine,
    val ocr: OcrEngine,
    val mode: MediaEngineMode,
    /** Tools endpoint actually in use, or "-" when the platform/vision fallback is active. */
    val endpoint: String,
    val deepSeekProvider: Boolean,
) {
    fun describe(): String =
        "mode=$mode deepSeekProvider=$deepSeekProvider endpoint=$endpoint " +
            "asr=${asr.kind} tts=${tts.kind} ocr=${ocr.kind}"

    fun kinds(): Map<String, MediaEngineKind> = mapOf(
        "asr" to asr.kind,
        "tts" to tts.kind,
        "ocr" to ocr.kind,
    )
}

/**
 * Chooses between DeepSeek's official tools API and the Android platform engines.
 *
 * Default ([MediaEngineMode.AUTO]): when the active provider is DeepSeek (provider id, base URL
 * host, or a `deepseek-*` chat model), all three capabilities use `POST /v1/tools/{asr,tts,ocr}`
 * with the same API key. Otherwise ASR/TTS fall back to the platform engines and OCR to the
 * configured chat model's vision channel. Users can force either side in Settings.
 */
class MediaEngineRouter(
    private val context: Context,
    private val settings: () -> AppSettings,
    private val chat: () -> LlmClient?,
) {
    @Volatile private var cacheKey = 0

    @Volatile private var cached: MediaEngines? = null

    @Synchronized
    fun current(): MediaEngines {
        val s = settings()
        val key = listOf(
            s.mediaEngineMode.name,
            s.providerId,
            s.chat.baseUrl,
            s.chat.apiKey,
            s.chat.model,
            s.mediaToolsBaseUrl,
            s.requestTimeoutSeconds.toString(),
        ).hashCode()
        cached?.let { if (cacheKey == key) return it }
        val built = build(s)
        cacheKey = key
        cached = built
        return built
    }

    private fun build(s: AppSettings): MediaEngines {
        val deepSeekProvider = isDeepSeek(s)
        val useDeepSeek = when (s.mediaEngineMode) {
            MediaEngineMode.AUTO -> deepSeekProvider
            MediaEngineMode.DEEPSEEK -> true
            MediaEngineMode.SYSTEM -> false
        }
        val base = s.mediaToolsBaseUrl.trim().ifBlank {
            if (deepSeekProvider) s.chat.baseUrl else OFFICIAL_TOOLS_BASE
        }
        val endpoint = runCatching { DeepSeekToolsClient.toolsRoot(base) }
            .getOrDefault("$OFFICIAL_TOOLS_BASE/tools")
        val tools = DeepSeekToolsClient.fromConfig(
            baseUrl = base,
            apiKey = s.chat.apiKey,
            timeoutSeconds = s.requestTimeoutSeconds.toLong().coerceAtLeast(10L),
        )
        val recorder = WavRecorder(context)
        val player = WavPlayer()

        val deepSeekAsr by lazy { DeepSeekAsrEngine(tools, recorder) }
        val deepSeekTts by lazy { DeepSeekTtsEngine(tools, player) }
        val deepSeekOcr by lazy { DeepSeekOcrEngine(tools) }
        val systemAsr by lazy { SystemAsrEngine(context) }
        val systemTts by lazy { SystemTtsEngine(context) }
        val visionOcr by lazy { VisionModelOcrEngine(chat) }

        return MediaEngines(
            asr = if (useDeepSeek) deepSeekAsr else systemAsr,
            tts = if (useDeepSeek) deepSeekTts else systemTts,
            ocr = if (useDeepSeek) deepSeekOcr else visionOcr,
            mode = s.mediaEngineMode,
            endpoint = if (useDeepSeek) endpoint else "-",
            deepSeekProvider = deepSeekProvider,
        )
    }

    /** Provider id, endpoint host or model name all count as "using a DeepSeek model". */
    fun isDeepSeek(s: AppSettings): Boolean =
        s.providerId == "deepseek" ||
            s.chat.baseUrl.contains("api.deepseek.com", ignoreCase = true) ||
            s.chat.model.trim().startsWith("deepseek", ignoreCase = true)

    companion object {
        const val OFFICIAL_TOOLS_BASE = "https://api.deepseek.com/v1"
    }
}
