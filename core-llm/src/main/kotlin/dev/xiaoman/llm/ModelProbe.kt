package dev.xiaoman.llm

import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelRole
import java.util.Base64

/** Result of a one-shot model probe; the UI turns it into a human-readable diagnosis. */
sealed class ProbeResult {
    data class Ok(val latencyMillis: Long, val reply: String) : ProbeResult()

    data class Failed(
        val statusCode: Int? = null,
        val providerCode: String? = null,
        val message: String = "",
        val retryable: Boolean = false,
    ) : ProbeResult()
}

/**
 * Sends the smallest possible request to verify an OpenAI-compatible endpoint.
 *
 * The operator model receives a 1x1 PNG as well, because Xiaoman's operation loop needs image input;
 * a text-only success would otherwise hide a configuration that cannot operate the phone.
 */
object ModelProbe {
    private val onePixelPng: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )

    suspend fun probe(client: LlmClient, role: ModelRole): ProbeResult {
        val parts = buildList {
            add(ContentPart.Text(if (role == ModelRole.OPERATOR) "看这张图，只回复 OK" else "只回复 OK"))
            if (role == ModelRole.OPERATOR) add(ContentPart.ImagePng(onePixelPng))
        }
        val started = System.nanoTime()
        return try {
            val response = client.complete(ChatRequest(listOf(ChatMessage(dev.xiaoman.api.ChatRole.USER, parts))))
            ProbeResult.Ok(
                latencyMillis = (System.nanoTime() - started) / 1_000_000,
                reply = response.text.trim().take(120),
            )
        } catch (e: LlmException) {
            ProbeResult.Failed(
                statusCode = e.statusCode,
                providerCode = e.providerCode,
                message = e.message ?: e.javaClass.simpleName,
                retryable = e.retryable,
            )
        } catch (e: Exception) {
            ProbeResult.Failed(message = e.message ?: e.javaClass.simpleName)
        }
    }
}
