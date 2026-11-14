package dev.xiaoman.app.quick

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCommandStoreTest {
    private fun file() = Files.createTempDirectory("quick-commands").resolve("commands.json").toFile()

    private val defaults = listOf(QuickCommand("default", "默认", "你好", QuickCommand.ICON_SPARK))

    @Test fun `missing file falls back to defaults`() {
        val store = QuickCommandStore(file(), defaults)
        assertEquals(defaults, store.current)
    }

    @Test fun `add edit and delete survive a reload`() {
        val file = file()
        val store = QuickCommandStore(file, defaults)
        val added = assertNotNull(store.add("买牛奶", "帮我买一盒牛奶"))
        assertTrue(store.edit(added.id, "买水", "帮我买一瓶水"))
        val reloaded = QuickCommandStore(file, defaults)
        assertEquals(listOf(defaults.first(), added.copy(title = "买水", prompt = "帮我买一瓶水")), reloaded.current)

        assertTrue(reloaded.delete(added.id))
        assertEquals(listOf(defaults.first()), QuickCommandStore(file, defaults).current)
    }

    @Test fun `deleting every command keeps the list empty instead of restoring defaults`() {
        val file = file()
        val store = QuickCommandStore(file, defaults)
        assertTrue(store.delete(defaults.first().id))
        assertEquals(emptyList(), store.current)
        assertEquals(emptyList(), QuickCommandStore(file, defaults).current)
    }

    @Test fun `blank input and the size limit are rejected`() {
        val store = QuickCommandStore(file(), emptyList())
        assertNull(store.add("", "x"))
        assertNull(store.add("x", " "))
        repeat(QuickCommandStore.MAX_COMMANDS) { i ->
            assertNotNull(store.add("标题$i", "内容$i"))
        }
        assertNull(store.add("再一个", "超出上限"))
    }

    @Test fun `a corrupt file falls back to defaults`() {
        val file = file()
        file.parentFile?.mkdirs()
        file.writeText("{not json")
        assertEquals(defaults, QuickCommandStore(file, defaults).current)
    }
}
