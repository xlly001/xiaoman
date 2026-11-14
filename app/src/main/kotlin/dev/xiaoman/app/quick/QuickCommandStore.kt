package dev.xiaoman.app.quick

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
import java.util.UUID

/** One editable shortcut shown on the empty chat screen. */
@Serializable
data class QuickCommand(
    val id: String,
    val title: String,
    val prompt: String,
    /** A small icon key understood by the UI; unknown keys fall back to a spark icon. */
    val icon: String = ICON_SPARK,
) {
    companion object {
        const val ICON_SPARK = "spark"
        const val ICON_SHOPPING = "shopping"
        const val ICON_BELL = "bell"
        const val ICON_MEMORY = "memory"
        const val ICON_HEALTH = "health"
        const val ICON_PLAN = "plan"
    }
}

@Serializable
private data class QuickCommandFile(val version: Int = 1, val commands: List<QuickCommand> = emptyList())

/**
 * User-editable shortcuts persisted as JSON under [file].
 *
 * A missing or unreadable file falls back to [defaults]; an intentionally emptied list stays empty.
 */
class QuickCommandStore(
    private val file: File,
    private val defaults: List<QuickCommand> = emptyList(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _commands = MutableStateFlow(load())
    val commands: StateFlow<List<QuickCommand>> = _commands.asStateFlow()

    val current: List<QuickCommand> get() = _commands.value

    @Synchronized
    fun add(title: String, prompt: String, icon: String = QuickCommand.ICON_SPARK): QuickCommand? {
        val cleanTitle = title.trim().take(MAX_TITLE)
        val cleanPrompt = prompt.trim()
        if (cleanTitle.isEmpty() || cleanPrompt.isEmpty()) return null
        if (_commands.value.size >= MAX_COMMANDS) return null
        val command = QuickCommand(UUID.randomUUID().toString(), cleanTitle, cleanPrompt, icon)
        update(_commands.value + command)
        return command
    }

    @Synchronized
    fun edit(id: String, title: String, prompt: String): Boolean {
        val cleanTitle = title.trim().take(MAX_TITLE)
        val cleanPrompt = prompt.trim()
        if (cleanTitle.isEmpty() || cleanPrompt.isEmpty()) return false
        val existing = _commands.value.firstOrNull { it.id == id } ?: return false
        update(_commands.value.map { if (it.id == id) existing.copy(title = cleanTitle, prompt = cleanPrompt) else it })
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val remaining = _commands.value.filterNot { it.id == id }
        if (remaining.size == _commands.value.size) return false
        update(remaining)
        return true
    }

    @Synchronized
    fun resetToDefaults() = update(defaults.take(MAX_COMMANDS))

    private fun update(commands: List<QuickCommand>) {
        _commands.value = commands.take(MAX_COMMANDS)
        persist(_commands.value)
    }

    private fun load(): List<QuickCommand> {
        if (!file.isFile) return defaults.take(MAX_COMMANDS)
        return runCatching {
            json.decodeFromString<QuickCommandFile>(file.readText()).commands.take(MAX_COMMANDS)
        }.getOrElse { defaults.take(MAX_COMMANDS) }
    }

    private fun persist(commands: List<QuickCommand>) {
        runCatching {
            val parent = file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val text = json.encodeToString(QuickCommandFile(commands = commands))
            val temp = File.createTempFile(".quick-", ".tmp", parent)
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
        }
    }

    companion object {
        const val MAX_COMMANDS = 8
        const val MAX_TITLE = 24
    }
}
