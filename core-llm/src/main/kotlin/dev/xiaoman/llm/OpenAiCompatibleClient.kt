package dev.xiaoman.llm

import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmClient] for any OpenAI-compatible `/chat/completions` endpoint.
 *
 * Retries 408/409/429/5xx and transport errors up to [maxRetries] times with exponential backoff
 * starting at [baseDelayMillis] (or the server's `Retry-After` seconds). If the endpoint rejects
 * `response_format`, the request is retried once without it and JSON mode stays off for this client.
 * Error messages never contain the API key.
 */
class OpenAiCompatibleClient(
    override val config: ModelConfig,
    http: OkHttpClient = LlmClients.sharedHttp,
    private val maxRetries: Int = 2,
    private val baseDelayMillis: Long = 500,
    /** Backoff sleep; replace in tests to avoid real waiting. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : LlmClient {
    private val http = http.newBuilder()
        .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()
    private val url = config.baseUrl.trimEnd('/') + "/chat/completions"

    @Volatile
    private var jsonModeUnsupported = false

    override suspend fun complete(request: ChatRequest): ChatResponse {
        var attempt = 0
        var jsonMode = request.jsonMode && !jsonModeUnsupported
        while (true) {
            val result = try {
                send(buildBody(request, jsonMode))
            } catch (e: IOException) {
                if (attempt < maxRetries) {
                    sleep(backoff(attempt++, null))
                    continue
                }
                throw LlmException(redact("Request to $url failed: ${e.message ?: e.javaClass.simpleName}"), retryable = true, cause = e)
            }
            if (result.code in 200..299) return parseResponse(result.body)
            if (result.code == 400 && jsonMode && result.body.contains("response_format")) {
                jsonModeUnsupported = true
                jsonMode = false
                continue
            }
            val providerCode = providerCode(result.body)
            // A configured spending/inference limit needs an account change, not backoff.
            val retryable = isRetryable(result.code) && providerCode != "SetLimitExceeded"
            if (retryable && attempt < maxRetries) {
                sleep(backoff(attempt++, result.retryAfter))
                continue
            }
            throw LlmException(redact("HTTP ${result.code} from $url: ${errorDetail(result.body)}"), result.code, retryable,
                providerCode = providerCode)
        }
    }

    /**
     * Streams one chat round over SSE: text chunks go to [onText], tool-call deltas are assembled
     * into the returned [ChatResponse]. Falls back to a normal JSON body if the server ignores
     * `stream` and answers with one object.
     */
    override suspend fun completeStreaming(
        request: ChatRequest,
        shouldStop: () -> Boolean,
        onText: (String) -> Unit,
    ): ChatResponse {
        var attempt = 0
        var jsonMode = request.jsonMode && !jsonModeUnsupported
        while (true) {
            try {
                when (val result = stream(buildBody(request, jsonMode, stream = true), shouldStop, onText)) {
                    is StreamResult.Done -> return result.response
                    StreamResult.Stopped -> throw CancellationException("stopped")
                }
            } catch (e: StreamHttpError) {
                if (e.code == 400 && jsonMode && e.body.contains("response_format")) {
                    jsonModeUnsupported = true
                    jsonMode = false
                    continue
                }
                val providerCode = providerCode(e.body)
                val retryable = isRetryable(e.code) && providerCode != "SetLimitExceeded"
                if (retryable && attempt < maxRetries) {
                    sleep(backoff(attempt++, e.retryAfter))
                    continue
                }
                throw LlmException(
                    redact("HTTP ${e.code} from $url: ${errorDetail(e.body)}"),
                    e.code,
                    retryable,
                    providerCode = providerCode,
                )
            } catch (e: IOException) {
                if (shouldStop()) throw CancellationException("stopped")
                if (attempt < maxRetries) {
                    sleep(backoff(attempt++, null))
                    continue
                }
                throw LlmException(
                    redact("Request to $url failed: ${e.message ?: e.javaClass.simpleName}"),
                    retryable = true,
                    cause = e,
                )
            }
        }
    }

    /**
     * Reads the SSE stream from the response and assembles a [ChatResponse]. Text chunks are pushed
     * through [onText] as they arrive; tool-call fragments are accumulated by `index` and joined into
     * full [ToolCall]s. If the server ignores `stream` and answers with a single JSON object, the body
     * is handed to [parseResponse] instead. A `2xx` status is required; anything else throws
     * [StreamHttpError] so the caller can retry. Checking [shouldStop] between chunks lets the caller
     * cancel a turn cooperatively.
     */
    private suspend fun stream(
        body: JsonObject,
        shouldStop: () -> Boolean,
        onText: (String) -> Unit,
    ): StreamResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .apply { config.extraHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
        val call = http.newCall(request)
        // Break the blocking read if this coroutine is cancelled (e.g. the caller's `Job` is cancelled).
        coroutineContext[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (shouldStop()) throw CancellationException("stopped")
            throw e
        }
        val code = response.code
        val retryAfter = response.header("Retry-After")
        val payload0 = response.body ?: throw StreamHttpError(code, "", retryAfter)
        try {
            val source = payload0.source()
            if (code !in 200..299) {
                val err = source.readString(Charsets.UTF_8)
                throw StreamHttpError(code, err, retryAfter)
            }
            val text = StringBuilder()
            val toolSlots = linkedMapOf<Int, ToolCallBuilder>()
            var finishReason: String? = null
            var usage: Usage? = null
            var first = true
            var sse = true
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (first) {
                    first = false
                    sse = trimmed.startsWith("data:")
                }
                if (!sse) {
                    // The endpoint ignored `stream` and replied with one JSON object.
                    val full = trimmed + "\n" + source.readString(Charsets.UTF_8)
                    return@withContext StreamResult.Done(parseResponse(full))
                }
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") continue
                val root = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: continue
                val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                if (choice != null) {
                    val delta = choice["delta"] as? JsonObject
                    val chunk = contentText(delta?.get("content"))
                    if (!chunk.isNullOrEmpty()) {
                        text.append(chunk)
                        onText(chunk)
                        if (shouldStop()) {
                            // Cooperatively stop: return a sentinel so the caller can surface a
                            // `CancellationException` at the top level (outside `withContext`).
                            return@withContext StreamResult.Stopped
                        }
                    }
                    (delta?.get("tool_calls") as? JsonArray)?.forEach { element ->
                        val tool = element as? JsonObject ?: return@forEach
                        val index = (tool["index"] as? JsonPrimitive)?.intOrNull ?: 0
                        val slot = toolSlots.getOrPut(index) { ToolCallBuilder() }
                        (tool["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { slot.id = it }
                        val function = tool["function"] as? JsonObject
                        function?.get("name")?.let { if (it is JsonPrimitive && it.content.isNotEmpty()) slot.name = it.content }
                        function?.get("arguments")?.let { if (it is JsonPrimitive) slot.args.append(it.content) }
                    }
                    (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finishReason = it }
                }
                (root["usage"] as? JsonObject)?.let {
                    usage = Usage(it.int("prompt_tokens") ?: 0, it.int("completion_tokens") ?: 0)
                }
            }
            val toolCalls = toolSlots.entries.sortedBy { it.key }.map { ToolCall(it.value.id, it.value.name, it.value.args.toString().ifEmpty { "{}" }) }
            StreamResult.Done(ChatResponse(text.toString(), toolCalls, finishReason, usage))
        } finally {
            payload0.close()
        }
    }

    private class ToolCallBuilder(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private class StreamHttpError(val code: Int, val body: String, val retryAfter: String?) : Exception("HTTP $code")

    /** Result of one streaming attempt: either a full reply or a cooperative stop signal. */
    private sealed interface StreamResult {
        data class Done(val response: ChatResponse) : StreamResult
        data object Stopped : StreamResult
    }

    internal fun buildBody(request: ChatRequest, jsonMode: Boolean, stream: Boolean = false): JsonObject = buildJsonObject {
        put("model", config.model)
        if (stream) put("stream", true)
        putJsonArray("messages") { request.messages.forEach { add(encodeMessage(it)) } }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    }
                }
            }
        }
        if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        config.temperature?.let { put("temperature", it) }
        config.maxTokens?.let { put("max_tokens", it) }
    }

    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role.name.lowercase())
        val hasImage = message.parts.any { it is ContentPart.ImagePng }
        when {
            hasImage -> putJsonArray("content") {
                message.parts.forEach { part ->
                    addJsonObject {
                        when (part) {
                            is ContentPart.Text -> {
                                put("type", "text")
                                put("text", part.text)
                            }
                            is ContentPart.ImagePng -> {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(part.bytes))
                                }
                            }
                        }
                    }
                }
            }
            message.toolCalls.isNotEmpty() && message.text.isEmpty() -> put("content", JsonNull)
            else -> put("content", message.text)
        }
        if (message.role == ChatRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { call ->
                    addJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        }
                    }
                }
            }
        }
        message.toolCallId?.let { put("tool_call_id", it) }
    }

    private fun parseResponse(body: String): ChatResponse {
        val root = try {
            json.parseToJsonElement(body) as JsonObject
        } catch (e: Exception) {
            throw LlmException(redact("Malformed response from $url: ${body.take(200)}"), 200, cause = e)
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw LlmException(redact("Response from $url has no choices: ${errorDetail(body)}"), 200)
        val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
        val toolCalls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            val args = when (val a = function["arguments"]) {
                null, is JsonNull -> "{}"
                is JsonPrimitive -> a.content
                else -> a.toString()
            }
            ToolCall(call.string("id").orEmpty(), function.string("name").orEmpty(), args)
        }
        val usage = (root["usage"] as? JsonObject)?.let {
            Usage(it.int("prompt_tokens") ?: 0, it.int("completion_tokens") ?: 0)
        }
        return ChatResponse(contentText(message["content"]), toolCalls, choice.string("finish_reason"), usage)
    }

    private fun contentText(content: JsonElement?): String = when (content) {
        null, is JsonNull -> ""
        is JsonPrimitive -> content.content
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.content
                is JsonObject -> if (part.string("type") in setOf(null, "text")) part.string("text").orEmpty() else ""
                else -> ""
            }
        }
        is JsonObject -> content.string("text").orEmpty()
    }

    /** Runs the HTTP call on [Dispatchers.IO]; cancelling the coroutine cancels the OkHttp call. */
    private suspend fun send(body: JsonObject): HttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
        config.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val call = http.newCall(builder.build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                val result = call.execute().use { response ->
                    HttpResult(response.code, response.body?.string().orEmpty(), response.header("Retry-After"))
                }
                cont.resume(result)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun backoff(attempt: Int, retryAfter: String?): Long =
        retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }
            ?: (baseDelayMillis shl attempt.coerceAtMost(20))

    private fun errorDetail(body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body) as JsonObject
            when (val error = root["error"]) {
                is JsonObject -> error.string("message")
                is JsonPrimitive -> error.contentOrNull
                else -> root.string("message")
            }
        }.getOrNull()
        return (message ?: body).take(500).ifBlank { "<empty body>" }
    }

    /** Only carry a short provider identifier into user-facing events, never a raw error body. */
    private fun providerCode(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as JsonObject
        val error = root["error"] as? JsonObject
        (error?.string("code") ?: root.string("code"))?.takeIf {
            it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")) &&
                (config.apiKey.isBlank() || !it.contains(config.apiKey))
        }
    }.getOrNull()

    private fun redact(text: String): String =
        if (config.apiKey.isBlank()) text else text.replace(config.apiKey, "***")

    private class HttpResult(val code: Int, val body: String, val retryAfter: String?)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        fun isRetryable(code: Int) = code == 408 || code == 409 || code == 429 || code in 500..599
        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
