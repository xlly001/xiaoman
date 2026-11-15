package dev.xiaoman.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where a queued phone task is in its short life cycle. */
@Serializable
enum class QueueState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED,
}

/**
 * One phone task waiting for the single execution slot. The id is also the task id: the session
 * primes it before running, so the trace folder, task thread and queue row all agree.
 */
@Serializable
data class QueuedTask(
    val id: String,
    val goal: String,
    val skill: String? = null,
    val state: QueueState = QueueState.QUEUED,
    val priority: Int = 0,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** Failure reason or short result, shown in the task center. */
    val note: String = "",
) {
    /** Still waiting for or holding the execution slot. */
    val active: Boolean
        get() = state == QueueState.QUEUED || state == QueueState.RUNNING || state == QueueState.PAUSED
}

@Serializable
private data class QueueFile(val version: Int = 1, val tasks: List<QueuedTask> = emptyList())

/**
 * Persists the phone-task queue under [file]. The session owns the single execution slot; this
 * class only tracks order and state, so it stays testable on the JVM.
 *
 * A `RUNNING` row can only exist while the process is alive. On load those rows become
 * [QueueState.FAILED] with [interruptedNote], because there is no checkpoint to resume from yet.
 */
class TaskQueueStore(
    private val file: File,
    private val maxActive: Int = 20,
    private val maxRecords: Int = 120,
    private val interruptedNote: String = "",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val loaded = load()

    /** Ids that were running when the process died; the session marks their task threads failed too. */
    val interruptedIds: List<String> = loaded.interruptedIds

    private val _tasks = MutableStateFlow(loaded.tasks)
    val tasks: StateFlow<List<QueuedTask>> = _tasks.asStateFlow()

    /** Adds a task to the tail of the queue; null when too many tasks are still active. */
    @Synchronized
    fun enqueue(
        id: String,
        goal: String,
        skill: String? = null,
        priority: Int = 0,
        at: Long = System.currentTimeMillis(),
    ): QueuedTask? {
        if (_tasks.value.count { it.active } >= maxActive) return null
        val task = QueuedTask(
            id = id,
            goal = goal,
            skill = skill,
            priority = priority,
            createdAt = at,
            updatedAt = at,
        )
        return if (commit(_tasks.value + task)) task else null
    }

    /** Highest priority first, then first come first served; marks the winner [QueueState.RUNNING]. */
    @Synchronized
    fun next(at: Long = System.currentTimeMillis()): QueuedTask? {
        val current = _tasks.value
        val index = current.indices
            .filter { current[it].state == QueueState.QUEUED }
            .sortedWith(compareByDescending<Int> { current[it].priority }.thenBy { current[it].createdAt })
            .firstOrNull()
            ?: return null
        val running = current[index].copy(state = QueueState.RUNNING, updatedAt = at)
        return if (commit(current.toMutableList().also { it[index] = running })) running else null
    }

    @Synchronized
    fun pause(id: String, at: Long = System.currentTimeMillis()): Boolean =
        transition(id, QueueState.QUEUED, QueueState.PAUSED, at)

    @Synchronized
    fun resume(id: String, at: Long = System.currentTimeMillis()): Boolean =
        transition(id, QueueState.PAUSED, QueueState.QUEUED, at)

    /** Drops a task that has not started yet; a running task is stopped through the session. */
    @Synchronized
    fun cancel(id: String, at: Long = System.currentTimeMillis()): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (task.state != QueueState.QUEUED && task.state != QueueState.PAUSED) return false
        return replace(task.copy(state = QueueState.CANCELLED, updatedAt = at))
    }

    /** Puts a task back after the execution slot could not be taken. */
    @Synchronized
    fun requeue(id: String, at: Long = System.currentTimeMillis()): Boolean =
        transition(id, QueueState.RUNNING, QueueState.QUEUED, at)

    @Synchronized
    fun finish(id: String, success: Boolean, note: String = "", at: Long = System.currentTimeMillis()): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (!task.active) return false
        return replace(
            task.copy(
                state = if (success) QueueState.DONE else QueueState.FAILED,
                note = note,
                updatedAt = at,
            ),
        )
    }

    @Synchronized
    fun get(id: String): QueuedTask? = _tasks.value.firstOrNull { it.id == id }

    @Synchronized
    fun clearFinished() {
        commit(_tasks.value.filter { it.active })
    }

    @Synchronized
    fun clear() {
        runCatching { !file.exists() || file.delete() }
        _tasks.value = emptyList()
    }

    private fun transition(id: String, from: QueueState, to: QueueState, at: Long): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (task.state != from) return false
        return replace(task.copy(state = to, updatedAt = at))
    }

    private fun replace(task: QueuedTask): Boolean {
        val current = _tasks.value
        val index = current.indexOfFirst { it.id == task.id }
        if (index < 0) return false
        return commit(current.toMutableList().also { it[index] = task })
    }

    private fun commit(tasks: List<QueuedTask>): Boolean {
        val trimmed = trim(tasks)
        val saved = save(trimmed)
        if (saved) _tasks.value = trimmed
        return saved
    }

    /** Keeps every active row and the most recent finished ones; the oldest finished rows fall off. */
    private fun trim(tasks: List<QueuedTask>): List<QueuedTask> {
        if (tasks.size <= maxRecords) return tasks
        val active = tasks.filter { it.active }
        val finished = tasks.filterNot { it.active }
        val keep = (active + finished.takeLast((maxRecords - active.size).coerceAtLeast(0))).map { it.id }.toSet()
        return tasks.filter { it.id in keep }
    }

    private class Loaded(val tasks: List<QueuedTask>, val interruptedIds: List<String>)

    private fun load(): Loaded = runCatching {
        if (!file.isFile) return Loaded(emptyList(), emptyList())
        val tasks = json.decodeFromString<QueueFile>(file.readText()).tasks
        val interrupted = tasks.filter { it.state == QueueState.RUNNING }.map { it.id }
        val settled = tasks.map {
            if (it.state == QueueState.RUNNING) {
                it.copy(state = QueueState.FAILED, note = interruptedNote, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }.takeLast(maxRecords)
        if (interrupted.isNotEmpty()) save(settled)
        Loaded(settled, interrupted)
    }.getOrDefault(Loaded(emptyList(), emptyList()))

    private fun save(tasks: List<QueuedTask>): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val text = json.encodeToString(QueueFile(tasks = tasks))
        val temp = File.createTempFile(".queue-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } finally {
            temp.delete()
        }
        true
    }.getOrDefault(false)
}
