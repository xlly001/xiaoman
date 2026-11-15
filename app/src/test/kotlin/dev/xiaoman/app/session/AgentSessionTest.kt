package dev.xiaoman.app.session

import dev.xiaoman.api.Action
import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.api.StopSignal
import dev.xiaoman.api.TaskResume
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {
    private val traced = mutableListOf<AgentEvent>()
    private val trace = AgentListener { traced += it }

    private fun TestScope.session(agent: Agent?) = AgentSession(
        scope = backgroundScope,
        agent = { agent },
        runIds = RunIds(),
        stopSignal = MutableStopSignal(),
        trace = trace,
    )

    private fun queueFile(): File {
        val dir = Files.createTempDirectory("xiaoman-session-queue").toFile()
        dir.deleteOnExit()
        return File(dir, "queue.json")
    }

    private fun TestScope.queuedSession(
        agent: Agent?,
        queue: TaskQueueStore,
        runIds: RunIds,
        threads: TaskThreadStore? = null,
    ) = AgentSession(
        scope = backgroundScope,
        agent = { agent },
        runIds = runIds,
        stopSignal = MutableStopSignal(),
        trace = trace,
        queue = queue,
        taskThreads = threads,
    )

    /** Answers like XiaomanAgent: asks once through the session, then finishes. */
    private class AskingAgent(private val users: () -> AgentSession) : Agent {
        val gate = CompletableDeferred<Unit>()

        override suspend fun handle(
            userMessage: String,
            listener: AgentListener,
            stop: StopSignal,
            images: List<ByteArray>,
            taskId: String?,
        ) {
            val answer = users().ask("t1", "去哪里？", false)
            listener.onEvent(AgentEvent.Reply("t1", "好的，去$answer"))
        }

        override suspend fun runPhoneTask(
            goal: String,
            skillName: String?,
            listener: AgentListener,
            stop: StopSignal,
            resume: TaskResume?,
        ): AgentEvent.PhoneTaskFinished {
            listener.onEvent(AgentEvent.PhoneTaskStarted("ignored", goal, skillName))
            gate.await()
            throw IllegalStateException("boom")
        }
    }

    @Test fun `debug run without models still ends with terminal events`() = runTest(UnconfinedTestDispatcher()) {
        val s = session(null)
        assertFalse(s.runPhoneTask("打开设置", null, "r1"))
        assertEquals(listOf("Failed", "PhoneTaskFinished"), traced.map { it::class.simpleName })
        assertTrue(traced.all { it.taskId == "r1" })
    }

    @Test fun `next chat input answers the agent's question`() = runTest(UnconfinedTestDispatcher()) {
        lateinit var s: AgentSession
        s = session(AskingAgent { s })
        assertIs<AgentSession.Submit.Started>(s.submit("打车"))
        assertNotNull(s.question.value)
        assertIs<AgentSession.Submit.Answered>(s.submit("家"))
        advanceUntilIdle()
        assertEquals("好的，去家", (traced.last() as AgentEvent.Reply).text)
        assertFalse(s.busy.value)
    }

    @Test fun `a crashed task is closed with Failed and PhoneTaskFinished`() = runTest(UnconfinedTestDispatcher()) {
        lateinit var s: AgentSession
        val agent = AskingAgent { s }
        s = session(agent)
        assertTrue(s.runPhoneTask("goal", null, "r2"))
        assertFalse(s.runPhoneTask("second", null, "r3")) // busy: r3 is closed right away
        assertEquals(listOf("Failed", "PhoneTaskFinished"), traced.filter { it.taskId == "r3" }.map { it::class.simpleName })
        agent.gate.complete(Unit)
        advanceUntilIdle()
        val r2 = traced.filter { it.taskId == "r2" }.map { it::class.simpleName }
        assertEquals(listOf("Failed", "PhoneTaskFinished"), r2)
        assertFalse(s.busy.value)
    }

    @Test fun `approval card answers and stop denies`() = runTest(UnconfinedTestDispatcher()) {
        val s = session(null)
        val request = ApprovalRequest("t", Action.Tap(nodeId = 1, target = "支付"), "确认支付？", "confirmation word '支付'")
        val first = backgroundScope.launch { assertEquals(ApprovalAnswer(true, ApprovalScope.TASK), s.request(request)) }
        val pending = assertNotNull(s.approval.value)
        s.answerApproval(ApprovalAnswer(false), approvalId = pending.id + 100) // stale id: ignored
        s.answerApproval(ApprovalAnswer(true, ApprovalScope.TASK), approvalId = pending.id)
        first.join()
        assertEquals(null, s.approval.value)
        assertTrue(s.items.value.last() is ChatItem.Progress)
    }

    @Test fun `queue rejects tasks while no model is configured`() = runTest(UnconfinedTestDispatcher()) {
        val queue = TaskQueueStore(queueFile())
        val s = queuedSession(null, queue, RunIds())

        assertIs<AgentSession.Submit.NotConfigured>(s.enqueueTask("查快递"))
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun `resume keeps queued tasks while no model is configured`() = runTest(UnconfinedTestDispatcher()) {
        val queue = TaskQueueStore(queueFile())
        queue.enqueue("q1", "查快递")
        val s = queuedSession(null, queue, RunIds())

        s.resumeQueue()
        advanceUntilIdle()

        assertEquals(QueueState.QUEUED, queue.get("q1")?.state)
    }

    @Test fun `queued tasks run one after another`() = runTest(UnconfinedTestDispatcher()) {
        val queue = TaskQueueStore(queueFile())
        val runIds = RunIds()
        val goals = mutableListOf<String>()
        val firstGate = CompletableDeferred<Unit>()
        val agent = object : Agent {
            override suspend fun handle(
                userMessage: String,
                listener: AgentListener,
                stop: StopSignal,
                images: List<ByteArray>,
                taskId: String?,
            ) = Unit

            override suspend fun runPhoneTask(
                goal: String,
                skillName: String?,
                listener: AgentListener,
                stop: StopSignal,
                resume: TaskResume?,
            ): AgentEvent.PhoneTaskFinished {
                val id = runIds.next()
                goals += goal
                listener.onEvent(AgentEvent.PhoneTaskStarted(id, goal, skillName))
                if (goals.size == 1) firstGate.await()
                val finished = AgentEvent.PhoneTaskFinished(id, true, "完成：$goal", 1)
                listener.onEvent(finished)
                return finished
            }
        }
        val s = queuedSession(agent, queue, runIds)

        assertIs<AgentSession.Submit.Queued>(s.enqueueTask("第一个"))
        assertIs<AgentSession.Submit.Queued>(s.enqueueTask("第二个"))
        assertEquals(1, queue.tasks.value.count { it.state == QueueState.RUNNING })
        assertEquals(1, queue.tasks.value.count { it.state == QueueState.QUEUED })

        firstGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("第一个", "第二个"), goals)
        assertTrue(queue.tasks.value.all { it.state == QueueState.DONE })
        assertFalse(s.busy.value)
    }

    @Test fun `an interrupted task without checkpoint is closed on load`() = runTest(UnconfinedTestDispatcher()) {
        val queueFile = queueFile()
        val first = TaskQueueStore(queueFile)
        first.enqueue("q1", "查快递", at = 100)
        first.next(at = 200) // left RUNNING, as if the process died

        val queue = TaskQueueStore(queueFile)
        val threads = TaskThreadStore(queueFile())
        queuedSession(null, queue, RunIds(), threads)
        advanceUntilIdle()

        assertEquals(QueueState.FAILED, queue.get("q1")?.state)
    }

    @Test fun `an interrupted task with checkpoint resumes from it`() = runTest(UnconfinedTestDispatcher()) {
        val queueFile = queueFile()
        val first = TaskQueueStore(queueFile)
        first.enqueue("q1", "查快递", at = 100)
        first.next(at = 200)

        val threadFile = queueFile()
        TaskThreadStore(threadFile).apply {
            start("q1", "查快递", at = 100)
            checkpoint("q1", 2, "1. 点击 快递：成功", at = 300)
            checkpoint("q1", 3, "2. 输入 123：成功", at = 400)
        }

        val resumes = mutableListOf<TaskResume?>()
        val agent = object : Agent {
            override suspend fun handle(
                userMessage: String,
                listener: AgentListener,
                stop: StopSignal,
                images: List<ByteArray>,
                taskId: String?,
            ) = Unit

            override suspend fun runPhoneTask(
                goal: String,
                skillName: String?,
                listener: AgentListener,
                stop: StopSignal,
                resume: TaskResume?,
            ): AgentEvent.PhoneTaskFinished {
                resumes += resume
                listener.onEvent(AgentEvent.PhoneTaskStarted("q1", goal, skillName))
                val finished = AgentEvent.PhoneTaskFinished("q1", true, "done", resume?.steps ?: 0)
                listener.onEvent(finished)
                return finished
            }
        }
        val queue = TaskQueueStore(queueFile)
        val threads = TaskThreadStore(threadFile)
        val s = queuedSession(agent, queue, RunIds(), threads)

        assertEquals(QueueState.QUEUED, queue.get("q1")?.state)
        assertTrue(queue.get("q1")?.resumed == true)

        s.resumeQueue()
        advanceUntilIdle()

        assertEquals(QueueState.DONE, queue.get("q1")?.state)
        val resume = assertNotNull(resumes.single())
        assertEquals(3, resume.steps)
        assertEquals(2, resume.history.size)
    }
}
