package dev.xiaoman.llm

import dev.xiaoman.api.AsrResult
import dev.xiaoman.api.MediaCapability
import dev.xiaoman.api.MediaException
import dev.xiaoman.api.OcrPage
import dev.xiaoman.api.OcrPurpose
import dev.xiaoman.api.OcrResult
import dev.xiaoman.api.TtsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wire client for the standalone DeepSeek application APIs released on 2026-11-13:
 *
 * - `POST /v1/tools/ocr`  multipart `file` + `purpose`, returns `pages[].text`
 * - `POST /v1/tools/asr`  multipart `file` (16-bit PCM WAV / Ogg Opus), returns `text`
 * - `POST /v1/tools/tts`  JSON `{text, voice_id, format, filter_markdown}`, streams WAV bytes
 *
 * All three share the OpenAI-compatible base URL and the same API key. Audio capture, playback
 * and image selection are deliberately left to the Android layer; this class owns only HTTP.
 *
 * Docs: https://api-docs.deepseek.com/zh-cn/guides/{ocr,asr,tts}
 */
class DeepSeekToolsClient(
    baseUrl: String,
    private val apiKey: String,
    http: OkHttpClient = LlmClients.sharedHttp,
    timeoutSeconds: Long = 120,
) {
    private val http = http.newBuilder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** e.g. `https://api.deepseek.com/v1/tools`. Never contains the API key. */
    val endpoint: String = toolsRoot(baseUrl)

    /** Transcribe one recorded clip. [audio] must be 16-bit PCM WAV or Ogg Opus, <= 120 s / 8 MiB. */
    suspend fun asr(audio: ByteArray, fileName: String = "speech.wav"): AsrResult =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, audio.toRequestBody(audioType(fileName)))
                .build()
            val raw = execute("asr", body, MediaCapability.ASR)
            val obj = parseObject(raw, MediaCapability.ASR)
            val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isBlank()) throw MediaException("ASR returned no transcript", capability = MediaCapability.ASR)
            AsrResult(
                text = text.trim(),
                durationMs = obj["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong(),
                requestId = obj["request_id"]?.jsonPrimitive?.contentOrNull,
            )
        }

    /**
     * Synthesize [text] and return the complete WAV byte stream. The service streams audio as it
     * is generated, so a response that starts but ends early must be treated as a failure; callers
     * should check that the bytes form a playable WAV before using them.
     */
    suspend fun tts(text: String, voice: TtsVoice = TtsVoice.STELLA, filterMarkdown: Boolean = true): ByteArray =
        withContext(Dispatchers.IO) {
            checkCredentials(MediaCapability.TTS)
            if (text.isBlank()) throw MediaException("TTS needs non-empty text", capability = MediaCapability.TTS)
            val payload = buildJsonObject {
                put("text", text)
                put("voice_id", voice.wire)
                put("format", "wav")
                put("filter_markdown", filterMarkdown)
            }.toString()
            val request = Request.Builder()
                .url("$endpoint/tts")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody(JSON_TYPE))
                .build()
            val bytes = try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw mediaError("tts", response.code, response.body?.string().orEmpty(), MediaCapability.TTS)
                    }
                    response.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: IOException) {
                throw MediaException(
                    "TTS request failed: ${e.message ?: e.javaClass.simpleName}",
                    capability = MediaCapability.TTS,
                    cause = e,
                )
            }
            // A WAV header alone (or an empty body) means synthesis was interrupted.
            if (bytes.size <= WAV_HEADER_MIN) {
                throw MediaException("TTS returned truncated audio (${bytes.size} bytes)", capability = MediaCapability.TTS)
            }
            bytes
        }

    /**
     * Extract text from an image or document. The service sends newline keep-alive bytes before
     * the JSON object, so the body is trimmed before parsing.
     */
    suspend fun ocr(image: ByteArray, fileName: String = "image.png", purpose: OcrPurpose = OcrPurpose.TEXT): OcrResult =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", purpose.wire)
                .addFormDataPart("file", fileName, image.toRequestBody(imageType(fileName)))
                .build()
            val raw = execute("ocr", body, MediaCapability.OCR)
            val obj = parseObject(raw, MediaCapability.OCR)
            val pages = obj["pages"]?.jsonArray.orEmpty().mapIndexed { index, element ->
                val page = element as? JsonObject ?: return@mapIndexed OcrPage(index, "")
                OcrPage(
                    index = page["index"]?.jsonPrimitive?.intOrNull ?: index,
                    text = page["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    finishReason = page["finish_reason"]?.jsonPrimitive?.contentOrNull,
                )
            }
            val text = pages.joinToString("\n\n") { it.text }.trim()
            if (text.isBlank()) throw MediaException("OCR returned no text", capability = MediaCapability.OCR)
            OcrResult(
                text = text,
                pages = pages,
                requestId = obj["id"]?.jsonPrimitive?.contentOrNull,
            )
        }

    private fun execute(path: String, body: RequestBody, capability: MediaCapability): String {
        checkCredentials(capability)
        val request = Request.Builder()
            .url("$endpoint/$path")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw mediaError(path, response.code, raw, capability)
                raw
            }
        } catch (e: IOException) {
            throw MediaException(
                "Request to /$path failed: ${e.message ?: e.javaClass.simpleName}",
                capability = capability,
                cause = e,
            )
        }
    }

    private fun checkCredentials(capability: MediaCapability) {
        if (apiKey.isBlank()) {
            throw MediaException("DeepSeek API key is not configured", capability = capability)
        }
    }

    private fun parseObject(raw: String, capability: MediaCapability): JsonObject {
        val element = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrElse { cause ->
            throw MediaException("Invalid JSON from DeepSeek tools: ${cause.message}", capability = capability, cause = cause)
        }
        return element as? JsonObject
            ?: throw MediaException("Unexpected JSON shape from DeepSeek tools", capability = capability)
    }

    private fun mediaError(path: String, code: Int, body: String, capability: MediaCapability): MediaException {
        val message = runCatching {
            Json.parseToJsonElement(body.trim()).jsonObject["error"]?.jsonObject?.get("message")
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (code) {
            400 -> "invalid input"
            408 -> "upload timed out"
            413 -> "file too large"
            429 -> "rate limited"
            503 -> "service busy"
            504 -> "timeout"
            else -> "request rejected"
        }
        return MediaException(
            "HTTP $code from /$path ($hint): ${(message ?: body.take(160)).replace(apiKey, "***")}",
            statusCode = code,
            capability = capability,
        )
    }

    private fun audioType(fileName: String) =
        if (fileName.endsWith(".opus", ignoreCase = true) || fileName.endsWith(".ogg", ignoreCase = true)) {
            "audio/ogg".toMediaType()
        } else {
            "audio/wav".toMediaType()
        }

    private fun imageType(fileName: String): okhttp3.MediaType = when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg".toMediaType()
        fileName.endsWith(".webp", true) -> "image/webp".toMediaType()
        fileName.endsWith(".pdf", true) -> "application/pdf".toMediaType()
        else -> "image/png".toMediaType()
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val WAV_HEADER_MIN = 44

        /**
         * Entry point for modules that do not declare OkHttp themselves (e.g. `app`). The
         * constructor's `http` parameter has an [okhttp3.OkHttpClient] default, which would
         * otherwise leak that type into every caller's classpath.
         */
        fun fromConfig(baseUrl: String, apiKey: String, timeoutSeconds: Long = 120): DeepSeekToolsClient =
            DeepSeekToolsClient(baseUrl, apiKey, LlmClients.sharedHttp, timeoutSeconds)

        /**
         * Derive the tools root from the OpenAI-compatible base URL.
         * `https://api.deepseek.com/v1` -> `https://api.deepseek.com/v1/tools`;
         * a bare host gets `/v1` appended. Works for local proxies such as
         * `http://10.0.2.2:8765/v1` used by the E2E harness.
         */
        fun toolsRoot(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            require(base.startsWith("http://") || base.startsWith("https://")) {
                "DeepSeek tools base URL must be an http(s) URL"
            }
            return when {
                base.endsWith("/tools") -> base
                base.endsWith("/v1") -> "$base/tools"
                else -> "$base/v1/tools"
            }
        }
    }
}
