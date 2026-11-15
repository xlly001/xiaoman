package dev.xiaoman.app.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.xiaoman.api.AsrEngine
import dev.xiaoman.api.AsrResult
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.MediaCapability
import dev.xiaoman.api.MediaEngineKind
import dev.xiaoman.api.MediaException
import dev.xiaoman.api.OcrEngine
import dev.xiaoman.api.OcrPage
import dev.xiaoman.api.OcrPurpose
import dev.xiaoman.api.OcrResult
import dev.xiaoman.api.TtsEngine
import dev.xiaoman.api.TtsResult
import dev.xiaoman.api.TtsVoice
import dev.xiaoman.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline/platform fallback used when the active provider is not DeepSeek. ASR and TTS use the
 * Android platform engines; OCR has no offline engine, so it hands the image to the configured
 * chat model's vision channel (and says so in [detail] instead of pretending to be local OCR).
 */
class SystemAsrEngine(private val context: Context) : AsrEngine {
    override val capability = MediaCapability.ASR
    override val kind = MediaEngineKind.SYSTEM
    override val detail = "Android 系统语音识别"

    @Volatile private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(): AsrResult = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw MediaException("设备没有可用的系统语音识别服务", capability = MediaCapability.ASR)
        }
        suspendCancellableCoroutine { continuation ->
            val speech = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            MediaException("系统语音识别失败（错误码 $error）", capability = MediaCapability.ASR),
                        )
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!continuation.isActive) return
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isEmpty()) {
                        continuation.resumeWithException(
                            MediaException("没有识别到语音", capability = MediaCapability.ASR),
                        )
                    } else {
                        continuation.resume(AsrResult(text))
                    }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            runCatching { speech.startListening(intent) }.onFailure { cause ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        MediaException("无法启动系统语音识别：${cause.message}", capability = MediaCapability.ASR, cause = cause),
                    )
                }
            }
            continuation.invokeOnCancellation {
                runCatching { speech.cancel(); speech.destroy() }
                recognizer = null
            }
        }
    }

    override fun cancel() {
        recognizer?.stopListening()
    }
}

class SystemTtsEngine(context: Context) : TtsEngine {
    override val capability = MediaCapability.TTS
    override val kind = MediaEngineKind.SYSTEM
    override val detail = "Android 系统语音合成"

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()

    /**
     * TextToSpeech binds its callbacks to the Looper of the constructing thread. The media router
     * is built from a background coroutine, so create the engine on the main thread explicitly;
     * otherwise the init callback and utterance callbacks can never be delivered and playback
     * silently produces no sound.
     */
    @Volatile private var tts: TextToSpeech? = null

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        return withContext(Dispatchers.Main) {
            tts ?: TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
                .also { tts = it }
        }
    }

    override suspend fun speak(text: String, voice: TtsVoice): TtsResult {
        if (text.isBlank()) return TtsResult(0, null, "system")
        val engine = engine()
        if (withTimeoutOrNull(8_000) { ready.await() } != true) {
            throw MediaException(appContext.getString(R.string.media_tts_system_not_ready), capability = MediaCapability.TTS)
        }

        // Prefer a Chinese voice for Chinese text; many phones default to an English engine even
        // when the UI locale is Chinese, which reads Chinese characters as silence.
        val chinese = text.any { it.code in 0x4E00..0x9FFF }
        val language = if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
        val languageStatus = engine.setLanguage(language)
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw MediaException(appContext.getString(R.string.media_tts_system_missing), capability = MediaCapability.TTS)
        }

        val token = "xiaoman-${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == token) done.complete(Unit)
            }

            @Deprecated("Required by UtteranceProgressListener on older API levels")
            override fun onError(utteranceId: String?) {
                if (utteranceId == token) {
                    done.completeExceptionally(MediaException(appContext.getString(R.string.media_tts_system_failed), capability = MediaCapability.TTS))
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == token) {
                    val message = appContext.getString(R.string.media_tts_system_failed_code, errorCode)
                    done.completeExceptionally(MediaException(message, capability = MediaCapability.TTS))
                }
            }
        })
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, token) }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, token) == TextToSpeech.ERROR) {
            throw MediaException(appContext.getString(R.string.media_tts_system_rejected), capability = MediaCapability.TTS)
        }
        // Some engines never fire onDone; never block the caller forever.
        withTimeoutOrNull(120_000) { done.await() }
            ?: throw MediaException(appContext.getString(R.string.media_tts_system_timeout), capability = MediaCapability.TTS)
        return TtsResult(audioBytes = 0, mimeType = null, voice = "system")
    }

    override fun stop() {
        tts?.stop()
    }
}

class VisionModelOcrEngine(private val chat: () -> LlmClient?) : OcrEngine {
    override val capability = MediaCapability.OCR
    override val kind = MediaEngineKind.VISION_MODEL
    override val detail = "聊天模型视觉识别（回退）"

    override suspend fun recognize(image: ByteArray, fileName: String, purpose: OcrPurpose): OcrResult {
        val client = chat()
            ?: throw MediaException("未配置聊天模型，无法识别图片文字", capability = MediaCapability.OCR)
        val prompt = "请逐字输出图片中的全部文字，保持原有顺序与换行。不要解释、不要总结、不要添加任何额外内容；若图中没有文字，只回复空字符串。"
        val response = client.complete(ChatRequest(listOf(ChatMessage.user(prompt, listOf(image)))))
        val text = response.text.trim()
        return OcrResult(text = text, pages = listOf(OcrPage(0, text)))
    }
}
