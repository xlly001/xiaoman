package dev.xiaoman.app.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryStoreTest {
    private fun file() = Files.createTempDirectory("chat-history").resolve("history.json").toFile()

    private val items = listOf(
        ChatItem.User(1, "帮我买牛奶"),
        ChatItem.Progress(2, "使用技能 takeout-reorder"),
        ChatItem.Assistant(3, "好的，已经记下。"),
        ChatItem.Question(4, "要买几盒？", takeover = false),
        ChatItem.Error(5, "网络失败"),
    )

    @Test fun `missing file loads an empty history`() {
        assertEquals(emptyList(), ChatHistoryStore(file()).load())
    }

    @Test fun `every item type round trips`() {
        val file = file()
        val store = ChatHistoryStore(file)
        assertTrue(store.save(items))
        assertEquals(items, ChatHistoryStore(file).load())
    }

    @Test fun `load keeps the newest entries within the limit`() {
        val file = file()
        ChatHistoryStore(file).save(items)
        assertEquals(items.takeLast(2), ChatHistoryStore(file).load(limit = 2))
    }

    @Test fun `clear removes the file and a corrupt file loads empty`() {
        val file = file()
        val store = ChatHistoryStore(file)
        store.save(items)
        assertTrue(store.clear())
        assertEquals(emptyList(), store.load())

        file.parentFile?.mkdirs()
        file.writeText("{\"items\":[")
        assertEquals(emptyList(), ChatHistoryStore(file).load())
    }
}
