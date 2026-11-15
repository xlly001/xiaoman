package dev.xiaoman.app.diary

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiaryStoreTest {
    private fun file() = Files.createTempDirectory("diary").resolve("diary.json").toFile()

    @Test fun `legacy diary files without summaries still load`() {
        val file = file().apply {
            parentFile?.mkdirs()
            writeText(
                """{"version":1,"entries":[{"id":1,"at":1000,"kind":"task_done","title":"买牛奶","detail":""}]}""",
                Charsets.UTF_8,
            )
        }
        val store = DiaryStore(file)
        assertEquals(1, store.entries.value.size)
        assertEquals(emptyMap(), store.summaries.value)
    }

    @Test fun `a summary round trips and survives later entries`() {
        val file = file()
        val store = DiaryStore(file)
        store.add("task_done", "买牛奶", "已下单")
        store.putSummary("2026-11-15", "今天我帮你订了牛奶。", 1)

        val reloaded = DiaryStore(file)
        assertEquals(1, reloaded.entries.value.size)
        val saved = reloaded.summaries.value["2026-11-15"]
        assertEquals("今天我帮你订了牛奶。", saved?.text)
        assertEquals(1, saved?.entryCount)
        assertTrue(saved!!.updatedAt > 0)

        reloaded.add("memory", "记住偏好")
        val again = DiaryStore(file)
        assertEquals(2, again.entries.value.size)
        assertEquals("今天我帮你订了牛奶。", again.summaries.value["2026-11-15"]?.text)
    }

    @Test fun `putSummary replaces the recap for the same day`() {
        val store = DiaryStore(file())
        store.putSummary("2026-11-15", "第一版", 1)
        store.putSummary("2026-11-15", "第二版", 3)
        assertEquals(1, store.summaries.value.size)
        assertEquals("第二版", store.summaries.value["2026-11-15"]?.text)
        assertEquals(3, store.summaries.value["2026-11-15"]?.entryCount)
    }

    @Test fun `blank summaries are ignored and clear resets both lists`() {
        val file = file()
        val store = DiaryStore(file)
        store.add("note", "一条记录")
        store.putSummary("2026-11-15", "   ", 1)
        assertTrue(store.summaries.value.isEmpty())
        store.putSummary("2026-11-15", "有内容", 1)
        assertTrue(store.summaries.value.isNotEmpty())

        store.clear()
        assertTrue(store.entries.value.isEmpty())
        assertTrue(store.summaries.value.isEmpty())
        assertEquals(emptyMap(), DiaryStore(file).summaries.value)
    }
}
