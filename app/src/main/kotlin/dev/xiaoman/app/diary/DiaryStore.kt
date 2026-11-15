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

/** A model-generated recap of one day; [entryCount] tells the UI when a refresh is due. */
@Serializable
data class DiarySummary(
    /** yyyy-MM-dd in the device's local zone. */
    val date: String,
    val text: String,
    val updatedAt: Long = 0L,
    val entryCount: Int = 0,
)

@Serializable
private data class DiaryFile(
    val version: Int = 1,
    val entries: List<DiaryEntry> = emptyList(),
    /** Added in v2; old files simply decode to an empty list. */
    val summaries: List<DiarySummary> = emptyList(),
)

/**
 * Persists Xiaoman's diary under [file]; plain JSON so users can inspect or back it up.
 * Writes are atomic and both lists are capped so the file never grows without bound.
 */
class DiaryStore(private val file: File, private val maxItems: Int = 500) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val initial: DiaryFile = load()
    private val _entries = MutableStateFlow(initial.entries)
    val entries: StateFlow<List<DiaryEntry>> = _entries.asStateFlow()
    private val _summaries = MutableStateFlow(initial.summaries.associateBy { it.date })
    val summaries: StateFlow<Map<String, DiarySummary>> = _summaries.asStateFlow()

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
        if (save(next, _summaries.value.values)) _entries.value = next
    }

    /** Stores the summary produced by the model API for one day. */
    @Synchronized
    fun putSummary(date: String, text: String, entryCount: Int) {
        val clean = text.trim()
        if (date.isBlank() || clean.isEmpty()) return
        val next = _summaries.value + (date to DiarySummary(date, clean, System.currentTimeMillis(), entryCount))
        if (save(_entries.value, next.values)) _summaries.value = next
    }

    @Synchronized
    fun clear() {
        runCatching { !file.exists() || file.delete() }
        _entries.value = emptyList()
        _summaries.value = emptyMap()
    }

    private fun load(): DiaryFile = runCatching {
        if (!file.isFile) return DiaryFile()
        val parsed = json.decodeFromString<DiaryFile>(file.readText())
        DiaryFile(
            entries = parsed.entries.takeLast(maxItems),
            summaries = parsed.summaries.takeLast(MAX_SUMMARIES),
        )
    }.getOrDefault(DiaryFile())

    private fun save(entries: List<DiaryEntry>, summaries: Collection<DiarySummary>): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val text = json.encodeToString(
            DiaryFile(entries = entries, summaries = summaries.sortedBy { it.date }.takeLast(MAX_SUMMARIES)),
        )
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

    companion object {
        /** Keep roughly four months of daily recaps. */
        private const val MAX_SUMMARIES = 120
    }
}
