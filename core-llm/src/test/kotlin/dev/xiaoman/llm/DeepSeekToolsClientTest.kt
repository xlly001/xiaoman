package dev.xiaoman.llm

import dev.xiaoman.api.MediaCapability
import dev.xiaoman.api.MediaException
import dev.xiaoman.api.OcrPurpose
import dev.xiaoman.api.TtsVoice
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeepSeekToolsClientTest {
    private val apiKey = "sk-secret-123456"
    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekToolsClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = DeepSeekToolsClient(server.url("/v1").toString(), apiKey)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `asr posts multipart audio and parses the transcript`() = runTest {
        server.enqueue(MockResponse().setBody("""{"request_id":"asr-1","text":"上海今天天气怎么样？","duration_ms":3200}"""))

        val result = client.asr(ByteArray(64) { 1 }, "speech.wav")

        assertEquals("上海今天天气怎么样？", result.text)
        assertEquals(3200L, result.durationMs)
        assertEquals("asr-1", result.requestId)
        val sent = server.takeRequest()
        assertEquals("/v1/tools/asr", sent.path)
        assertEquals("Bearer $apiKey", sent.getHeader("Authorization"))
        val multipart = sent.body.readUtf8()
        assertTrue(multipart.contains("name=\"file\""))
        assertTrue(multipart.contains("filename=\"speech.wav\""))
        assertTrue(multipart.contains("audio/wav"))
    }

    @Test
    fun `asr labels opus clips with an ogg content type`() = runTest {
        server.enqueue(MockResponse().setBody("""{"request_id":"a","text":"hi","duration_ms":900}"""))
        client.asr(ByteArray(32), "speech.opus")
        assertTrue(server.takeRequest().body.readUtf8().contains("audio/ogg"))
    }

    @Test
    fun `tts sends the official json fields and returns wav bytes`() = runTest {
        val wav = ByteArray(256) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(wav)).setHeader("Content-Type", "audio/wav"))

        val audio = client.tts("你好，我是小满。", TtsVoice.TIDE)

        assertEquals(256, audio.size)
        val sent = server.takeRequest()
        assertEquals("/v1/tools/tts", sent.path)
        assertEquals("Bearer $apiKey", sent.getHeader("Authorization"))
        val json = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("你好，我是小满。", json["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tide", json["voice_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("wav", json["format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", json["filter_markdown"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `tts rejects a truncated audio response`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(12))))
        val error = assertFailsWith<MediaException> { client.tts("hi") }
        assertEquals(MediaCapability.TTS, error.capability)
        assertTrue(error.message!!.contains("truncated"))
    }

    @Test
    fun `ocr parses keep-alive newlines and joins every page`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "\n\n" + """{"id":"ocr-9","object":"ocr.result","created":1731600000,"purpose":"text_recognition","page_count":2,"pages":[{"index":0,"text":"第一页文字","finish_reason":"stop"},{"index":1,"text":"第二页文字","finish_reason":"stop"}]}""",
            ),
        )

        val result = client.ocr(ByteArray(48) { 2 }, "scan.png", OcrPurpose.RICH_LAYOUT)

        assertEquals("第一页文字\n\n第二页文字", result.text)
        assertEquals(2, result.pages.size)
        assertEquals(1, result.pages[1].index)
        assertEquals("ocr-9", result.requestId)
        val sent = server.takeRequest()
        assertEquals("/v1/tools/ocr", sent.path)
        val multipart = sent.body.readUtf8()
        assertTrue(multipart.contains("name=\"purpose\""))
        assertTrue(multipart.contains("rich_layout_recognition"))
        assertTrue(multipart.contains("filename=\"scan.png\""))
    }

    @Test
    fun `http errors keep the status code and capability`() = runTest {
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":{"message":"file too large"}}"""))
        val error = assertFailsWith<MediaException> { client.ocr(ByteArray(8)) }
        assertEquals(413, error.statusCode)
        assertEquals(MediaCapability.OCR, error.capability)
        assertTrue(error.message!!.contains("file too large"))
    }

    @Test
    fun `a missing api key fails before any request`() = runTest {
        val noKey = DeepSeekToolsClient(server.url("/v1").toString(), "")
        val error = assertFailsWith<MediaException> { noKey.asr(ByteArray(8)) }
        assertEquals(MediaCapability.ASR, error.capability)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tools root is derived from the openai compatible base url`() {
        assertEquals("https://api.deepseek.com/v1/tools", DeepSeekToolsClient.toolsRoot("https://api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com/v1/tools", DeepSeekToolsClient.toolsRoot("https://api.deepseek.com/v1/"))
        assertEquals("https://api.deepseek.com/v1/tools", DeepSeekToolsClient.toolsRoot("https://api.deepseek.com"))
        assertEquals("http://10.0.2.2:8765/v1/tools", DeepSeekToolsClient.toolsRoot("http://10.0.2.2:8765/v1"))
        assertEquals("https://proxy.example/v1/tools", DeepSeekToolsClient.toolsRoot("https://proxy.example/v1/tools"))
    }
}
