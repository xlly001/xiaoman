package dev.xiaoman.eval

import dev.xiaoman.api.Action
import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.AppInfo
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.AuditSink
import dev.xiaoman.api.Bounds
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.Decision
import dev.xiaoman.api.Device
import dev.xiaoman.api.GuardContext
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.ReminderScheduler
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.SearchHit
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillLoadResult
import dev.xiaoman.api.SkillOrigin
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.StopSignal
import dev.xiaoman.api.StoredSkill
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.ToolProvider
import dev.xiaoman.api.ToolResult
import dev.xiaoman.api.ToolSpec
import dev.xiaoman.api.UiNode
import dev.xiaoman.api.UserChannel
import dev.xiaoman.api.UserSkillStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Every deterministic input in an eval scenario; keeps the suite reproducible across runs and machines. */
const val EVAL_CLOCK: Long = 1_700_000_000_000L

/** One scripted model reply. An exhausted script answers with empty text (never a random reply). */
sealed class LlmStep {
    abstract fun respond(request: ChatRequest): ChatResponse

    data class Text(val text: String) : LlmStep() {
        override fun respond(request: ChatRequest) = ChatResponse(text)
    }

    data class Calls(val calls: List<ToolCall>) : LlmStep() {
        override fun respond(request: ChatRequest) = ChatResponse("", calls)
    }

    data class Failure(val message: String, val retryable: Boolean = false) : LlmStep() {
        override fun respond(request: ChatRequest): ChatResponse =
            throw LlmException(message, statusCode = 503, retryable = retryable)
    }

    class Dynamic(val block: (ChatRequest) -> ChatResponse) : LlmStep() {
        override fun respond(request: ChatRequest) = block(request)
    }
}

/** An [LlmClient] fed by a script; optionally reports the reply text in two-character chunks. */
class EvalLlm(
    private val role: ModelRole,
    private val steps: List<LlmStep>,
    private val streaming: Boolean = false,
) : LlmClient {
    override val config = ModelConfig(role, "http://eval.invalid", "eval-model", "no-key")
    val requests = mutableListOf<ChatRequest>()
    private var cursor = 0

    private fun next(request: ChatRequest): ChatResponse {
        requests += request
        return steps.getOrNull(cursor++)?.respond(request) ?: ChatResponse("")
    }

    override suspend fun complete(request: ChatRequest): ChatResponse = next(request)

    override suspend fun completeStreaming(
        request: ChatRequest,
        shouldStop: () -> Boolean,
        onText: (String) -> Unit,
    ): ChatResponse {
        val response = next(request)
        if (streaming) {
            for (chunk in response.text.chunked(2)) {
                if (shouldStop()) throw CancellationException("stopped")
                onText(chunk)
            }
        }
        return response
    }
}

/** Screen fixtures. Nodes carry ids so operator scripts can refer to them. */
object Screens {
    fun node(
        id: Int,
        text: String,
        password: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        className = "android.widget.TextView",
        bounds = Bounds(0, id * 100, 1000, id * 100 + 90),
        clickable = true,
        editable = editable,
        focused = focused,
        password = password,
    )

    fun screen(packageName: String, vararg texts: String, secure: Boolean = false): ScreenState =
        screenWith(packageName, secure, *texts.mapIndexed { i, t -> node(i, t) }.toTypedArray())

    fun screenWith(packageName: String, secure: Boolean = false, vararg nodes: UiNode): ScreenState = ScreenState(
        screenshotPng = if (secure) null else byteArrayOf(1, 2, 3),
        widthPx = 1080,
        heightPx = 2400,
        packageName = packageName,
        nodes = nodes.toList(),
        secure = secure,
        capturedAtMillis = EVAL_CLOCK,
    )

    fun home() = screen("com.android.launcher", "设置", "相册")
}

/** Device whose observe() walks a screen queue; the last screen repeats (needed by approval checks). */
class EvalDevice(screens: List<ScreenState>) : Device {
    private val queue = ArrayDeque(screens.ifEmpty { listOf(Screens.home()) })
    val performed = mutableListOf<Action>()
    var result: (Action) -> ActionResult = { ActionResult(true) }
    var observations = 0
        private set

    override suspend fun observe(): ScreenState {
        observations++
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun perform(action: Action): ActionResult {
        performed += action
        return result(action)
    }

    override suspend fun installedApps() = listOf(AppInfo("com.android.settings", "设置"))
}

/** Chinese-friendly in-memory memory store (bigram overlap scoring). */
class EvalMemory(initial: List<Note> = emptyList()) : MemoryStore {
    val notes = LinkedHashMap<String, Note>().apply { initial.forEach { put(it.permalink, it) } }

    override suspend fun write(note: Note): Note = note.also { notes[it.permalink] = it }
    override suspend fun read(identifier: String): Note? =
        notes[identifier] ?: notes.values.firstOrNull { it.title == identifier }

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val grams = query.windowed(2).toSet().ifEmpty { setOf(query) }
        return notes.values.mapNotNull { n ->
            val text = n.title + "\n" + n.body + n.observations.joinToString("\n") { it.content }
            val score = grams.count { it in text }
            if (score > 0) SearchHit(n.permalink, n.title, n.observations.firstOrNull()?.content ?: n.body.take(40), score.toDouble()) else null
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun appendObservation(identifier: String, observation: Observation): Note {
        val n = read(identifier) ?: Note(title = identifier, permalink = identifier)
        return write(n.copy(observations = n.observations + observation))
    }

    override suspend fun delete(identifier: String): Boolean = read(identifier)?.let { notes.remove(it.permalink) } != null
    override suspend fun list(folder: String?): List<Note> =
        notes.values.filter { folder == null || it.permalink.startsWith("$folder/") }
}

class EvalSkills(initial: List<Skill> = emptyList()) : SkillRepository {
    private val all = initial.toMutableList()
    var reloads = 0
        private set

    fun add(skill: Skill) {
        all.removeAll { it.name == skill.name }
        all.add(skill)
    }

    fun remove(name: String) {
        all.removeAll { it.name == name }
    }

    override suspend fun reload(): SkillLoadResult {
        reloads++
        return SkillLoadResult(all.toList(), emptyList())
    }

    override suspend fun list() = all.toList()
    override suspend fun get(name: String) = all.firstOrNull { it.name == name }
}

/**
 * Minimal [UserSkillStore] that mirrors a save into [EvalSkills], because `skill_save` verifies the
 * reloaded skill by name and source folder before reporting success.
 */
class EvalUserSkillStore(
    private val skills: EvalSkills,
    private val root: File = File("build/eval-skills"),
) : UserSkillStore {
    private val saved = LinkedHashMap<String, String>()
    val savedNames = mutableListOf<String>()
    val deletedNames = mutableListOf<String>()

    override fun read(name: String): StoredSkill? = saved[name]?.let { StoredSkill(name, it, SkillOrigin.USER, false) }

    override fun save(name: String, skillMd: String): File {
        saved[name] = skillMd
        savedNames += name
        val folder = File(root, name)
        skills.add(Skill(name = name, description = "评测技能 $name", instructions = skillMd, source = folder.path))
        return File(folder, "SKILL.md")
    }

    override fun delete(name: String): Boolean {
        if (saved.remove(name) == null) return false
        deletedNames += name
        skills.remove(name)
        return true
    }

    override fun isUser(name: String) = name in saved
    override fun isBundled(name: String) = false

    override fun validate(name: String, skillMd: String): Pair<Skill?, List<SkillProblem>> =
        Skill(name = name, description = "评测技能 $name", instructions = skillMd, source = File(root, name).path) to emptyList()
}

class EvalApprovals(var answer: (ApprovalRequest) -> ApprovalAnswer = { ApprovalAnswer(false) }) : ApprovalGateway {
    val requests = mutableListOf<ApprovalRequest>()
    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        requests += request
        return answer(request)
    }
}

class EvalAudit : AuditSink {
    val records = mutableListOf<AuditRecord>()
    override fun record(record: AuditRecord) {
        records += record
    }
}

/** Non-safety scenarios use a permissive sentinel; safety scenarios must opt into the real policy. */
class AllowAllSentinel : Sentinel {
    val checked = mutableListOf<Action>()
    override suspend fun guard(context: GuardContext, action: Action): Decision {
        checked += action
        return Decision.Allow()
    }

    override suspend fun authorize(context: GuardContext, action: Action) = guard(context, action) is Decision.Allow
}

class EvalUsers(private val answers: List<String> = emptyList()) : UserChannel {
    val asked = mutableListOf<Pair<String, Boolean>>()
    private var cursor = 0
    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        asked += question to takeover
        return answers.getOrNull(cursor++) ?: "好"
    }
}

class EvalReminders : ReminderScheduler {
    val scheduled = mutableListOf<Pair<String, Long>>()
    override fun schedule(text: String, atMillis: Long) {
        scheduled += text to atMillis
    }
}

class EvalTools(private val results: Map<String, ToolResult> = emptyMap()) : ToolProvider {
    val calls = mutableListOf<Triple<String, String, String>>()
    override suspend fun specs(): List<ToolSpec> =
        results.keys.map { ToolSpec(it, "评测工具 $it", JsonObject(emptyMap())) }

    override suspend fun call(taskId: String, name: String, argumentsJson: String): ToolResult? {
        calls += Triple(taskId, name, argumentsJson)
        return results[name]
    }
}

class EvalStop(var value: Boolean = false) : StopSignal {
    override val stopped get() = value
}

class EvalEvents : AgentListener {
    val all = mutableListOf<AgentEvent>()
    override fun onEvent(event: AgentEvent) {
        all += event
    }

    inline fun <reified T : AgentEvent> of(): List<T> = all.filterIsInstance<T>()
}
