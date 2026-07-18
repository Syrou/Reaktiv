package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.protocol.aggregateLogicStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateThreadStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogicTraceStatsTest {

    private fun start(
        callId: String,
        logicClass: String,
        methodName: String,
        timestampMs: Long = 0L,
        thread: String? = null,
        dispatcher: String? = null
    ) = LogicMethodStart(
        logicClass = logicClass,
        methodName = methodName,
        params = emptyMap(),
        callId = callId,
        timestampMs = timestampMs,
        thread = thread,
        dispatcher = dispatcher
    )

    private fun completed(callId: String, durationMs: Long) = LogicMethodCompleted(
        callId = callId,
        result = "ok",
        resultType = "String",
        durationMs = durationMs,
        timestampMs = durationMs
    )

    private fun failed(callId: String, durationMs: Long) = LogicMethodFailed(
        callId = callId,
        exceptionType = "IllegalStateException",
        exceptionMessage = "boom",
        stackTrace = null,
        durationMs = durationMs,
        timestampMs = durationMs
    )

    @Test
    fun `aggregates calls durations and sorts by total time descending`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "UserLogic", "fetchUser"),
                start("a2", "UserLogic", "fetchUser"),
                start("b1", "NavigationGuards", "guard(workspace)")
            ),
            completed = listOf(
                completed("a1", 100L),
                completed("a2", 300L),
                completed("b1", 50L)
            ),
            failed = emptyList()
        )

        assertEquals(2, stats.size)
        assertEquals("UserLogic.fetchUser", stats[0].methodIdentifier)
        assertEquals(2, stats[0].calls)
        assertEquals(400L, stats[0].totalMs)
        assertEquals(200L, stats[0].avgMs)
        assertEquals(300L, stats[0].maxMs)
        assertEquals(0, stats[0].failures)

        assertEquals("NavigationGuards.guard(workspace)", stats[1].methodIdentifier)
        assertEquals(50L, stats[1].totalMs)
    }

    @Test
    fun `failures count toward duration and are reported separately`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "UserLogic", "fetchUser"),
                start("a2", "UserLogic", "fetchUser")
            ),
            completed = listOf(completed("a1", 100L)),
            failed = listOf(failed("a2", 700L))
        )

        assertEquals(1, stats.size)
        assertEquals(2, stats[0].calls)
        assertEquals(2, stats[0].finished)
        assertEquals(1, stats[0].failures)
        assertEquals(800L, stats[0].totalMs)
        assertEquals(700L, stats[0].maxMs)
        assertEquals(0, stats[0].inFlight)
    }

    @Test
    fun `in flight calls are starts without a finish`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "UserLogic", "observeStream"),
                start("a2", "UserLogic", "observeStream")
            ),
            completed = listOf(completed("a1", 10L)),
            failed = emptyList()
        )

        assertEquals(1, stats.size)
        assertEquals(1, stats[0].inFlight)
        assertEquals(10L, stats[0].totalMs)
    }

    @Test
    fun `finish events without a matching start are ignored`() {
        val stats = aggregateLogicStats(
            started = listOf(start("a1", "UserLogic", "fetchUser")),
            completed = listOf(
                completed("a1", 10L),
                completed("orphan", 999L)
            ),
            failed = listOf(failed("other-orphan", 999L))
        )

        assertEquals(1, stats.size)
        assertEquals(10L, stats[0].totalMs)
    }

    @Test
    fun `threads and dispatchers are collected per method`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "UserLogic", "fetchUser", thread = "main", dispatcher = "Dispatchers.Main"),
                start("a2", "UserLogic", "fetchUser", thread = "DefaultDispatcher-worker-1", dispatcher = "Dispatchers.Default")
            ),
            completed = listOf(completed("a1", 10L), completed("a2", 20L)),
            failed = emptyList()
        )

        assertEquals(setOf("main", "DefaultDispatcher-worker-1"), stats[0].threads)
        assertEquals(setOf("Dispatchers.Main", "Dispatchers.Default"), stats[0].dispatchers)
        assertTrue(stats[0].runsOnMainThread)
    }

    @Test
    fun `overlapping calls report peak concurrency and congestion`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "SyncLogic", "sync", timestampMs = 0L, thread = "w1"),
                start("a2", "SyncLogic", "sync", timestampMs = 10L, thread = "w1"),
                start("a3", "SyncLogic", "sync", timestampMs = 20L, thread = "w1"),
                start("a4", "SyncLogic", "sync", timestampMs = 500L, thread = "w1")
            ),
            completed = listOf(
                completed("a1", 100L),
                completed("a2", 100L),
                completed("a3", 100L),
                completed("a4", 100L)
            ),
            failed = emptyList()
        )

        assertEquals(3, stats[0].maxConcurrent)
        assertTrue(stats[0].isCongested)
    }

    @Test
    fun `sequential calls are not congested`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "SyncLogic", "sync", timestampMs = 0L),
                start("a2", "SyncLogic", "sync", timestampMs = 200L)
            ),
            completed = listOf(completed("a1", 100L), completed("a2", 100L)),
            failed = emptyList()
        )

        assertEquals(1, stats[0].maxConcurrent)
        assertEquals(false, stats[0].isCongested)
    }

    @Test
    fun `thread stats aggregate busy time and overlap per thread`() {
        val stats = aggregateThreadStats(
            started = listOf(
                start("a1", "UserLogic", "fetchUser", timestampMs = 0L, thread = "main"),
                start("a2", "SyncLogic", "sync", timestampMs = 10L, thread = "main"),
                start("b1", "SyncLogic", "sync", timestampMs = 0L, thread = "DefaultDispatcher-worker-1")
            ),
            completed = listOf(
                completed("a1", 50L),
                completed("a2", 50L),
                completed("b1", 30L)
            ),
            failed = emptyList()
        )

        assertEquals(2, stats.size)
        val main = stats.first { it.thread == "main" }
        assertEquals(2, main.calls)
        assertEquals(100L, main.busyMs)
        assertEquals(2, main.maxConcurrent)
        assertTrue(main.isMain)

        val worker = stats.first { it.thread == "DefaultDispatcher-worker-1" }
        assertEquals(30L, worker.busyMs)
        assertEquals(false, worker.isMain)
    }

    @Test
    fun `methods with no finished calls report zero durations`() {
        val stats = aggregateLogicStats(
            started = listOf(start("a1", "UserLogic", "longRunning")),
            completed = emptyList(),
            failed = emptyList()
        )

        assertEquals(1, stats.size)
        assertEquals(0L, stats[0].totalMs)
        assertEquals(0L, stats[0].avgMs)
        assertEquals(1, stats[0].inFlight)
    }
}
