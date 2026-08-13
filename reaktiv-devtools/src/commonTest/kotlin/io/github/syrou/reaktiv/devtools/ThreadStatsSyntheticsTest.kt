package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.protocol.aggregateThreadStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThreadStatsSyntheticsTest {

    private fun start(logicClass: String, methodName: String, callId: String, timestampMs: Long, thread: String) =
        LogicMethodStart(
            logicClass = logicClass,
            methodName = methodName,
            params = emptyMap(),
            callId = callId,
            timestampMs = timestampMs,
            thread = thread
        )

    private fun completed(callId: String, durationMs: Long, timestampMs: Long) =
        LogicMethodCompleted(
            callId = callId,
            result = null,
            resultType = "Unit",
            durationMs = durationMs,
            timestampMs = timestampMs
        )

    @Test
    fun `pipeline synthetics do not count as thread contention`() {
        val starts = listOf(
            start("StoreDispatch", "NewsLoading", "d1", 1000, thread = "DefaultDispatcher-worker-1"),
            start("StoreDispatch", "ServiceStatusChanged", "d2", 1005, thread = "DefaultDispatcher-worker-1"),
            start("DispatchPhase", "reducer", "p1", 1000, thread = "DefaultDispatcher-worker-1"),
            start("DispatchPhase", "captureMiddleware[0]", "p2", 1000, thread = "DefaultDispatcher-worker-1"),
            start("com.example.NewsLogic", "load", "l1", 1000, thread = "DefaultDispatcher-worker-1")
        )
        val completions = listOf(
            completed("d1", 50, 1050),
            completed("d2", 40, 1045),
            completed("p1", 10, 1010),
            completed("p2", 5, 1005),
            completed("l1", 30, 1030)
        )

        val stats = aggregateThreadStats(starts, completions, emptyList())

        val worker = stats.single { it.thread == "DefaultDispatcher-worker-1" }
        assertEquals(1, worker.calls)
        assertEquals(30, worker.busyMs)
        assertEquals(1, worker.maxConcurrent)
        assertFalse(worker.isCongested)
    }
}
