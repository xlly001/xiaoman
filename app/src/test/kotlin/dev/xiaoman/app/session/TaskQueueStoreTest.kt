package dev.xiaoman.app.session

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskQueueStoreTest {
    private fun tempFile(): File {
        val dir = Files.createTempDirectory("xiaoman-queue").toFile()
        dir.deleteOnExit()
        return File(dir, "queue.json")
    }

    @Test fun `next serves tasks first come first served`() {
        val store = TaskQueueStore(tempFile())
        store.enqueue("q1", "查快递", at = 100)
        store.enqueue("q2", "打开 Wi-Fi", at = 200)

        val first = store.next(at = 300)
        val second = store.next(at = 400)

        assertEquals("q1", first?.id)
        assertEquals(QueueState.RUNNING, first?.state)
        assertEquals("q2", second?.id)
        assertNull(store.next(at = 500))
    }

    @Test fun `higher priority jumps the queue`() {
        val store = TaskQueueStore(tempFile())
        store.enqueue("low", "整理相册", at = 100)
        store.enqueue("high", "订车票", priority = 5, at = 200)

        assertEquals("high", store.next()?.id)
    }

    @Test fun `paused tasks wait and resume returns them to the queue`() {
        val store = TaskQueueStore(tempFile())
        store.enqueue("q1", "查快递", at = 100)
        store.enqueue("q2", "记一笔账", at = 200)

        assertTrue(store.pause("q1"))
        assertEquals("q2", store.next()?.id)
        assertNull(store.next())

        assertTrue(store.resume("q1"))
        assertEquals("q1", store.next()?.id)
    }

    @Test fun `cancel drops waiting tasks only`() {
        val store = TaskQueueStore(tempFile())
        store.enqueue("q1", "查快递", at = 100)
        assertTrue(store.cancel("q1"))
        assertEquals(QueueState.CANCELLED, store.get("q1")?.state)
        assertNull(store.next())

        store.enqueue("q2", "买东西", at = 200)
        assertEquals("q2", store.next()?.id)
        assertTrue(!store.cancel("q2"))
        assertEquals(QueueState.RUNNING, store.get("q2")?.state)
    }

    @Test fun `finish records the result and survives a restart`() {
        val file = tempFile()
        val store = TaskQueueStore(file)
        store.enqueue("q1", "查快递", at = 100)
        store.next(at = 200)
        store.finish("q1", success = true, note = "已到达北京", at = 300)

        val reloaded = TaskQueueStore(file).get("q1")
        assertNotNull(reloaded)
        assertEquals(QueueState.DONE, reloaded.state)
        assertEquals("已到达北京", reloaded.note)
    }

    @Test fun `a running task becomes failed after a restart`() {
        val file = tempFile()
        val store = TaskQueueStore(file)
        store.enqueue("q1", "查快递", at = 100)
        store.next(at = 200)

        val reloaded = TaskQueueStore(file, interruptedNote = "应用重启，任务中断")
        val task = reloaded.get("q1")
        assertNotNull(task)
        assertEquals(QueueState.FAILED, task.state)
        assertEquals("应用重启，任务中断", task.note)
        assertEquals(listOf("q1"), reloaded.interruptedIds)
    }

    @Test fun `active tasks are capped but finished ones are not counted`() {
        val file = tempFile()
        val store = TaskQueueStore(file, maxActive = 1)
        assertNotNull(store.enqueue("q1", "查快递", at = 100))
        assertNull(store.enqueue("q2", "再查一个", at = 200))

        store.next()
        store.finish("q1", success = true)
        assertNotNull(store.enqueue("q2", "再查一个", at = 300))
    }

    @Test fun `requeue puts an unstarted task back`() {
        val store = TaskQueueStore(tempFile())
        store.enqueue("q1", "查快递", at = 100)
        store.next()
        assertTrue(store.requeue("q1"))
        assertEquals(QueueState.QUEUED, store.get("q1")?.state)
        assertEquals("q1", store.next()?.id)
    }
}
