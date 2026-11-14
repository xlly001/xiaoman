package dev.xiaoman.web

import dev.xiaoman.agent.Prompts
import dev.xiaoman.api.*
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.skills.DirectorySkillRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class WebSkillsTest {
    private fun writeSkill(root: Path, name: String, channel: String, extra: String = "") {
        val dir = Files.createDirectories(root.resolve(name))
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: $name\ndescription: 工作流 $name\nchannel: $channel\n$extra---\n先列出三件事，再设置提醒")
    }

    private fun request(server: WebServer, path: String, body: String, session: String? = null): String {
        val c = URL("http://127.0.0.1:" + server.port + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        session?.let { c.setRequestProperty("X-Xiaoman-Session", it) }
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            assertEquals(200, c.responseCode)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    @Test
    fun directoryWorkflowsReachWebPromptAndPhoneSkillsAreFiltered() {
        val root = Files.createTempDirectory("web-skills-")
        try {
            writeSkill(root, "today-plan", "chat", "memory: [偏好/工作]\n")
            writeSkill(root, "taxi-home", "gui")
            writeSkill(root, "broken", "chat", "apps: []\n")
            val requests = CopyOnWriteArrayList<ChatRequest>()
            val logs = CopyOnWriteArrayList<String>()
            val llm = LlmClientFactory { config -> object : LlmClient {
                override val config = config
                override suspend fun complete(request: ChatRequest): ChatResponse {
                    requests += request
                    return ChatResponse("安排好了")
                }
            } }
            WebServer(WebServer.Options(port = 0, skillsRoot = root, allowPrivateMcp = true), llm = llm, logger = { logs.add(it) }).start().use { server ->
                val sid = Json.parseToJsonElement(request(server, "/api/session", "{}")).jsonObject.str("sessionId")
                val body = """{"message":"今日开工","model":{"baseUrl":"https://llm.invalid","model":"fake","apiKey":"test"},"mcp":{}}"""
                val reply = request(server, "/api/chat", body, sid)
                assertTrue("安排好了" in reply)
                val prompt = requests.single().messages.first().text
                assertTrue("### today-plan：工作流 today-plan" in prompt)
                assertTrue("先列出三件事，再设置提醒" in prompt)
                assertFalse("taxi-home" in prompt)
                assertFalse(Prompts.SKILLS_HEADER in prompt)
                assertFalse(requests.single().tools.any { it.name == "phone_task" })
                assertTrue(logs.any { "加载 1 个，问题 1 个" in it })
            }
            runBlocking {
                val repository = ChatSkillRepository(DirectorySkillRepository(listOf(root.toFile())))
                assertEquals(listOf("today-plan"), repository.reload().skills.map { it.name })
                assertEquals(listOf("today-plan"), repository.list().map { it.name })
                assertNull(repository.get("taxi-home"))
                assertNotNull(repository.get("today-plan"))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun localDiscoveryPrefersExamplesAndFindsJarParent() {
        val root = Files.createTempDirectory("local-skills-")
        try {
            val cwd = Files.createDirectories(root.resolve("cwd"))
            val bundle = Files.createDirectories(root.resolve("bundle"))
            val lib = Files.createDirectories(bundle.resolve("lib"))
            val bundledSkills = Files.createDirectories(bundle.resolve("skills"))
            assertEquals(bundledSkills, localSkillsRoot(cwd, lib.resolve("web-server.jar")))
            val examples = Files.createDirectories(cwd.resolve("skills/examples"))
            assertEquals(examples, localSkillsRoot(cwd, lib.resolve("web-server.jar")))
            assertNull(localSkillsRoot(root.resolve("missing"), null))
        } finally { root.toFile().deleteRecursively() }
    }
}
