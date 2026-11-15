package dev.xiaoman.llm

import dev.xiaoman.api.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One model returned by an OpenAI-compatible `GET /models` endpoint.
 *
 * OpenRouter and several gateways include context/output/vision metadata; plain OpenAI/DeepSeek
 * responses only have [id], so callers fall back to model-family defaults.
 */
data class RemoteModel(
    val id: String,
    val ownedBy: String? = null,
    val contextLength: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsVision: Boolean? = null,
)

/**
 * Lists models from an OpenAI-compatible provider.
 *
 * A few providers (notably Volcano Ark) use endpoint IDs or do not expose `/models`; callers should
 * treat 404/empty results as "manual model ID required" instead of a hard failure.
 */
class ModelCatalogClient internal constructor(private val http: OkHttpClient) {
    /** App callers use this constructor; OkHttp stays an implementation detail of core-llm. */
    constructor() : this(LlmClients.sharedHttp)

    private val client = http.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun list(
        baseUrl: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): List<RemoteModel> = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/') + "/models"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${apiKey.trim()}")
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw LlmException(redact("Request to $url failed: ${e.message ?: e.javaClass.simpleName}", apiKey), retryable = true, cause = e)
        }
        response.use { result ->
            val body = result.body?.string().orEmpty()
            if (result.code !in 200..299) {
                throw LlmException(
                    redact("HTTP ${result.code} from $url: ${errorDetail(body)}", apiKey),
                    result.code,
                    retryable = result.code == 408 || result.code == 429 || result.code in 500..599,
                    providerCode = providerCode(body, apiKey),
                )
            }
            parse(body)
        }
    }

    private fun parse(body: String): List<RemoteModel> {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrElse {
            throw LlmException(redact("Malformed model list: ${body.take(200)}", ""), 200, cause = it)
        }
        val array = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element ->
            val objectValue = element as? JsonObject ?: return@mapNotNull null
            val id = (objectValue["id"] as? JsonPrimitive)?.contentOrNull
                ?: (objectValue["name"] as? JsonPrimitive)?.contentOrNull
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { modelId ->
                val topProvider = objectValue["top_provider"] as? JsonObject
                val architecture = objectValue["architecture"] as? JsonObject
                val modalities = (architecture?.get("input_modalities") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val capabilities = objectValue["capabilities"] as? JsonObject
                RemoteModel(
                    id = modelId,
                    ownedBy = objectValue.string("owned_by"),
                    contextLength = objectValue.int("context_length")
                        ?: objectValue.int("context_window")
                        ?: objectValue.int("max_context_length"),
                    maxOutputTokens = objectValue.int("max_completion_tokens")
                        ?: objectValue.int("max_output_tokens")
                        ?: topProvider?.int("max_completion_tokens"),
                    supportsVision = when {
                        modalities != null -> "image" in modalities
                        else -> (objectValue["supports_vision"] as? JsonPrimitive)?.booleanOrNull
                            ?: (capabilities?.get("vision") as? JsonPrimitive)?.booleanOrNull
                    },
                )
            }
        }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
    }

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

    private fun providerCode(body: String, apiKey: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as JsonObject
        val error = root["error"] as? JsonObject
        (error?.string("code") ?: root.string("code"))?.takeIf {
            it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")) && (apiKey.isBlank() || !it.contains(apiKey))
        }
    }.getOrNull()

    private fun redact(text: String, apiKey: String): String =
        if (apiKey.isBlank()) text else text.replace(apiKey, "***")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
