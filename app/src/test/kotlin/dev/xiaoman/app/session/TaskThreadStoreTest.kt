package dev.xiaoman.app.session

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskThreadStoreTest {
    private fun tempFile(): File {
        val dir = Files.createTempDirectory("xiaoman-tasks").toFile()
        dir.deleteOnExit()
        return File(dir, "tasks.json")
    }

    @Test fun `thread survives a restart with steps and result`() {
        val file = tempFile()
        val store = TaskThreadStore(file)
        store.start("t1", "买牛奶", at = 100)
        store.append("t1", ChatItem.Progress(1, "打开超市 App", true))
        store.append("t1", ChatItem.Progress(2, "下单", true))
        store.finish("t1", success = true, summary = "已下单", steps = 2, at = 200)

        val reloaded = TaskThreadStore(file).get("t1")
        assertNotNull(reloaded)
        assertEquals("买牛奶", reloaded.goal)
        assertEquals(2, reloaded.steps)
        assertEquals("已下单", reloaded.summary)
        assertTrue(reloaded.success == true)
        assertEquals(2, reloaded.items.size)
    }

    @Test fun `checkpoint keeps the last steps across a restart`() {
        val file = tempFile()
        val store = TaskThreadStore(file)
        store.start("t1", "查快递", at = 100)
        store.checkpoint("t1", 1, "1. 点击 快递：成功", at = 200)
        store.checkpoint("t1", 2, "2. 输入 123：成功", at = 300)

        val checkpoint = TaskThreadStore(file).get("t1")?.checkpoint
        assertNotNull(checkpoint)
        assertEquals(2, checkpoint.steps)
        assertEquals(listOf("1. 点击 快递：成功", "2. 输入 123：成功"), checkpoint.history)
    }

    @Test fun `checkpoint history keeps only the newest lines`() {
        val store = TaskThreadStore(tempFile())
        store.start("t1", "查快递", at = 100)
        repeat(15) { index -> store.checkpoint("t1", index + 1, "第 ${index + 1} 步") }

        val history = store.get("t1")?.checkpoint?.history.orEmpty()
        assertEquals(12, history.size)
        assertEquals("第 4 步", history.first())
        assertEquals("第 15 步", history.last())
    }

    @Test fun `append to an unknown task is ignored`() {
        val store = TaskThreadStore(tempFile())
        store.append("missing", ChatItem.Progress(1, "step"))
        assertNull(store.get("missing"))
        assertTrue(store.threads.value.isEmpty())
    }
}
