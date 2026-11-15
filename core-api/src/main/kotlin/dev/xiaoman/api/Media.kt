package dev.xiaoman.api

/**
 * Contracts for the three built-in media capabilities: speech recognition, speech synthesis and
 * image text recognition.
 *
 * Unlike [LlmClient], an engine owns the whole interaction (microphone capture, audio playback,
 * image handoff), not just the HTTP call. That keeps this module free of Android dependencies:
 * `core-llm` implements the wire protocol, `app` wires it to the platform and the UI.
 */

/** A built-in media capability exposed by the app. */
enum class MediaCapability { ASR, TTS, OCR }

/** Which implementation actually serves a capability; shown in Settings and written to logs. */
enum class MediaEngineKind {
    /** Official DeepSeek `/v1/tools/{asr,tts,ocr}` endpoints. */
    DEEPSEEK,

    /** Android platform engines (SpeechRecognizer / TextToSpeech). */
    SYSTEM,

    /** Fallback: ask the configured chat model to read the image (needs a vision-capable model). */
    VISION_MODEL,
}

/** User preference for capability routing. [AUTO] follows the active model provider. */
enum class MediaEngineMode { AUTO, DEEPSEEK, SYSTEM }

class MediaException(
    message: String,
    val statusCode: Int? = null,
    val capability: MediaCapability? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One utterance transcribed to text. */
data class AsrResult(
    val text: String,
    val durationMs: Long? = null,
    val requestId: String? = null,
)

/** Evidence that a reply was spoken; [audioBytes] is 0 when the engine plays internally. */
data class TtsResult(
    val audioBytes: Int,
    val mimeType: String? = null,
    val voice: String = "",
)

/** One page of OCR output. DeepSeek returns pages in order, indexed from 0. */
data class OcrPage(val index: Int, val text: String, val finishReason: String? = null)

data class OcrResult(
    /** All pages joined with a blank line, ready to paste into the composer. */
    val text: String,
    val pages: List<OcrPage> = emptyList(),
    val requestId: String? = null,
)

/** `purpose` values accepted by `POST /v1/tools/ocr`. */
enum class OcrPurpose(val wire: String) {
    TEXT("text_recognition"),
    RICH_LAYOUT("rich_layout_recognition"),
}

/** `voice_id` values accepted by `POST /v1/tools/tts`. */
enum class TtsVoice(val wire: String) {
    STELLA("stella"),
    TIDE("tide"),
}

interface MediaEngine {
    val capability: MediaCapability
    val kind: MediaEngineKind
    /** Human-readable endpoint/engine description, never containing an API key. */
    val detail: String
}

interface AsrEngine : MediaEngine {
    /**
     * Capture one utterance and return its transcript. Implementations own microphone capture and
     * must be cancellable through [cancel].
     */
    suspend fun listen(): AsrResult

    /**
     * Finish an in-flight [listen] early. Audio captured so far is still transcribed: tapping the
     * microphone twice means "start recording" then "stop and recognize".
     */
    fun cancel()
}

interface TtsEngine : MediaEngine {
    /** Speak [text] to completion, or until [stop] is called. */
    suspend fun speak(text: String, voice: TtsVoice): TtsResult

    /** Interrupt current playback; a no-op when nothing is playing. */
    fun stop()
}

interface OcrEngine : MediaEngine {
    /**
     * Extract text from an encoded image (PNG/JPEG/WebP...). [fileName] is required by the
     * multipart API and should carry a real extension.
     */
    suspend fun recognize(image: ByteArray, fileName: String, purpose: OcrPurpose): OcrResult
}
