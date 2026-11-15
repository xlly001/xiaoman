package dev.xiaoman.llm

import dev.xiaoman.api.LlmException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogClientTest {
    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `parses data array and sends the bearer key`() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"gpt-4o","owned_by":"openai"},{"id":"deepseek-chat"}]}""",
                ),
            )
            val models = ModelCatalogClient().list(server.url("/v1").toString(), "secret-key")
            assertEquals(listOf("deepseek-chat", "gpt-4o"), models.map { it.id })
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer secret-key", request.getHeader("Authorization"))
        }
    }

    @Test fun `accepts a models array and trims trailing slashes`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"models":[{"id":"a"},{"id":"a"},{"id":"b"}]}"""))
            val models = ModelCatalogClient().list(server.url("/v1/").toString().trimEnd('/') + "/", "k")
            assertEquals(listOf("a", "b"), models.map { it.id })
            assertEquals("/v1/models", server.takeRequest().path)
        }
    }

    @Test fun `401 keeps the status and never leaks the key`() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setBody("""{"error":{"message":"bad key secret-key","code":"AuthFailed"}}"""),
            )
            val error = assertFailsWith<LlmException> {
                ModelCatalogClient().list(server.url("/v1").toString(), "secret-key")
            }
            assertEquals(401, error.statusCode)
            assertEquals("AuthFailed", error.providerCode)
            assertFalse(error.message.orEmpty().contains("secret-key"))
        }
    }

    @Test fun `404 is reported so the UI can offer manual model input`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            val error = assertFailsWith<LlmException> {
                ModelCatalogClient().list(server.url("/v1").toString(), "k")
            }
            assertEquals(404, error.statusCode)
        }
    }

    @Test fun `empty and malformed bodies are handled`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(ModelCatalogClient().list(server.url("/v1").toString(), "k").isEmpty())

            server.enqueue(MockResponse().setBody("not json"))
            val error = assertFailsWith<LlmException> {
                ModelCatalogClient().list(server.url("/v1").toString(), "k")
            }
            assertEquals(200, error.statusCode)
        }
    }
}
