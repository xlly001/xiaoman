package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Relation
import dev.xiaoman.api.ReminderScheduler
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.ToolProvider
import dev.xiaoman.api.UserChannel
import dev.xiaoman.api.UserSkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class ToolArgumentException(message: String) : Exception(message)

/** Runs the chat model's tool calls. Argument and runtime errors become `{"error": ...}` tool results. */
internal class ChatTools(
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    private val clock: () -> Long,
    private val reminders: ReminderScheduler,
    private val external: ToolProvider?,
    private val phoneTasks: Boolean = true,
    private val userSkillStore: UserSkillStore? = null,
    private val users: UserChannel? = null,
    private val phoneTask: suspend (goal: String, skill: String?) -> AgentEvent.PhoneTaskFinished,
) {
    suspend fun execute(call: ToolCall, taskId: String, emit: (AgentEvent) -> Unit): String = try {
        val args = parseArgs(call.argumentsJson)
        when (call.name) {
            AgentTools.MEMORY_SEARCH -> search(args)
            AgentTools.MEMORY_READ -> read(args)
            AgentTools.MEMORY_WRITE -> write(args, taskId, emit)
            AgentTools.MEMORY_FORGET -> forget(args, taskId, emit)
            AgentTools.LIST_SKILLS -> listSkills()
            AgentTools.SKILL_READ -> if (userSkillStore != null) readSkill(args) else external(call, taskId, emit)
            AgentTools.SKILL_SAVE -> if (userSkillStore != null) saveSkill(args, taskId) else external(call, taskId, emit)
            AgentTools.SKILL_DELETE -> if (userSkillStore != null) deleteSkill(args, taskId) else external(call, taskId, emit)
            AgentTools.REMINDER_SET -> reminder(args)
            AgentTools.PHONE_TASK -> if (phoneTasks) phone(args) else error("这里不能操作手机，请用其他工具或告诉用户自己操作")
            else -> external(call, taskId, emit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolArgumentException) {
        error("参数错误：${e.message}")
    } catch (e: Exception) {
        error("工具执行失败：${e.message ?: e::class.simpleName}")
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    /** The store rejects bad skill names (and path traversal) with IllegalArgumentException; surface them as parameter errors. */
    private fun <T> withValidName(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ToolArgumentException(e.message ?: "技能名不合法")
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return try {
            JsonText.json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ToolArgumentException("arguments 不是合法的 JSON 对象")
    }

    private fun required(o: JsonObject, key: String): String =
        JsonText.string(o, key) ?: throw ToolArgumentException("缺少 $key")

    private suspend fun search(args: JsonObject): String {
        val query = required(args, "query")
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 20)
        val hits = memory.search(query, limit)
        return buildJsonObject {
            putJsonArray("hits") {
                hits.forEach { h -> addJsonObject { put("permalink", h.permalink); put("title", h.title); put("snippet", h.snippet) } }
            }
        }.toString()
    }

    private suspend fun read(args: JsonObject): String {
        val note = memory.read(required(args, "identifier")) ?: return buildJsonObject { put("found", false) }.toString()
        return buildJsonObject {
            put("found", true)
            put("title", note.title)
            put("permalink", note.permalink)
            put("type", note.type)
            putJsonArray("tags") { note.tags.forEach { add(it) } }
            putJsonArray("observations") { note.observations.forEach { add(NoteText.observationLine(it)) } }
            putJsonArray("relations") { note.relations.forEach { add(NoteText.relationLine(it)) } }
            put("body", note.body)
        }.toString()
    }

    private suspend fun write(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val title = required(args, "title")
        val observations = objects(args, "observations").map {
            Observation(required(it, "category"), required(it, "content"), JsonText.stringList(it, "tags"), JsonText.string(it, "context"))
        }
        val relations = objects(args, "relations").map {
            Relation(required(it, "type"), required(it, "target"), JsonText.string(it, "context"))
        }
        if (observations.isEmpty() && relations.isEmpty()) throw ToolArgumentException("observations 不能为空")

        val existing = memory.read(JsonText.string(args, "permalink") ?: title)
        val permalink = JsonText.string(args, "permalink") ?: existing?.permalink ?: title
        val mergedObs = ((existing?.observations ?: emptyList()) + observations).distinctBy { it.category to it.content }
        val mergedRels = ((existing?.relations ?: emptyList()) + relations).distinctBy { it.type to it.target }
        val note = Note(
            title = title,
            permalink = permalink,
            type = JsonText.string(args, "type") ?: existing?.type ?: "note",
            tags = existing?.tags ?: emptyList(),
            extraFrontmatter = existing?.extraFrontmatter ?: emptyMap(),
            body = NoteText.body(title, mergedObs, mergedRels, existing?.body?.let { NoteText.preamble(it) } ?: ""),
            observations = mergedObs,
            relations = mergedRels,
            updatedAtMillis = clock(),
        )
        val stored = memory.write(note)
        emit(AgentEvent.MemoryUpdated(taskId, stored.permalink))
        return buildJsonObject {
            put("ok", true)
            put("permalink", stored.permalink)
            put("created", existing == null)
            put("observations", mergedObs.size)
        }.toString()
    }

    private fun objects(args: JsonObject, key: String): List<JsonObject> = when (val e = args[key]) {
        null -> emptyList()
        is JsonArray -> e.map { it as? JsonObject ?: throw ToolArgumentException("$key 的每一项必须是对象") }
        is JsonObject -> listOf(e)
        else -> if (e is JsonPrimitive && e.content == "null") emptyList() else throw ToolArgumentException("$key 必须是数组")
    }

    private suspend fun forget(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val identifier = required(args, "identifier")
        val permalink = memory.read(identifier)?.permalink ?: identifier
        val deleted = memory.delete(identifier)
        if (deleted) emit(AgentEvent.MemoryUpdated(taskId, permalink))
        return buildJsonObject { put("deleted", deleted) }.toString()
    }

    private suspend fun listSkills(): String {
        val list = skills.list()
        return listSkillsJson(list)
    }

    private fun listSkillsJson(list: List<Skill>): String = buildJsonObject {
        putJsonArray("skills") {
            list.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("channel", s.channel.wire)
                    put("apps", buildJsonArray { s.apps.forEach { add(it) } })
                    put("hasFlow", s.flow != null)
                }
            }
        }
    }.toString()

    private suspend fun readSkill(args: JsonObject): String = withContext(Dispatchers.IO) {
        val name = required(args, "name")
        val stored = withValidName { userSkillStore!!.read(name) }
        buildJsonObject {
            put("found", stored != null)
            put("name", name)
            if (stored != null) {
                put("origin", stored.origin.name.lowercase())
                put("skill_md", stored.skillMd)
                put("hasFlow", stored.hasFlow)
            }
        }.toString()
    }

    private suspend fun confirmed(taskId: String, question: String): Boolean {
        val channel = users ?: throw IllegalStateException("缺少用户确认通道")
        return channel.ask(taskId, question, takeover = false).trim().lowercase() in
            setOf("确认", "是", "好", "可以", "y", "yes")
    }

    private suspend fun saveSkill(args: JsonObject, taskId: String): String {
        val store = userSkillStore!!
        val name = required(args, "name")
        val markdown = required(args, "skill_md")
        val (skill, problems) = withContext(Dispatchers.IO) { withValidName { store.validate(name, markdown) } }
        if (problems.isNotEmpty() || skill == null) return buildJsonObject {
            put("error", "SKILL.md 校验失败")
            putJsonArray("problems") {
                problems.forEach { problem -> addJsonObject {
                    put("source", problem.source)
                    put("message", problem.message)
                    problem.line?.let { put("line", it) }
                } }
            }
        }.toString()
        val (created, overridesBundled) = withContext(Dispatchers.IO) {
            withValidName { !store.isUser(name) to store.isBundled(name) }
        }
        val draft = if (markdown.length > 3000) markdown.take(3000) + "\n[SKILL.md 超过 3000 字，展示已截断]" else markdown
        val question = buildString {
            append(if (created) "新建" else "修改").append("技能「").append(name).append("」。\n")
            if (overridesBundled) append("保存后将覆盖同名内置技能，内置文件保留。\n")
            append("description：").append(skill.description).append("\nchannel：").append(skill.channel.wire)
            append("\n\nSKILL.md：\n").append(draft)
            append("\n\n回复“确认”保存，其他回复取消。")
        }
        if (!confirmed(taskId, question)) return buildJsonObject {
            put("saved", false); put("reason", "用户取消")
        }.toString()
        val path = withContext(Dispatchers.IO) { withValidName { store.save(name, markdown) } }
        val loaded = skills.reload()
        if (loaded.skills.none { it.name == name && it.source == path.parentFile.path }) {
            return buildJsonObject {
                put("error", "文件已写入，但重新加载后用户技能不可用，请检查 SKILL.md 和已有 flow.yaml")
                putJsonArray("problems") { loaded.problems.forEach { add(it.message) } }
            }.toString()
        }
        return buildJsonObject {
            put("saved", true); put("created", created); put("overridesBundled", overridesBundled); put("path", path.path)
        }.toString()
    }

    private suspend fun deleteSkill(args: JsonObject, taskId: String): String {
        val store = userSkillStore!!
        val name = required(args, "name")
        val (isUser, isBundled) = withContext(Dispatchers.IO) {
            withValidName { store.isUser(name) to store.isBundled(name) }
        }
        if (!isUser) return error(if (isBundled) "内置技能不能删除，可以用 skill_save 写一份同名技能覆盖它" else "技能不存在：$name")
        val question = "删除用户技能「$name」及其文件夹。" +
            (if (isBundled) "删除后将恢复同名内置技能。" else "删除后该技能将不再可用。") +
            "回复“确认”删除，其他回复取消。"
        if (!confirmed(taskId, question)) return buildJsonObject {
            put("deleted", false); put("reason", "用户取消")
        }.toString()
        val deleted = withContext(Dispatchers.IO) { withValidName { store.delete(name) } }
        val loaded = skills.reload()
        val restoredBundled = isBundled && loaded.skills.any { it.name == name }
        return buildJsonObject { put("deleted", deleted); put("restoredBundled", restoredBundled) }.toString()
    }

    private fun reminder(args: JsonObject): String {
        val text = required(args, "text")
        val raw = required(args, "at")
        val atMillis = ReminderTimes.parseAtMillis(raw)
            ?: throw ToolArgumentException("at 不是合法的 ISO-8601 本地时间，应为 2026-11-03T08:00 这样的形式")
        if (atMillis <= clock()) throw ToolArgumentException("提醒时间 $raw 已经过去，请向用户确认一个将来的时间")
        reminders.schedule(text, atMillis)
        return buildJsonObject {
            put("ok", true)
            put("text", text)
            put("at", raw)
        }.toString()
    }

    private suspend fun external(call: ToolCall, taskId: String, emit: (AgentEvent) -> Unit): String {
        val result = external?.call(taskId, call.name, call.argumentsJson) ?: return error("未知工具 ${call.name}")
        emit(AgentEvent.ToolCalled(taskId, call.name, result.ok, result.summary))
        return result.text
    }

    private suspend fun phone(args: JsonObject): String {
        val finished = phoneTask(required(args, "goal"), JsonText.string(args, "skill"))
        return buildJsonObject {
            put("success", finished.success)
            put("summary", finished.summary)
            put("steps", finished.steps)
        }.toString()
    }
}
