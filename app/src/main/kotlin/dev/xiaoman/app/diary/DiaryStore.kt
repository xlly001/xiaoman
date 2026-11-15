package dev.xiaoman.app.diary

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

/** One line in Xiaoman's diary: a finished task, a memory update, or another milestone. */
@Serializable
data class DiaryEntry(
    val id: Long,
    val at: Long,
    /** task_done / task_failed / memory / note */
    val kind: String,
    val title: String,
    val detail: String = "",
)

@Serializable
private data class DiaryFile(val version: Int = 1, val entries: List<DiaryEntry> = emptyList())

/**
 * Persists Xiaoman's diary under [file]; plain JSON so users can inspect or back it up.
 * Writes are atomic and the list is capped so the file never grows without bound.
 */
class DiaryStore(private val file: File, private val maxItems: Int = 500) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<DiaryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun add(kind: String, title: String, detail: String = "", at: Long = System.currentTimeMillis()) {
        if (title.isBlank() && detail.isBlank()) return
        val current = _entries.value
        val entry = DiaryEntry(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1,
            at = at,
            kind = kind,
            title = title.trim(),
            detail = detail.trim(),
        )
        val next = (current + entry).takeLast(maxItems.coerceAtLeast(1))
        if (save(next)) _entries.value = next
    }

    @Synchronized
    fun clear() {
        runCatching { !file.exists() || file.delete() }
        _entries.value = emptyList()
    }

    private fun load(): List<DiaryEntry> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<DiaryFile>(file.readText()).entries.takeLast(maxItems)
    }.getOrDefault(emptyList())

    private fun save(entries: List<DiaryEntry>): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val text = json.encodeToString(DiaryFile(entries = entries))
        val temp = File.createTempFile(".diary-", ".tmp", parent)
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
