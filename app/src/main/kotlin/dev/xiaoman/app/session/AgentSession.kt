package dev.xiaoman.app.session

import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.TaskResume
import dev.xiaoman.api.UserChannel
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Texts the session adds to the chat itself; resolved from resources by the app. */
data class SessionTexts(
    val notConfigured: String = "还没有配置模型。",
    val busy: String = "已有任务在运行。",
    val stopped: String = "已停止",
    val interrupted: String = "应用重启，任务中断",
    val resumed: (Int) -> String = { "应用重启，从第 $it 步之后继续" },
    val approvedOnce: (String) -> String = { "已允许：$it" },
    val approvedTask: (String) -> String = { "本次任务都允许：$it" },
    val denied: (String) -> String = { "已拒绝：$it" },
    val approvalPending: (String) -> String = { "等待确认：$it" },
)

class PendingQuestion(val id: Long, val taskId: String, val question: String, val takeover: Boolean) {
    internal val answer = CompletableDeferred<String>()
}

class PendingApproval(val id: Long, val request: ApprovalRequest) {
    internal val answer = CompletableDeferred<ApprovalAnswer>()
}

/**
 * The one conversation with the agent: runs chat turns and phone tasks one at a time in [scope],
 * keeps the chat list, and is the agent's [UserChannel] (the next chat input answers a question)
 * and [ApprovalGateway] (the approval card / notification answers it).
 *
 * [AgentService][dev.xiaoman.app.AgentService] keeps the process in the foreground while [busy].
 *
 * Every phone task ends with a terminal event in the trace: when the agent cannot run (not
 * configured, busy, crashed, cancelled) the session emits `Failed` + `PhoneTaskFinished` itself.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val agent: () -> Agent?,
    private val runIds: RunIds,
    val stopSignal: MutableStopSignal,
    private val trace: AgentListener,
    private val onTaskEnd: (String) -> Unit = {},
    private val texts: SessionTexts = SessionTexts(),
    private val ready: suspend () -> Unit = {},
    /** Optional local persistence; null keeps the conversation in memory (tests, web runs). */
    private val history: ChatHistoryStore? = null,
    /** Phone tasks are kept as their own sub-conversations instead of flooding the main chat. */
    private val taskThreads: TaskThreadStore? = null,
    /** Phone tasks dispatched from the task center wait here for the single execution slot. */
    private val queue: TaskQueueStore? = null,
    /** Dynamic history cap from Settings; read on each append. */
    private val historyLimit: () -> Int = { MAX_ITEMS },
    /** Localized event text; the default Chinese object keeps JVM tests and the web server unchanged. */
    private val stepText: StepTextFormatter = StepText,
) : UserChannel, ApprovalGateway {

    // Load with the absolute cap instead of reading Settings here: the session is built during
    // Application.onCreate, and settings live behind EncryptedSharedPreferences. The live
    // [historyLimit] still applies on every append/save, so lowering the cap trims the file.
    private val restored = history?.load(limit = 2_000).orEmpty()
    private val ids = AtomicLong(restored.maxOfOrNull { it.id } ?: 0L)
    private val running = AtomicBoolean(false)
    private val persistMutex = Mutex()

    private val _items = MutableStateFlow(restored)
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Short text of the current step for the overlay and the notification. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Phone tasks started but without PhoneTaskFinished yet. */
    private val openRuns = LinkedHashSet<String>()

    /** Main-chat TaskEntry id for each task thread, so completion can update the same card. */
    private val taskEntryIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Checkpoint for one step: completed steps and a compact, UI-language-independent line. */
    private val assistantDrafts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun checkpointOf(event: AgentEvent): Pair<Int, String>? = when (event) {
        is AgentEvent.StepExecuted -> {
            val outcome = if (event.result.ok) "成功" else "失败：${event.result.message ?: "未知原因"}"
            (event.stepIndex + 1) to "${event.stepIndex + 1}. ${StepText.action(event.action)}：$outcome"
        }
        is AgentEvent.StepBlocked ->
            event.stepIndex to "${event.stepIndex + 1}. ${StepText.action(event.action)}：被拦截（${event.decision.reason}）"
        else -> null
    }

    init {
        // A RUNNING queue row means the process died mid-task. Tasks with a checkpoint go back to
        // the queue so the operator can continue; the rest are closed honestly.
        val interruptedQueue = queue?.interruptedIds.orEmpty().toSet()
        val resumable = interruptedQueue.filter { taskThreads?.get(it)?.checkpoint != null }.toSet()
        interruptedQueue.forEach { id ->
            if (id !in resumable) {
                queue?.finish(id, success = false, note = texts.interrupted)
                taskThreads?.get(id)?.let { thread ->
                    taskThreads.finish(id, success = false, summary = texts.interrupted, steps = thread.steps)
                }
            }
        }
        // Threads can only be "running" on disk when the previous process died, and every queue
        // task that could resume is already known; anything else is stale.
        taskThreads?.threads?.value.orEmpty()
            .filter { it.running && it.id !in resumable }
            .forEach { thread ->
                taskThreads?.finish(thread.id, success = false, summary = texts.interrupted, steps = thread.steps)
            }
        if (interruptedQueue.isNotEmpty()) {
            _items.update { list ->
                list.map { item ->
                    if (item is ChatItem.TaskEntry && item.taskId in interruptedQueue && item.taskId !in resumable) {
                        item.copy(running = false, success = false, summary = texts.interrupted)
                    } else {
                        item
                    }
                }
            }
            persist()
        }
    }

    /** Trace first, then the UI. */
    val listener = AgentListener { event ->
        try {
            trace.onEvent(event)
        } catch (e: Exception) {
            // A full disk must not stop the task.
        }
        synchronized(openRuns) {
            when (event) {
                is AgentEvent.PhoneTaskStarted -> openRuns += event.taskId
                is AgentEvent.PhoneTaskFinished -> openRuns -= event.taskId
                else -> Unit
            }
        }
        if (event is AgentEvent.PhoneTaskFinished) onTaskEnd(event.taskId)
        stepText.status(event)?.let { _status.value = it }

        // Streaming text: deltas update an in-memory draft bubble; the final Reply finalizes and
        // persists it. Skipping chatItem below keeps a streamed reply as a single bubble.
        if (event is AgentEvent.ReplyDelta || event is AgentEvent.Reply) {
            handleReplyDraft(event)
            _events.tryEmit(event)
            return@AgentListener
        }

        stepText.chatItem(event, ids.incrementAndGet())?.let { item ->
            when (event) {
                // SkillChosen precedes PhoneTaskStarted; the thread is created by the latter.
                is AgentEvent.SkillChosen -> Unit
                is AgentEvent.PhoneTaskStarted -> {
                    taskThreads?.start(event.taskId, event.goal)
                    taskThreads?.append(event.taskId, item)
                    val entryId = ids.incrementAndGet()
                    taskEntryIds[event.taskId] = entryId
                    add(ChatItem.TaskEntry(entryId, event.taskId, event.goal, running = true))
                }
                is AgentEvent.PhoneTaskFinished -> {
                    taskThreads?.append(event.taskId, item)
                    taskThreads?.finish(event.taskId, event.success, event.summary, event.steps)
                    taskEntryIds.remove(event.taskId)?.let { entryId ->
                        update(entryId) { current ->
                            if (current is ChatItem.TaskEntry) {
                                current.copy(
                                    running = false,
                                    success = event.success,
                                    summary = event.summary,
                                    steps = event.steps,
                                )
                            } else {
                                current
                            }
                        }
                    }
                }
                else -> {
                    val step = checkpointOf(event)
                    if (taskThreads?.get(event.taskId) != null) {
                        // One commit per step: the progress row and the checkpoint share a write.
                        if (step != null) {
                            taskThreads.appendWithCheckpoint(event.taskId, item, step.first, step.second)
                        } else {
                            taskThreads.append(event.taskId, item)
                        }
                    } else {
                        add(item)
                    }
                }
            }
        }
        _events.tryEmit(event)
    }

    /** Routes [AgentEvent.ReplyDelta] / [AgentEvent.Reply] to the in-memory streaming draft. */
    private fun handleReplyDraft(event: AgentEvent) = when (event) {
        is AgentEvent.ReplyDelta -> appendOrUpdateDraft(event.taskId, event.text)
        is AgentEvent.Reply -> finalizeOrCreateDraft(event.taskId, event.text)
        else -> Unit
    }

    /** Appends [chunk] to the draft assistant bubble for [turnId]; in-memory only, no disk write. */
    private fun appendOrUpdateDraft(turnId: String, chunk: String) {
        val id = assistantDrafts[turnId]
        if (id != null) {
            _items.update { list -> list.map { if (it is ChatItem.Assistant && it.id == id) it.copy(text = it.text + chunk) else it } }
        } else {
            val newId = ids.incrementAndGet()
            assistantDrafts[turnId] = newId
            _items.update { (it + ChatItem.Assistant(newId, chunk)).takeLast(historyLimit().coerceIn(20, 2_000)) }
        }
    }

    /** Finalizes the draft bubble with the full text (persisted), or creates one when none streamed. */
    private fun finalizeOrCreateDraft(turnId: String, text: String) {
        val id = assistantDrafts.remove(turnId)
        if (id != null) {
            update(id) { current -> if (current is ChatItem.Assistant) current.copy(text = text) else current }
        } else {
            add(ChatItem.Assistant(ids.incrementAndGet(), text))
        }
    }

    sealed class Submit {
        data object Answered : Submit()
        data object Started : Submit()
        data object Busy : Submit()
        data object NotConfigured : Submit()
        data object Empty : Submit()

        /** The queue refuses more tasks until the waiting ones are handled. */
        data object QueueFull : Submit()

        /** Accepted into the task queue; it may run now or after the current task. */
        data class Queued(val id: String) : Submit()
    }

    /** Chat input: answers a pending question, else starts a chat turn. [images] are PNG bytes. */
    fun submit(text: String, images: List<ByteArray> = emptyList()): Submit {
        val message = text.trim()
        if (message.isEmpty() && images.isEmpty()) return Submit.Empty
        val pending = _question.value
        if (pending != null && pending.answer.complete(message)) {
            add(ChatItem.User(ids.incrementAndGet(), message, imageCount = images.size))
            return Submit.Answered
        }
        val agent = agent()
        if (agent == null) {
            add(ChatItem.User(ids.incrementAndGet(), message, imageCount = images.size))
            add(ChatItem.Error(ids.incrementAndGet(), texts.notConfigured))
            return Submit.NotConfigured
        }
        val started = launch(null) { agent.handle(message, listener, stopSignal, images) }
        if (!started) return Submit.Busy
        add(ChatItem.User(ids.incrementAndGet(), message, imageCount = images.size))
        return Submit.Started
    }

    /**
     * Continues one task's own conversation. The user message and Xiaoman's reply stay in that
     * thread; phone tasks started from here are routed to the main listener as new threads.
     */
    fun submitToThread(taskId: String, text: String, images: List<ByteArray> = emptyList()): Submit {
        val message = text.trim()
        if (message.isEmpty() && images.isEmpty()) return Submit.Empty
        val thread = taskThreads?.get(taskId) ?: return Submit.Empty

        val pending = _question.value
        if (pending != null && pending.taskId == taskId && pending.answer.complete(message)) {
            taskThreads.append(taskId, ChatItem.User(ids.incrementAndGet(), message, imageCount = images.size))
            return Submit.Answered
        }

        val agent = agent()
        if (agent == null) {
            taskThreads.append(taskId, ChatItem.User(ids.incrementAndGet(), message, imageCount = images.size))
            taskThreads.append(taskId, ChatItem.Error(ids.incrementAndGet(), texts.notConfigured))
            return Submit.NotConfigured
        }

        val turnId = "thread-${ids.incrementAndGet()}"
        val threadDrafts = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val threadDraftText = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()
        val threadListener = AgentListener { event ->
            if (event.taskId == turnId) {
                when (event) {
                    // Streaming reply in the sub-conversation: update an in-memory bubble, persist only the final text.
                    is AgentEvent.ReplyDelta -> {
                        val id = threadDrafts.getOrPut(turnId) { ids.incrementAndGet() }
                        val text = threadDraftText.getOrPut(turnId) { StringBuilder() }.append(event.text).toString()
                        taskThreads?.upsertAssistant(taskId, id, text, persist = false)
                    }
                    is AgentEvent.Reply -> {
                        val id = threadDrafts.remove(turnId)
                        val text = threadDraftText.remove(turnId)?.toString() ?: event.text
                        if (id != null) {
                            taskThreads?.upsertAssistant(taskId, id, text, persist = true)
                        } else {
                            stepText.chatItem(event, ids.incrementAndGet())?.let { taskThreads?.append(taskId, it) }
                        }
                    }
                    is AgentEvent.ToolCalled,
                    is AgentEvent.MemoryUpdated,
                    is AgentEvent.Failed,
                    -> stepText.chatItem(event, ids.incrementAndGet())?.let { taskThreads?.append(taskId, it) }
                    else -> Unit
                }
            } else {
                // A phone task started inside this turn keeps its own task thread and main-chat entry.
                listener.onEvent(event)
            }
        }
        val started = launch(null) {
            agent.handle(threadPrompt(thread, message), threadListener, stopSignal, images, taskId = turnId)
        }
        if (!started) return Submit.Busy
        taskThreads.append(taskId, ChatItem.User(ids.incrementAndGet(), message, imageCount = images.size))
        return Submit.Started
    }

    private fun threadPrompt(thread: TaskThread, message: String): String = buildString {
        appendLine("【任务子对话】")
        appendLine("目标：${thread.goal}")
        appendLine(
            "状态：" + when {
                thread.running -> "进行中"
                thread.success == true -> "已完成"
                else -> "未完成"
            },
        )
        if (thread.summary.isNotBlank()) appendLine("结果：${thread.summary}")
        val transcript = thread.items.takeLast(12).mapNotNull(::threadLine).joinToString("\n")
        if (transcript.isNotBlank()) appendLine("已有记录：\n$transcript")
        appendLine("用户现在说：" + message.ifBlank { "（发送了图片）" })
        append("请结合任务上下文直接回答。需要继续操作手机时可以调用 phone_task。")
    }

    private fun threadLine(item: ChatItem): String? = when (item) {
        is ChatItem.User -> "用户：${item.text}" + if (item.imageCount > 0) "（含图片）" else ""
        is ChatItem.Assistant -> "小满：${item.text}"
        is ChatItem.Progress -> item.text
        is ChatItem.Question -> "问题：${item.text}"
        is ChatItem.Error -> "错误：${item.text}"
        is ChatItem.TaskEntry -> null
    }

    /**
     * Runs one phone task directly (debug bridge). [runId] becomes the task id and trace folder;
     * an invalid or missing one is replaced. Returns false when it could not start; the trace then
     * already holds Failed + PhoneTaskFinished for that run.
     */
    fun runPhoneTask(goal: String, skill: String?, runId: String?): Boolean {
        val id = runId?.takeIf(RunIds::isValid) ?: runIds.generate()
        val agent = agent()
        if (agent == null) {
            fail(id, texts.notConfigured)
            return false
        }
        val started = launch(id) {
            runIds.prime(id)
            agent.runPhoneTask(goal, skill, listener, stopSignal)
        }
        if (!started) fail(id, texts.busy)
        return started
    }

    /**
     * Adds a phone task to the queue and starts it when the single execution slot is free.
     * Tasks queued behind a running one start as soon as it finishes; Stop only ends the current task.
     */
    fun enqueueTask(goal: String, skill: String? = null): Submit {
        val message = goal.trim()
        if (message.isEmpty()) return Submit.Empty
        val store = queue ?: return Submit.Empty
        if (agent() == null) return Submit.NotConfigured
        val record = store.enqueue(runIds.generate(), message, skill) ?: return Submit.QueueFull
        drainQueue()
        return Submit.Queued(record.id)
    }

    /** Picks up tasks that were still queued when the app last closed. */
    fun resumeQueue() {
        if (queue?.tasks?.value.isNullOrEmpty()) return
        // Without models every task would fail at once; keep them queued until a model is configured.
        if (agent() == null) return
        drainQueue()
    }

    /** Pulls the next queued task while the slot is free; true when a task took the slot. */
    private fun drainQueue(): Boolean {
        val store = queue ?: return false
        while (true) {
            if (running.get()) return false
            val next = store.next() ?: return false
            val agent = agent()
            if (agent == null) {
                // Keep the row for later; a missing model is not the task's failure.
                store.requeue(next.id)
                return false
            }
            val thread = taskThreads?.get(next.id)
            val resume = if (next.resumed) {
                thread?.checkpoint?.let { TaskResume(it.steps, it.history) }
            } else {
                null
            }
            if (next.resumed && resume == null) {
                store.finish(next.id, success = false, note = texts.interrupted)
                taskThreads?.finish(next.id, success = false, summary = texts.interrupted, steps = thread?.steps ?: 0)
                continue
            }
            val started = launch(next.id) {
                runIds.prime(next.id)
                if (resume != null) {
                    taskThreads?.append(
                        next.id,
                        ChatItem.Progress(ids.incrementAndGet(), texts.resumed(resume.steps), true),
                    )
                }
                try {
                    val finished = agent.runPhoneTask(next.goal, next.skill, listener, stopSignal, resume)
                    store.finish(next.id, finished.success, finished.summary)
                } catch (e: CancellationException) {
                    store.finish(next.id, success = false, note = texts.stopped)
                    throw e
                } catch (e: Exception) {
                    store.finish(next.id, success = false, note = e.message?.takeIf { it.isNotBlank() } ?: texts.stopped)
                    throw e
                }
            }
            if (!started) store.requeue(next.id)
            return started
        }
    }

    /** Emits Failed + PhoneTaskFinished for a run that cannot (or could not) complete. */
    fun fail(runId: String, message: String) {
        listener.onEvent(AgentEvent.Failed(runId, message))
        listener.onEvent(AgentEvent.PhoneTaskFinished(runId, false, message, 0))
    }

    /** Overlay Stop / notification Stop / debug STOP: cooperative stop, pending prompts released. */
    fun stop() {
        if (!_busy.value) return
        stopSignal.stop()
        _approval.value?.answer?.complete(ApprovalAnswer(approved = false))
        _question.value?.answer?.complete(texts.stopped)
        _status.value = texts.stopped
    }

    fun answerApproval(answer: ApprovalAnswer, approvalId: Long? = null) {
        val pending = _approval.value ?: return
        if (approvalId != null && pending.id != approvalId) return
        pending.answer.complete(answer)
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        val pending = PendingQuestion(ids.incrementAndGet(), taskId, question, takeover)
        if (stopSignal.stopped) return texts.stopped
        _question.value = pending
        try {
            return pending.answer.await()
        } finally {
            _question.compareAndSet(pending, null)
        }
    }

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        if (stopSignal.stopped) return ApprovalAnswer(approved = false)
        val pending = PendingApproval(ids.incrementAndGet(), request)
        val before = _status.value
        _approval.value = pending
        _status.value = texts.approvalPending(request.prompt)
        try {
            val answer = pending.answer.await()
            val line = when {
                !answer.approved -> texts.denied(request.prompt)
                answer.scope == dev.xiaoman.api.ApprovalScope.TASK -> texts.approvedTask(request.prompt)
                else -> texts.approvedOnce(request.prompt)
            }
            add(ChatItem.Progress(ids.incrementAndGet(), line, answer.approved))
            return answer
        } finally {
            _approval.compareAndSet(pending, null)
            if (!stopSignal.stopped) _status.value = before
        }
    }

    /** Starts [block] unless something is running. [runId] is pre-registered as open (debug runs). */
    private fun launch(runId: String?, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopSignal.reset()
        if (runId != null) synchronized(openRuns) { openRuns += runId }
        _status.value = null
        _busy.value = true
        scope.launch {
            var failure: String? = null
            try {
                ready()
                block()
            } catch (e: CancellationException) {
                failure = texts.stopped
                throw e
            } catch (e: Exception) {
                failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                withContext(NonCancellable) {
                    val open = synchronized(openRuns) { openRuns.toList().also { openRuns.clear() } }
                    open.forEach { fail(it, failure ?: texts.stopped) }
                    if (failure != null && open.isEmpty()) add(ChatItem.Error(ids.incrementAndGet(), failure))
                    _question.value?.answer?.cancel()
                    _approval.value?.answer?.cancel()
                    _question.value = null
                    _approval.value = null
                    _status.value = null
                    running.set(false)
                    // A finished turn may hand the slot straight to the next queued task, so the
                    // busy flag never drops and the foreground service does not flicker.
                    if (!drainQueue()) _busy.value = false
                }
            }
        }
        return true
    }

    /** Clears the visible conversation and the persisted copy; a running task is left alone. */
    fun clearHistory() {
        _items.value = emptyList()
        ids.set(0)
        taskEntryIds.clear()
        taskThreads?.clear()
        history?.clear()
    }

    private fun add(item: ChatItem) {
        _items.update { (it + item).takeLast(historyLimit().coerceIn(20, 2_000)) }
        persist()
    }

    private fun update(id: Long, transform: (ChatItem) -> ChatItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persist()
    }

    /** Writes the latest list on IO; the mutex makes the last queued save win instead of a stale one. */
    private fun persist() {
        val store = history ?: return
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock { store.save(_items.value) }
        }
    }

    companion object {
        const val MAX_ITEMS = 500
    }
}
