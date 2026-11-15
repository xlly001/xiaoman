package dev.xiaoman.agent

import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillAuthoringTest {
    private val markdown = """
        ---
        name: taxi-home
        description: 打车回家
        channel: gui
        apps: [com.autonavi.minimap]
        ---
        # 高德打车回家
        1. 目的地取自记忆，没有就询问。
        2. 点「确认呼叫」前必须得到用户确认。
    """.trimIndent()

    private val bundledMarkdown = markdown.replace("打车回家", "内置打车")

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun Harness.saveCall(markdown: String = this@SkillAuthoringTest.markdown, name: String = "taxi-home") =
        ToolCall("c1", AgentTools.SKILL_SAVE, args("name" to name, "skill_md" to markdown))

    private fun toolResult(h: Harness): JsonObject =
        Json.parseToJsonElement(h.chat.requests.last().messages.last().text).jsonObject

    /** Wires the fake repository so a reload after save sees the skill at the store's user folder. */
    private fun FakeUserSkillStore.exposeTo(h: Harness) {
        onSaved = { name -> h.skills.add(Skill(name, "测试技能 $name", instructions = "步骤", source = userFolder(name).path)) }
    }

    @Test
    fun confirmedSaveWritesAndReloadMakesSkillVisible() = runTest {
        val store = FakeUserSkillStore()
        val h = Harness(users = FakeUsers("确认"), userSkillStore = store)
        store.exposeTo(h)
        h.chat.tools(h.saveCall()).reply("已经保存好了，你可以说「打车回家」触发。")

        h.agent.handle("帮我新建一个打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(true, result["saved"]!!.jsonPrimitive.boolean)
        assertEquals(true, result["created"]!!.jsonPrimitive.boolean)
        assertEquals(false, result["overridesBundled"]!!.jsonPrimitive.boolean)
        assertTrue(result["path"]!!.jsonPrimitive.content.replace('\\', '/').endsWith("taxi-home/SKILL.md"))
        assertEquals(listOf("taxi-home" to markdown), store.saved)
        assertEquals(1, h.skills.reloadCount)

        val (question, takeover) = h.users.asked.single()
        assertFalse(takeover)
        assertTrue("新建" in question)
        assertTrue("taxi-home" in question && "打车回家" in question && "gui" in question)
        assertTrue(markdown in question)
        assertTrue(question.trimEnd().endsWith("回复“确认”保存，其他回复取消。"))
    }

    @Test
    fun saveOverExistingUserSkillIsAnUpdate() = runTest {
        val store = FakeUserSkillStore()
        store.save("taxi-home", markdown)
        store.saved.clear()
        val h = Harness(users = FakeUsers("yes"), userSkillStore = store)
        store.exposeTo(h)
        h.chat.tools(h.saveCall()).reply("改好了")

        h.agent.handle("修改打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(true, result["saved"]!!.jsonPrimitive.boolean)
        assertEquals(false, result["created"]!!.jsonPrimitive.boolean)
        assertTrue("修改" in h.users.asked.single().first)
    }

    @Test
    fun overridingBundledSkillIsCalledOutInTheQuestion() = runTest {
        val store = FakeUserSkillStore(bundled = mapOf("taxi-home" to bundledMarkdown))
        val h = Harness(users = FakeUsers("确认"), userSkillStore = store)
        store.exposeTo(h)
        h.chat.tools(h.saveCall()).reply("好了")

        h.agent.handle("覆盖内置打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(true, result["saved"]!!.jsonPrimitive.boolean)
        assertEquals(true, result["overridesBundled"]!!.jsonPrimitive.boolean)
        assertTrue("覆盖同名内置技能" in h.users.asked.single().first)
    }

    @Test
    fun longDraftIsTruncatedInTheConfirmationQuestion() = runTest {
        val store = FakeUserSkillStore()
        val h = Harness(users = FakeUsers("确认"), userSkillStore = store)
        store.exposeTo(h)
        val longMarkdown = markdown + "\n" + "补充说明。".repeat(700)
        h.chat.tools(h.saveCall(longMarkdown)).reply("好了")

        h.agent.handle("新建技能", h.events, h.stop)

        val question = h.users.asked.single().first
        assertTrue("[SKILL.md 超过 3000 字，展示已截断]" in question)
        assertFalse(longMarkdown in question)
        assertEquals(true, toolResult(h)["saved"]!!.jsonPrimitive.boolean)
        // The full text, not the truncated draft, is what reaches disk.
        assertEquals(longMarkdown, store.saved.single().second)
    }

    @Test
    fun continueAlsoConfirmsSave() = runTest {
        val store = FakeUserSkillStore()
        val h = Harness(users = FakeUsers("继续"), userSkillStore = store)
        store.exposeTo(h)
        h.chat.tools(h.saveCall()).reply("好了")

        h.agent.handle("继续保存技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(true, result["saved"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("taxi-home" to markdown), store.saved)
    }

    @Test
    fun continueDoesNotConfirmDelete() = runTest {
        val store = FakeUserSkillStore(bundled = mapOf("taxi-home" to bundledMarkdown))
        store.save("taxi-home", markdown)
        val h = Harness(users = FakeUsers("继续"), userSkillStore = store)
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_DELETE, args("name" to "taxi-home"))).reply("已取消")

        h.agent.handle("继续删除技能", h.events, h.stop)

        assertEquals(false, toolResult(h)["deleted"]!!.jsonPrimitive.boolean)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun cancelWritesNothing() = runTest {
        val store = FakeUserSkillStore()
        val h = Harness(users = FakeUsers("取消"), userSkillStore = store)
        store.exposeTo(h)
        h.chat.tools(h.saveCall()).reply("好的，已取消")

        h.agent.handle("帮我新建一个打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(false, result["saved"]!!.jsonPrimitive.boolean)
        assertEquals("用户取消", result["reason"]!!.jsonPrimitive.content)
        assertTrue(store.saved.isEmpty())
        assertEquals(0, h.skills.reloadCount)
    }

    @Test
    fun invalidSkillMdReturnsProblemsWithoutAsking() = runTest {
        val store = FakeUserSkillStore()
        store.validateProblems = listOf(SkillProblem("taxi-home/SKILL.md", "unknown channel 'nope'", 4))
        val h = Harness(users = FakeUsers("确认"), userSkillStore = store)
        store.exposeTo(h)
        h.chat.tools(h.saveCall()).reply("好")

        h.agent.handle("新建技能", h.events, h.stop)

        val result = toolResult(h)
        assertTrue(result["error"] != null)
        assertEquals(1, result["problems"]!!.jsonArray.size)
        assertTrue("unknown channel" in result["problems"]!!.jsonArray[0].jsonObject["message"]!!.jsonPrimitive.content)
        assertTrue(h.users.asked.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertEquals(0, h.skills.reloadCount)
    }

    @Test
    fun invalidNameIsAParameterErrorAndNeverAsks() = runTest {
        val store = FakeUserSkillStore()
        val h = Harness(users = FakeUsers("确认"), userSkillStore = store)
        h.chat.tools(h.saveCall(name = "x")).reply("好")

        h.agent.handle("新建技能", h.events, h.stop)

        val result = toolResult(h)
        assertTrue(result["error"]!!.jsonPrimitive.content.startsWith("参数错误"))
        assertTrue(h.users.asked.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun deletingBundledOnlySkillIsRejected() = runTest {
        val store = FakeUserSkillStore(bundled = mapOf("taxi-home" to bundledMarkdown))
        val h = Harness(users = FakeUsers("确认"), userSkillStore = store)
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_DELETE, args("name" to "taxi-home"))).reply("好")

        h.agent.handle("删除打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(
            "内置技能不能删除，可以用 skill_save 写一份同名技能覆盖它",
            result["error"]!!.jsonPrimitive.content,
        )
        assertTrue(h.users.asked.isEmpty())
        assertTrue(store.deleted.isEmpty())
        assertEquals(0, h.skills.reloadCount)
    }

    @Test
    fun deletingUserSkillReloadsAndRestoresBundledOne() = runTest {
        val store = FakeUserSkillStore(bundled = mapOf("taxi-home" to bundledMarkdown))
        store.save("taxi-home", markdown)
        val bundled = Skill("taxi-home", "内置打车", instructions = "步骤", source = "/bundled/taxi-home")
        val h = Harness(
            users = FakeUsers("确认"),
            skills = FakeSkills(bundled),
            userSkillStore = store,
        )
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_DELETE, args("name" to "taxi-home"))).reply("删好了")

        h.agent.handle("删除打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(true, result["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(true, result["restoredBundled"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("taxi-home"), store.deleted)
        assertEquals(1, h.skills.reloadCount)
    }

    @Test
    fun deletingUserSkillCanBeCancelled() = runTest {
        val store = FakeUserSkillStore(bundled = mapOf("taxi-home" to bundledMarkdown))
        store.save("taxi-home", markdown)
        val h = Harness(users = FakeUsers("再想想"), userSkillStore = store)
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_DELETE, args("name" to "taxi-home"))).reply("已取消")

        h.agent.handle("删除打车技能", h.events, h.stop)

        val result = toolResult(h)
        assertEquals(false, result["deleted"]!!.jsonPrimitive.boolean)
        assertEquals("用户取消", result["reason"]!!.jsonPrimitive.content)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun skillReadPrefersUserVersionAndReportsOrigin() = runTest {
        val store = FakeUserSkillStore(bundled = mapOf("set-alarm" to bundledMarkdown))
        store.save("taxi-home", markdown)
        val h = Harness(userSkillStore = store)

        h.chat.tools(ToolCall("c1", AgentTools.SKILL_READ, args("name" to "taxi-home"))).reply("好")
        h.agent.handle("读一下打车技能", h.events, h.stop)
        val user = toolResult(h)
        assertEquals(true, user["found"]!!.jsonPrimitive.boolean)
        assertEquals("user", user["origin"]!!.jsonPrimitive.content)
        assertEquals(markdown, user["skill_md"]!!.jsonPrimitive.content)
        assertEquals(false, user["hasFlow"]!!.jsonPrimitive.boolean)

        h.chat.tools(ToolCall("c2", AgentTools.SKILL_READ, args("name" to "set-alarm"))).reply("好")
        h.agent.handle("再看闹钟技能", h.events, h.stop)
        assertEquals("bundled", toolResult(h)["origin"]!!.jsonPrimitive.content)

        h.chat.tools(ToolCall("c3", AgentTools.SKILL_READ, args("name" to "missing"))).reply("好")
        h.agent.handle("还有别的吗", h.events, h.stop)
        assertEquals(false, toolResult(h)["found"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun authoringToolsAreHiddenWithoutAStore() = runTest {
        val h = Harness()
        h.chat.reply("好")
        h.agent.handle("你好", h.events, h.stop)

        val request = h.chat.requests.first()
        assertEquals(AgentTools.specs.map { it.name }, request.tools.map { it.name })
        assertTrue(request.tools.none { it.name in AgentTools.skillAuthoringNames })
        assertFalse(Prompts.SKILL_AUTHORING_HINT in request.messages.first().text)
    }

    @Test
    fun authoringToolsAndHintAreOfferedWithAStore() = runTest {
        val h = Harness(userSkillStore = FakeUserSkillStore())
        h.chat.reply("好")
        h.agent.handle("你好", h.events, h.stop)

        val request = h.chat.requests.first()
        val names = request.tools.map { it.name }
        AgentTools.skillAuthoringNames.forEach { assertTrue(it in names) }
        assertTrue(Prompts.SKILL_AUTHORING_HINT in request.messages.first().text)
    }
}
