package dev.xiaoman.app.debug

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DebugCommandTest {
    private fun parse(action: String?, vararg extras: Pair<String, String>) =
        DebugCommand.parse(action, mapOf(*extras)::get) { "generated" }

    @Test fun `run with all extras`() {
        assertEquals(
            DebugCommand.Run("打开设置", "open-settings-wifi", "r1"),
            parse("dev.xiaoman.debug.RUN", "goal" to " 打开设置 ", "skill" to "open-settings-wifi", "run_id" to "r1"),
        )
    }

    @Test fun `say carries trimmed text and needs some`() {
        assertEquals(DebugCommand.Say("帮我排一下今天"), parse("dev.xiaoman.debug.SAY", "text" to " 帮我排一下今天 "))
        assertNull(parse("dev.xiaoman.debug.SAY", "text" to "  "))
    }

    @Test fun `run id and skill are optional`() {
        assertEquals(DebugCommand.Run("打开设置", null, "generated"), parse("dev.xiaoman.debug.RUN", "goal" to "打开设置", "skill" to " "))
    }

    @Test fun `missing goal is an invalid run that keeps the run id`() {
        val cmd = assertIs<DebugCommand.InvalidRun>(parse("dev.xiaoman.debug.RUN", "run_id" to "r9"))
        assertEquals("r9", cmd.runId)
    }

    @Test fun `unsafe run id is rejected`() {
        val cmd = assertIs<DebugCommand.InvalidRun>(parse("dev.xiaoman.debug.RUN", "goal" to "x", "run_id" to "../etc"))
        assertEquals("generated", cmd.runId)
    }

    @Test fun `enqueue needs a goal and keeps the optional skill`() {
        assertEquals(
            DebugCommand.Enqueue("查快递", "taobao-compare"),
            parse("dev.xiaoman.debug.ENQUEUE", "goal" to " 查快递 ", "skill" to "taobao-compare"),
        )
        assertEquals(
            DebugCommand.Enqueue("查快递", null),
            parse("dev.xiaoman.debug.ENQUEUE", "goal" to "查快递", "skill" to " "),
        )
        assertNull(parse("dev.xiaoman.debug.ENQUEUE", "skill" to "taobao-compare"))
    }

    @Test fun `queue controls need an id`() {
        assertEquals(
            DebugCommand.QueueControl(DebugCommand.QueueOp.PAUSE, "q1"),
            parse("dev.xiaoman.debug.QUEUE_PAUSE", "id" to " q1 "),
        )
        assertEquals(
            DebugCommand.QueueControl(DebugCommand.QueueOp.RESUME, "q1"),
            parse("dev.xiaoman.debug.QUEUE_RESUME", "id" to "q1"),
        )
        assertEquals(
            DebugCommand.QueueControl(DebugCommand.QueueOp.CANCEL, "q1"),
            parse("dev.xiaoman.debug.QUEUE_CANCEL", "id" to "q1"),
        )
        assertNull(parse("dev.xiaoman.debug.QUEUE_PAUSE"))
        assertEquals(DebugCommand.QueueStatus, parse("dev.xiaoman.debug.QUEUE_STATUS"))
    }

    @Test fun `stop reload and unknown actions`() {
        assertEquals(DebugCommand.Stop, parse("dev.xiaoman.debug.STOP"))
        assertEquals(DebugCommand.ReloadSkills, parse("dev.xiaoman.debug.RELOAD_SKILLS"))
        assertNull(parse("dev.xiaoman.debug.NOPE"))
        assertNull(parse(null))
    }
}
