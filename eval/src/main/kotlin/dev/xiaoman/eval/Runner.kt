package dev.xiaoman.eval

import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.Sentinel
import dev.xiaoman.sentinel.PolicySentinel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class ScenarioResult(
    val name: String,
    val category: EvalCategory,
    val passed: Boolean,
    val failures: List<String>,
    val eventKinds: List<String>,
    val durationMs: Long,
)

data class EvalReport(val results: List<ScenarioResult>, val durationMs: Long) {
    val passed: Int get() = results.count { it.passed }
    val failed: Int get() = results.size - passed
    val allPassed: Boolean get() = failed == 0

    fun render(): String = buildString {
        appendLine("小满评测报告：${results.size} 个场景，${passed} 通过，${failed} 失败，用时 ${durationMs}ms")
        appendLine()
        for (category in EvalCategory.entries) {
            val group = results.filter { it.category == category }
            if (group.isEmpty()) continue
            appendLine("[$category] ${group.count { it.passed }}/${group.size}")
            group.forEach { r ->
                appendLine("  ${if (r.passed) "PASS" else "FAIL"}  ${r.name} (${r.durationMs}ms)")
                r.failures.forEach { appendLine("         - $it") }
                if (!r.passed) appendLine("         事件：${r.eventKinds.joinToString(" → ")}")
            }
            appendLine()
        }
        if (allPassed) appendLine("结论：全部通过，安全回归集无回退。")
        else appendLine("结论：存在失败场景，禁止发布。")
    }

    fun toJson(): String = buildJsonObject {
        put("total", results.size)
        put("passed", passed)
        put("failed", failed)
        put("durationMs", durationMs)
        putJsonArray("scenarios") {
            results.forEach { r ->
                addJsonObject {
                    put("name", r.name)
                    put("category", r.category.name)
                    put("passed", r.passed)
                    put("durationMs", r.durationMs)
                    putJsonArray("failures") { r.failures.forEach { add(it) } }
                    putJsonArray("events") { r.eventKinds.forEach { add(it) } }
                }
            }
        }
    }.toString()
}

/** Runs scenarios against the real agent with deterministic fakes. No Android SDK, no network, no keys. */
object EvalRunner {
    fun run(scenario: Scenario): ScenarioResult {
        val start = System.nanoTime()
        val trace = try {
            execute(scenario.spec)
        } catch (t: Throwable) {
            EvalTrace(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyMap(), emptyList(), emptyList(), emptyList(), null, t)
        }
        val failures = scenario.expectations.mapNotNull { expectation ->
            try {
                expectation.check(trace)?.let { "${expectation.description}：$it" }
            } catch (t: Throwable) {
                "${expectation.description}：断言自身抛异常 ${t::class.simpleName}: ${t.message}"
            }
        }
        return ScenarioResult(
            name = scenario.name,
            category = scenario.category,
            passed = failures.isEmpty(),
            failures = failures,
            eventKinds = trace.kinds,
            durationMs = (System.nanoTime() - start) / 1_000_000,
        )
    }

    fun runAll(scenarios: List<Scenario>): EvalReport {
        val start = System.nanoTime()
        val results = scenarios.map(::run)
        return EvalReport(results, (System.nanoTime() - start) / 1_000_000)
    }

    private fun execute(spec: ScenarioSpec): EvalTrace = runBlocking {
        val chat = EvalLlm(ModelRole.CHAT, spec.chat, spec.streaming)
        val operator = EvalLlm(ModelRole.OPERATOR, spec.operator)
        val device = EvalDevice(spec.screens)
        val memory = EvalMemory(spec.notes)
        val skills = EvalSkills(spec.skills)
        val approvals = EvalApprovals(spec.approval)
        val audit = EvalAudit()
        val users = EvalUsers(spec.userAnswers)
        val reminders = EvalReminders()
        val tools = if (spec.tools.isEmpty()) null else EvalTools(spec.tools)
        val skillStore = if (spec.skillAuthoring) EvalUserSkillStore(skills) else null
        val sentinel: Sentinel = if (spec.realSentinel) {
            PolicySentinel(approvals, audit, clock = { EVAL_CLOCK })
        } else {
            AllowAllSentinel()
        }
        val events = EvalEvents()
        val stop = EvalStop(spec.stopImmediately)
        var ids = 0
        val agent = XiaomanAgent(
            chat, operator, device, memory, skills, sentinel, users, reminders,
            config = AgentConfig(maxSteps = spec.maxSteps),
            clock = { EVAL_CLOCK },
            idFactory = { "eval-${++ids}" },
            externalTools = tools,
            userSkillStore = skillStore,
        )

        var error: Throwable? = null
        try {
            if (spec.userMessage != null) {
                agent.handle(spec.userMessage, events, stop)
            } else {
                agent.runPhoneTask(spec.phoneGoal!!, spec.phoneSkill, events, stop)
            }
        } catch (t: Throwable) {
            error = t
        }

        EvalTrace(
            events = events.all.toList(),
            performed = device.performed.toList(),
            approvals = approvals.requests.toList(),
            audit = audit.records.toList(),
            chatRequests = chat.requests.toList(),
            operatorRequests = operator.requests.toList(),
            notes = memory.notes.toMap(),
            userQuestions = users.asked.toList(),
            toolCalls = tools?.calls?.toList() ?: emptyList(),
            savedSkills = skillStore?.savedNames?.toList() ?: emptyList(),
            finished = events.of<AgentEvent.PhoneTaskFinished>().lastOrNull(),
            error = error,
        )
    }
}
