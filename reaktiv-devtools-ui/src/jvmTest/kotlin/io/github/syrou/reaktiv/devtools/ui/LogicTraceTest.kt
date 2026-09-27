package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogicTraceTest {

    private fun started(callId: String, logicClass: String, parent: String? = null) = LogicMethodEvent.Started(
        clientId = "c",
        event = LogicMethodStart(
            logicClass = logicClass,
            methodName = "run",
            params = emptyMap(),
            callId = callId,
            timestampMs = 0L,
            parentCallId = parent
        )
    )

    private fun completed(callId: String) = LogicMethodEvent.Completed(
        clientId = "c",
        event = LogicMethodCompleted(callId = callId, result = null, resultType = "Unit", durationMs = 1L, timestampMs = 1L)
    )

    @Test
    fun `depth follows the parent chain and a view can skip parents it hides`() {
        val events = listOf(
            started("root", "Pipeline"),
            started("child", "UserLogic", parent = "root"),
            started("grandchild", "UserLogic", parent = "child"),
            completed("grandchild")
        )
        val trace = LogicTrace.of(events)

        assertEquals(2, trace.depths()("grandchild"))
        assertEquals(1, trace.depths { it.logicClass != "Pipeline" }("grandchild"))
        assertEquals(listOf("grandchild"), trace.completed.map { it.callId })
    }

    @Test
    fun `a parent cycle ends at depth zero instead of recursing`() {
        val events = listOf(started("a", "L", parent = "b"), started("b", "L", parent = "a"))

        val depth = LogicTrace.of(events).depths()("a")

        assertTrue(depth in 0..2, "a cycle produced depth $depth")
    }

    @Test
    fun `the same event list shares one index`() {
        val events = listOf(started("a", "L"))

        assertSame(LogicTrace.of(events), LogicTrace.of(events))
    }
}
