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

/** One phone task as its own conversation: goal, step log, and final result. */
@Serializable
data class TaskThread(
    val id: String,
    val goal: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val success: Boolean? = null,
    val summary: String = "",
    val steps: Int = 0,
    val items: List<ChatItem> = emptyList(),
) {
    val running: Boolean get() = finishedAt == null
}

@Serializable
private data class TaskFile(val version: Int = 1, val threads: List<TaskThread> = emptyList())

/**
 * Persists task sub-conversations under [file]. The main chat only keeps a small entry card;
 * the detailed steps live here so the main conversation stays readable.
 */
class TaskThreadStore(
    private val file: File,
    private val maxThreads: Int = 100,
    private val maxItemsPerThread: Int = 200,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _threads = MutableStateFlow(load())
    val threads: StateFlow<List<TaskThread>> = _threads.asStateFlow()

    @Synchronized
    fun get(taskId: String): TaskThread? = _threads.value.firstOrNull { it.id == taskId }

    @Synchronized
    fun start(taskId: String, goal: String, at: Long = System.currentTimeMillis()) {
        if (_threads.value.any { it.id == taskId }) return
        val thread = TaskThread(id = taskId, goal = goal, startedAt = at)
        commit((_threads.value + thread).takeLast(maxThreads))
    }

    @Synchronized
    fun append(taskId: String, item: ChatItem) {
        val current = _threads.value
        val index = current.indexOfFirst { it.id == taskId }
        if (index < 0) return
        val thread = current[index]
        val items = (thread.items + item).takeLast(maxItemsPerThread)
        commit(current.toMutableList().also { it[index] = thread.copy(items = items) })
    }

    @Synchronized
    fun finish(
        taskId: String,
        success: Boolean,
        summary: String,
        steps: Int,
        at: Long = System.currentTimeMillis(),
    ) {
        val current = _threads.value
        val index = current.indexOfFirst { it.id == taskId }
        if (index < 0) return
        val thread = current[index]
        commit(
            current.toMutableList().also {
                it[index] = thread.copy(
                    finishedAt = at,
                    success = success,
                    summary = summary,
                    steps = steps,
                )
            },
        )
    }

    @Synchronized
    fun clear() {
        runCatching { !file.exists() || file.delete() }
        _threads.value = emptyList()
    }

    private fun commit(threads: List<TaskThread>) {
        if (save(threads)) _threads.value = threads
    }

    private fun load(): List<TaskThread> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<TaskFile>(file.readText()).threads.takeLast(maxThreads)
    }.getOrDefault(emptyList())

    private fun save(threads: List<TaskThread>): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val text = json.encodeToString(TaskFile(threads = threads))
        val temp = File.createTempFile(".tasks-", ".tmp", parent)
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
