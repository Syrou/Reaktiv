package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.protocol.aggregateDispatchStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateLogicStats
import io.github.syrou.reaktiv.devtools.protocol.aggregateStalls
import io.github.syrou.reaktiv.devtools.protocol.aggregateThreadStats
import io.github.syrou.reaktiv.devtools.protocol.stallCulprits
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
    fun `congestion on one thread reports interleaving reason`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "SyncLogic", "sync", timestampMs = 0L, thread = "w1"),
                start("a2", "SyncLogic", "sync", timestampMs = 10L, thread = "w1"),
                start("a3", "SyncLogic", "sync", timestampMs = 20L, thread = "w1")
            ),
            completed = listOf(completed("a1", 100L), completed("a2", 100L), completed("a3", 100L)),
            failed = emptyList()
        )

        assertEquals(setOf("w1"), stats[0].peakThreads)
        assertTrue(stats[0].congestionReason!!.contains("interleaving on w1"))
    }

    @Test
    fun `source location is carried onto method stats`() {
        val located = LogicMethodStart(
            logicClass = "UserLogic",
            methodName = "fetchUser",
            params = emptyMap(),
            callId = "a1",
            timestampMs = 0L,
            sourceFile = "src/main/UserLogic.kt",
            lineNumber = 42
        )
        val stats = aggregateLogicStats(
            started = listOf(located),
            completed = listOf(completed("a1", 10L)),
            failed = emptyList()
        )

        assertEquals("UserLogic.kt:42", stats[0].location)
        assertEquals("UserLogic.fetchUser (UserLogic.kt:42)", stats[0].labelWithLocation)
    }

    @Test
    fun `thread contention names the competing methods`() {
        val stats = aggregateThreadStats(
            started = listOf(
                start("a1", "UserLogic", "fetchUser", timestampMs = 0L, thread = "main"),
                start("a2", "SyncLogic", "sync", timestampMs = 5L, thread = "main"),
                start("a3", "NewsLogic", "load", timestampMs = 10L, thread = "main")
            ),
            completed = listOf(completed("a1", 100L), completed("a2", 100L), completed("a3", 100L)),
            failed = emptyList()
        )

        val main = stats.first { it.thread == "main" }
        assertEquals(3, main.maxConcurrent)
        assertEquals(
            setOf("UserLogic.fetchUser", "SyncLogic.sync", "NewsLogic.load"),
            main.contenders
        )
        assertTrue(main.contentionReason!!.contains("UserLogic.fetchUser"))
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
    fun `stall culprits are main-thread methods running during the freeze window`() {
        val stallStart = LogicMethodStart(
            logicClass = "MainThreadWatchdog",
            methodName = "stall",
            params = emptyMap(),
            callId = "stall-1",
            timestampMs = 1400L
        )
        val stallCompleted = LogicMethodCompleted(
            callId = "stall-1",
            result = "recovered",
            resultType = "Stall",
            durationMs = 400L,
            timestampMs = 1400L
        )
        val culprits = stallCulprits(
            started = listOf(
                stallStart,
                start("b1", "ImageLogic", "decode", timestampMs = 1100L, thread = "main"),
                start("i1", "SyncLogic", "sync", timestampMs = 200L, thread = "DefaultDispatcher-worker-1")
            ),
            completed = listOf(
                stallCompleted,
                completed("b1", 500L),
                completed("i1", 10L)
            ),
            failed = emptyList()
        )

        assertEquals(1, culprits.size)
        assertEquals("ImageLogic.decode", culprits[0].methodIdentifier)
    }

    @Test
    fun `failure exception is carried onto method stats`() {
        val stats = aggregateLogicStats(
            started = listOf(start("a1", "UserLogic", "fetchUser")),
            completed = emptyList(),
            failed = listOf(
                LogicMethodFailed(
                    callId = "a1",
                    exceptionType = "HttpException",
                    exceptionMessage = "401 Unauthorized",
                    stackTrace = "at ...",
                    durationMs = 30L,
                    timestampMs = 30L
                )
            )
        )

        assertEquals("HttpException", stats[0].failureType)
        assertEquals("401 Unauthorized", stats[0].failureMessage)
        assertEquals("HttpException: 401 Unauthorized", stats[0].failureSummary)
    }

    @Test
    fun `stall culprit that started before the freeze but ran into it is caught`() {
        val stallStart = LogicMethodStart(
            logicClass = "MainThreadWatchdog",
            methodName = "stall",
            params = emptyMap(),
            callId = "stall-1",
            timestampMs = 1400L
        )
        val stallCompleted = LogicMethodCompleted(
            callId = "stall-1",
            result = "recovered",
            resultType = "Stall",
            durationMs = 400L,
            timestampMs = 1400L
        )
        val culprits = stallCulprits(
            started = listOf(
                stallStart,
                start("b1", "ImageLogic", "decode", timestampMs = 900L, thread = "main")
            ),
            completed = listOf(stallCompleted, completed("b1", 300L)),
            failed = emptyList()
        )

        assertEquals(1, culprits.size)
        assertEquals("ImageLogic.decode", culprits[0].methodIdentifier)
    }

    @Test
    fun `stalls group by captured stack into distinct causes`() {
        fun stall(callId: String, stack: String, durationMs: Long, ts: Long) = Pair(
            LogicMethodStart(
                logicClass = "MainThreadWatchdog",
                methodName = "stall",
                params = mapOf("stack" to stack),
                callId = callId,
                timestampMs = ts
            ),
            LogicMethodCompleted(
                callId = callId,
                result = "recovered",
                resultType = "Stall",
                durationMs = durationMs,
                timestampMs = ts
            )
        )

        val a = stall("s1", "at ImageDecoder.decode", 200L, 100L)
        val b = stall("s2", "at ImageDecoder.decode", 450L, 200L)
        val c = stall("s3", "at JsonParser.parse", 300L, 300L)

        val groups = aggregateStalls(
            started = listOf(a.first, b.first, c.first),
            completed = listOf(a.second, b.second, c.second)
        )

        assertEquals(2, groups.size)
        assertEquals("at ImageDecoder.decode", groups[0].stack)
        assertEquals(2, groups[0].count)
        assertEquals(450L, groups[0].worstMs)
        assertEquals("at JsonParser.parse", groups[1].stack)
        assertEquals(1, groups[1].count)
    }

    @Test
    fun `stall culprits are empty without a stall event`() {
        val culprits = stallCulprits(
            started = listOf(start("a1", "UserLogic", "fetchUser", thread = "main")),
            completed = listOf(completed("a1", 10L)),
            failed = emptyList()
        )
        assertTrue(culprits.isEmpty())
    }

    @Test
    fun `dispatch stats aggregate queue metrics from StoreDispatch events`() {
        val dispatchStart = LogicMethodStart(
            logicClass = "StoreDispatch",
            methodName = "Increment",
            params = mapOf("queueWaitMs" to "120", "queueDepth" to "4"),
            callId = "d1",
            timestampMs = 0L
        )
        val otherStart = start("x1", "UserLogic", "fetchUser")

        val stats = aggregateDispatchStats(
            started = listOf(dispatchStart, otherStart),
            completed = listOf(completed("d1", 5L), completed("x1", 50L)),
            failed = emptyList()
        )

        requireNotNull(stats)
        assertEquals(1, stats.processedActions)
        assertEquals(120L, stats.avgQueueWaitMs)
        assertEquals(120L, stats.maxQueueWaitMs)
        assertEquals(4, stats.maxQueueDepth)
        assertEquals(5L, stats.totalProcessMs)
        assertTrue(stats.isCongested)
    }

    @Test
    fun `dispatch stats are null without StoreDispatch events`() {
        val stats = aggregateDispatchStats(
            started = listOf(start("x1", "UserLogic", "fetchUser")),
            completed = listOf(completed("x1", 50L)),
            failed = emptyList()
        )
        assertEquals(null, stats)
    }

    @Test
    fun `duplicate events for the same callId are counted once`() {
        val stats = aggregateLogicStats(
            started = listOf(
                start("a1", "NewsLogic", "countDown"),
                start("a1", "NewsLogic", "countDown")
            ),
            completed = listOf(
                completed("a1", 10_000L),
                completed("a1", 10_000L)
            ),
            failed = emptyList()
        )

        assertEquals(1, stats.size)
        assertEquals(1, stats[0].calls)
        assertEquals(1, stats[0].finished)
        assertEquals(0, stats[0].inFlight)
        assertEquals(10_000L, stats[0].totalMs)
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
