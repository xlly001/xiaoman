package dev.xiaoman.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
private data class ChatHistoryFile(val version: Int = 1, val items: List<ChatItem> = emptyList())

/**
 * Persists the chat list under [file] so a restart keeps the conversation.
 *
 * The file is plain, user-inspectable JSON. Writes are atomic; a corrupt or unreadable file is
 * treated as an empty history instead of blocking App startup.
 */
class ChatHistoryStore(
    private val file: File,
    /** Read dynamically so a Settings change applies without rebuilding the graph. */
    private val maxItems: () -> Int = { AgentSession.MAX_ITEMS },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun limit(): Int = maxItems().coerceIn(20, 2_000)

    @Synchronized
    fun load(limit: Int = limit()): List<ChatItem> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<ChatHistoryFile>(file.readText()).items.takeLast(limit)
    }.getOrDefault(emptyList())

    /** Returns false when the write failed; the in-memory conversation is never affected. */
    @Synchronized
    fun save(items: List<ChatItem>): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val text = json.encodeToString(ChatHistoryFile(items = items.takeLast(limit())))
        val temp = File.createTempFile(".history-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.recoverCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } finally {
            temp.delete()
        }
        true
    }.getOrDefault(false)

    @Synchronized
    fun clear(): Boolean = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
}
