package dev.xiaoman.eval

import dev.xiaoman.api.Action
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.Note
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.Skill
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass

/** Groups scenarios in the report; safety scenarios are the release gate. */
enum class EvalCategory { CHAT, MEMORY, SKILL, TASK, SAFETY }

/** A named assertion. Returning null means pass; any string is a human-readable failure reason. */
data class Expectation(val description: String, val check: (EvalTrace) -> String?)

/** Everything an expectation is allowed to observe after one deterministic agent run. */
data class EvalTrace(
    val events: List<AgentEvent>,
    val performed: List<Action>,
    val approvals: List<ApprovalRequest>,
    val audit: List<AuditRecord>,
    val chatRequests: List<ChatRequest>,
    val operatorRequests: List<ChatRequest>,
    val notes: Map<String, Note>,
    val userQuestions: List<Pair<String, Boolean>>,
    val toolCalls: List<Triple<String, String, String>>,
    val savedSkills: List<String>,
    val finished: AgentEvent.PhoneTaskFinished?,
    val error: Throwable?,
) {
    inline fun <reified T : AgentEvent> of(): List<T> = events.filterIsInstance<T>()

    val kinds: List<String> get() = events.map { it::class.simpleName ?: "?" }
    fun systemPrompt(): String = chatRequests.firstOrNull()?.messages?.firstOrNull()?.text.orEmpty()
}

/** Immutable inputs of one scenario. */
data class ScenarioSpec(
    val userMessage: String? = null,
    val phoneGoal: String? = null,
    val phoneSkill: String? = null,
    val chat: List<LlmStep> = emptyList(),
    val operator: List<LlmStep> = emptyList(),
    val streaming: Boolean = false,
    val screens: List<ScreenState> = emptyList(),
    val notes: List<Note> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val userAnswers: List<String> = emptyList(),
    val approval: (ApprovalRequest) -> ApprovalAnswer = { ApprovalAnswer(false) },
    val realSentinel: Boolean = false,
    val skillAuthoring: Boolean = false,
    val tools: Map<String, ToolResult> = emptyMap(),
    val maxSteps: Int = 8,
    val stopImmediately: Boolean = false,
)

data class Scenario(
    val name: String,
    val category: EvalCategory,
    val spec: ScenarioSpec,
    val expectations: List<Expectation>,
)

/** Script for the chat model: plain replies, tool calls, or failures, in order. */
class LlmScript {
    val steps = mutableListOf<LlmStep>()

    fun reply(text: String) {
        steps += LlmStep.Text(text)
    }

    fun toolCall(name: String, argumentsJson: String = "{}", id: String? = null) {
        steps += LlmStep.Calls(listOf(ToolCall(id ?: "call-${steps.size + 1}", name, argumentsJson)))
    }

    fun toolCalls(vararg calls: ToolCall) {
        steps += LlmStep.Calls(calls.toList())
    }

    fun fail(message: String, retryable: Boolean = false) {
        steps += LlmStep.Failure(message, retryable)
    }

    fun dynamic(block: (ChatRequest) -> ChatResponse) {
        steps += LlmStep.Dynamic(block)
    }
}

/** Script for the operator model, expressed as actions rather than raw JSON. */
class OperatorScript {
    val steps = mutableListOf<LlmStep>()

    private fun quote(text: String) = JsonPrimitive(text).toString()

    private fun action(json: String) {
        steps += LlmStep.Text("""{"thought":"eval","action":$json}""")
    }

    fun raw(actionJson: String) = action(actionJson)

    fun tap(nodeId: Int, target: String? = null) =
        action("""{"type":"tap","nodeId":$nodeId${target?.let { ",\"target\":${quote(it)}" } ?: ""}}""")

    fun tapPoint(x: Int, y: Int, target: String? = null) =
        action("""{"type":"tap","point":[$x,$y]${target?.let { ",\"target\":${quote(it)}" } ?: ""}}""")

    fun type(text: String, nodeId: Int? = null, submit: Boolean = false, target: String? = null) =
        action("""{"type":"type","text":${quote(text)},"submit":$submit${nodeId?.let { ",\"nodeId\":$it" } ?: ""}${target?.let { ",\"target\":${quote(it)}" } ?: ""}}""")

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) =
        action("""{"type":"swipe","from":[$fromX,$fromY],"to":[$toX,$toY]}""")

    fun launch(packageName: String) = action("""{"type":"launch","packageName":${quote(packageName)}}""")

    fun waitFor(millis: Long = 1000) = action("""{"type":"wait","millis":$millis}""")

    fun back() = action("""{"type":"back"}""")

    fun askUser(question: String, takeover: Boolean = false) =
        action("""{"type":"ask_user","question":${quote(question)},"takeover":$takeover}""")

    fun finish(success: Boolean = true, summary: String = "完成") =
        action("""{"type":"finish","success":$success,"summary":${quote(summary)}}""")
}

/** Declarative scenario builder: given* set up inputs, expect* assert the resulting trace. */
class ScenarioBuilder(private val name: String) {
    var category: EvalCategory = EvalCategory.CHAT
    private var userMessage: String? = null
    private var phoneGoal: String? = null
    private var phoneSkill: String? = null
    private var chat: List<LlmStep> = emptyList()
    private var operator: List<LlmStep> = emptyList()
    private var streaming = false
    private val screens = mutableListOf<ScreenState>()
    private val notes = mutableListOf<Note>()
    private val skills = mutableListOf<Skill>()
    private var userAnswers: List<String> = emptyList()
    private var approval: (ApprovalRequest) -> ApprovalAnswer = { ApprovalAnswer(false) }
    private var realSentinel = false
    private var skillAuthoring = false
    private val tools = linkedMapOf<String, ToolResult>()
    private var maxSteps = 8
    private var stopImmediately = false
    private val expectations = mutableListOf<Expectation>()

    fun givenUser(message: String) {
        userMessage = message
    }

    fun givenPhoneTask(goal: String, skill: String? = null) {
        phoneGoal = goal
        phoneSkill = skill
    }

    fun givenScreen(screen: ScreenState) {
        screens += screen
    }

    fun givenScreens(vararg items: ScreenState) {
        screens += items
    }

    fun givenNote(note: Note) {
        notes += note
    }

    fun givenSkill(skill: Skill) {
        skills += skill
    }

    fun chatScript(block: LlmScript.() -> Unit) {
        chat = LlmScript().apply(block).steps.toList()
    }

    fun operatorScript(block: OperatorScript.() -> Unit) {
        operator = OperatorScript().apply(block).steps.toList()
    }

    fun streamingChat() {
        streaming = true
    }

    fun userAnswers(vararg answers: String) {
        userAnswers = answers.toList()
    }

    fun approve(approved: Boolean, scope: ApprovalScope = ApprovalScope.ONCE) {
        approval = { ApprovalAnswer(approved, scope) }
    }

    fun approveWith(block: (ApprovalRequest) -> ApprovalAnswer) {
        approval = block
    }

    /** Opt into the real PolicySentinel (required for every safety scenario). */
    fun useRealSentinel() {
        realSentinel = true
    }

    /** Offer skill_read/skill_save/skill_delete to the chat model. */
    fun useSkillAuthoring() {
        skillAuthoring = true
    }

    fun externalTool(name: String, ok: Boolean = true, text: String = "{}", summary: String = "评测工具已执行") {
        tools[name] = ToolResult(ok, text, summary)
    }

    fun maxSteps(count: Int) {
        maxSteps = count
    }

    fun stopImmediately() {
        stopImmediately = true
    }

    fun expect(description: String, check: (EvalTrace) -> String?) {
        expectations += Expectation(description, check)
    }

    inline fun <reified T : AgentEvent> expectEvent(
        description: String = "出现 ${T::class.simpleName}",
        count: Int? = null,
        crossinline predicate: (T) -> Boolean = { true },
    ) {
        expect(description) { trace ->
            val found = trace.of<T>().filter(predicate)
            when {
                found.isEmpty() -> "没有出现 ${T::class.simpleName}；实际事件：${trace.kinds.joinToString(" → ")}"
                count != null && found.size != count -> "${T::class.simpleName} 数量应为 $count，实际 ${found.size}"
                else -> null
            }
        }
    }

    inline fun <reified T : AgentEvent> expectNoEvent(description: String = "不出现 ${T::class.simpleName}") {
        expect(description) { trace ->
            val found = trace.of<T>()
            if (found.isEmpty()) null else "不应出现 ${T::class.simpleName}，实际 ${found.size} 条：${found.first()}"
        }
    }

    fun expectReplyContains(text: String) = expect("最终回复包含「$text」") { trace ->
        val replies = trace.of<AgentEvent.Reply>()
        if (replies.any { text in it.text }) null else "回复中找不到「$text」；实际：${replies.map { it.text }}"
    }

    fun expectFinalReply(text: String) = expect("最终回复等于「$text」") { trace ->
        val last = trace.of<AgentEvent.Reply>().lastOrNull()
        if (last?.text == text) null else "最终回复应为「$text」，实际：${last?.text ?: "<无回复>"}"
    }

    fun expectReplyCount(count: Int) = expect("Reply 恰好 $count 条") { trace ->
        val n = trace.of<AgentEvent.Reply>().size
        if (n == count) null else "Reply 应为 $count 条，实际 $n 条"
    }

    fun expectDeltaCount(count: Int) = expect("ReplyDelta 恰好 $count 条") { trace ->
        val n = trace.of<AgentEvent.ReplyDelta>().size
        if (n == count) null else "ReplyDelta 应为 $count 条，实际 $n 条"
    }

    fun expectNoReply() = expectNoEvent<AgentEvent.Reply>("不产生最终回复")

    fun expectFailed(contains: String? = null) = expect("上报 Failed") { trace ->
        val found = trace.of<AgentEvent.Failed>()
        when {
            found.isEmpty() -> "没有 Failed 事件"
            contains != null && found.none { contains in it.message } -> "Failed 消息不含「$contains」：${found.map { it.message }}"
            else -> null
        }
    }

    fun expectNoFailure() = expect("运行过程不抛异常") { trace ->
        trace.error?.let { "运行抛出异常：${it::class.simpleName}: ${it.message}" }
    }

    fun expectApprovalRequested(contains: String) = expect("请求审批且提示含「$contains」") { trace ->
        val hit = trace.approvals.firstOrNull { contains in it.prompt || contains in it.reason }
        if (hit != null) null else "没有匹配的审批请求；实际：${trace.approvals.map { it.prompt + " / " + it.reason }}"
    }

    fun expectNoApproval() = expect("不请求审批") { trace ->
        if (trace.approvals.isEmpty()) null else "不应请求审批，实际 ${trace.approvals.size} 次"
    }

    fun expectActionPerformed(description: String, predicate: (Action) -> Boolean) = expect(description) { trace ->
        if (trace.performed.any(predicate)) null else "没有执行预期动作；实际执行：${trace.performed}"
    }

    fun expectNoActionPerformed(description: String = "不执行任何设备动作", predicate: (Action) -> Boolean = { true }) =
        expect(description) { trace ->
            val hit = trace.performed.filter(predicate)
            if (hit.isEmpty()) null else "不应执行这些动作：${hit}"
        }

    fun expectTaskFinished(success: Boolean, steps: Int? = null) = expect("任务结束 success=$success") { trace ->
        val f = trace.finished
        when {
            f == null -> "没有 PhoneTaskFinished 事件"
            f.success != success -> "任务 success 应为 $success，实际 ${f.success}（${f.summary}）"
            steps != null && f.steps != steps -> "任务步数应为 $steps，实际 ${f.steps}"
            else -> null
        }
    }

    fun expectMemoryNote(permalink: String) = expect("记忆存在 $permalink") { trace ->
        if (permalink in trace.notes) null else "找不到笔记 $permalink；现有：${trace.notes.keys}"
    }

    fun expectNoMemoryNote(permalink: String) = expect("记忆已删除 $permalink") { trace ->
        if (permalink !in trace.notes) null else "笔记 $permalink 仍然存在"
    }

    fun expectSystemPromptContains(text: String) = expect("系统提示词包含「$text」") { trace ->
        if (text in trace.systemPrompt()) null else "系统提示词不含「$text」"
    }

    fun expectEventSequence(vararg types: KClass<out AgentEvent>) = expect(
        "事件顺序 " + types.joinToString(" → ") { it.simpleName ?: "?" },
    ) { trace ->
        var matched = 0
        for (event in trace.events) {
            if (matched < types.size && types[matched].isInstance(event)) matched++
        }
        if (matched == types.size) null else "只按顺序匹配到 $matched/${types.size} 个事件；实际：${trace.kinds.joinToString(" → ")}"
    }

    fun expectToolCalled(name: String) = expect("外部工具 $name 被调用") { trace ->
        if (trace.toolCalls.any { it.second == name }) null else "工具 $name 未被调用；实际：${trace.toolCalls.map { it.second }}"
    }

    fun expectSkillSaved(name: String) = expect("技能 $name 已保存") { trace ->
        if (name in trace.savedSkills) null else "技能 $name 未保存；实际：${trace.savedSkills}"
    }

    fun expectNoSkillSaved() = expect("没有技能被保存") { trace ->
        if (trace.savedSkills.isEmpty()) null else "不应保存技能，实际：${trace.savedSkills}"
    }

    fun expectUserAsked(contains: String) = expect("向用户确认含「$contains」") { trace ->
        if (trace.userQuestions.any { contains in it.first }) null else "没有匹配的确认；实际：${trace.userQuestions.map { it.first }}"
    }

    internal fun build(): Scenario {
        require(userMessage != null || phoneGoal != null) { "场景「$name」缺少 givenUser 或 givenPhoneTask" }
        return Scenario(
            name = name,
            category = category,
            spec = ScenarioSpec(
                userMessage = userMessage,
                phoneGoal = phoneGoal,
                phoneSkill = phoneSkill,
                chat = chat,
                operator = operator,
                streaming = streaming,
                screens = screens.toList(),
                notes = notes.toList(),
                skills = skills.toList(),
                userAnswers = userAnswers,
                approval = approval,
                realSentinel = realSentinel,
                skillAuthoring = skillAuthoring,
                tools = tools.toMap(),
                maxSteps = maxSteps,
                stopImmediately = stopImmediately,
            ),
            expectations = expectations.toList(),
        )
    }
}

fun scenario(name: String, block: ScenarioBuilder.() -> Unit): Scenario = ScenarioBuilder(name).apply(block).build()
