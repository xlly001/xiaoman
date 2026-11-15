package dev.xiaoman.app.media

import dev.xiaoman.api.AsrEngine
import dev.xiaoman.api.AsrResult
import dev.xiaoman.api.MediaCapability
import dev.xiaoman.api.MediaEngineKind
import dev.xiaoman.api.OcrEngine
import dev.xiaoman.api.OcrPurpose
import dev.xiaoman.api.OcrResult
import dev.xiaoman.api.TtsEngine
import dev.xiaoman.api.TtsResult
import dev.xiaoman.api.TtsVoice
import dev.xiaoman.llm.DeepSeekToolsClient

/**
 * The three built-in capabilities backed by DeepSeek's official standalone tools API. These
 * classes only glue the wire client to Android audio I/O; the HTTP contract lives in core-llm.
 */
class DeepSeekAsrEngine(
    private val tools: DeepSeekToolsClient,
    private val recorder: WavRecorder,
) : AsrEngine {
    override val capability = MediaCapability.ASR
    override val kind = MediaEngineKind.DEEPSEEK
    override val detail = "DeepSeek /v1/tools/asr"

    override suspend fun listen(): AsrResult = tools.asr(recorder.record())

    override fun cancel() = recorder.finish()
}

class DeepSeekTtsEngine(
    private val tools: DeepSeekToolsClient,
    private val player: WavPlayer,
) : TtsEngine {
    override val capability = MediaCapability.TTS
    override val kind = MediaEngineKind.DEEPSEEK
    override val detail = "DeepSeek /v1/tools/tts"

    override suspend fun speak(text: String, voice: TtsVoice): TtsResult {
        val wav = tools.tts(text, voice)
        player.play(wav)
        return TtsResult(audioBytes = wav.size, mimeType = "audio/wav", voice = voice.wire)
    }

    override fun stop() = player.stop()
}

class DeepSeekOcrEngine(private val tools: DeepSeekToolsClient) : OcrEngine {
    override val capability = MediaCapability.OCR
    override val kind = MediaEngineKind.DEEPSEEK
    override val detail = "DeepSeek /v1/tools/ocr"

    override suspend fun recognize(image: ByteArray, fileName: String, purpose: OcrPurpose): OcrResult =
        tools.ocr(image, fileName, purpose)
}
