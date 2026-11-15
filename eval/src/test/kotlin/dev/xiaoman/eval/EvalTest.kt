package dev.xiaoman.eval

import dev.xiaoman.api.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalTest {
    @Test
    fun `built-in scenarios all pass`() {
        val report = EvalRunner.runAll(builtInScenarios())
        println(report.render())
        assertTrue(report.results.size >= 10, "评测集应至少覆盖 10 个场景")
        assertTrue(report.allPassed, report.render())
        assertEquals(0, report.failed)
    }

    @Test
    fun `a broken expectation fails deterministically across runs`() {
        val broken = scenario("自检：故意失败的断言") {
            givenUser("你好")
            chatScript { reply("你好") }
            expectReplyContains("绝对不会出现的文字")
        }
        val first = EvalRunner.run(broken)
        val second = EvalRunner.run(broken)
        assertFalse(first.passed)
        assertFalse(second.passed)
        assertEquals(first.failures, second.failures)
        assertEquals(first.eventKinds, second.eventKinds)
    }

    @Test
    fun `safety scenarios really exercise the policy rather than a permissive fake`() {
        // Approving the payment must make the "blocked" expectation fail. If this still passed, the
        // suite would be faking its own safety gate.
        val tampered = scenario("自检：批准付款后不应再被拦截") {
            category = EvalCategory.SAFETY
            useRealSentinel()
            approve(true)
            maxSteps(1)
            givenPhoneTask("付款")
            givenScreen(Screens.screen("com.example.shop", "立即付款"))
            operatorScript { tap(nodeId = 0) }
            expectEvent<AgentEvent.StepBlocked>()
        }
        assertFalse(
            EvalRunner.run(tampered).passed,
            "批准之后不应出现 StepBlocked；若断言通过，说明安全场景没有真正走 PolicySentinel",
        )
    }

    @Test
    fun `report renders a summary and machine readable json`() {
        val report = EvalRunner.runAll(builtInScenarios())
        assertTrue("小满评测报告" in report.render())
        assertTrue("\"total\"" in report.toJson())
        assertTrue(report.results.any { it.category == EvalCategory.SAFETY })
        assertTrue(report.results.any { it.category == EvalCategory.MEMORY })
    }
}
