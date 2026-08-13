package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.devtools.protocol.FindingSeverity
import io.github.syrou.reaktiv.devtools.protocol.StateSizeTracker
import io.github.syrou.reaktiv.devtools.protocol.aggregateChurn
import io.github.syrou.reaktiv.devtools.protocol.computeFindings
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingsTest {

    private fun start(
        logicClass: String,
        methodName: String,
        callId: String,
        timestampMs: Long,
        params: Map<String, String> = emptyMap(),
        thread: String? = null,
        sourceFile: String? = null,
        lineNumber: Int? = null
    ) = LogicMethodStart(
        logicClass = logicClass,
        methodName = methodName,
        params = params,
        callId = callId,
        timestampMs = timestampMs,
        thread = thread,
        sourceFile = sourceFile,
        lineNumber = lineNumber
    )

    private fun completed(callId: String, durationMs: Long, timestampMs: Long) =
        LogicMethodCompleted(
            callId = callId,
            result = null,
            resultType = "Unit",
            durationMs = durationMs,
            timestampMs = timestampMs
        )

    private fun action(module: String, index: Int) = CapturedAction(
        clientId = "c",
        timestamp = index.toLong(),
        actionType = "A$index",
        actionData = "",
        stateDeltaJson = "{}",
        moduleName = module,
        deltaKind = DeltaKind.FIELDS
    )

    @Test
    fun `a stall names the overlapping main thread logic span as culprit`() {
        val starts = listOf(
            start("MainThreadWatchdog", "stall", "stall-1", 1000),
            start(
                "com.example.NewsLogic", "countDown", "logic-1", 900,
                thread = "main", sourceFile = "NewsLogic.kt", lineNumber = 42
            ),
            start("com.example.OtherLogic", "background", "logic-2", 900, thread = "DefaultDispatcher-worker-1")
        )
        val completions = listOf(
            completed("stall-1", 800, 1900),
            completed("logic-1", 950, 1850),
            completed("logic-2", 900, 1800)
        )

        val findings = computeFindings(starts, completions)

        val stall = findings.single { it.category == "stall" }
        assertEquals(FindingSeverity.CRITICAL, stall.severity)
        assertTrue(stall.title.contains("800ms"))
        assertTrue(stall.detail.contains("NewsLogic.countDown"))
        assertEquals("NewsLogic.kt", stall.sourceFile)
        assertEquals(42, stall.lineNumber)
    }

    @Test
    fun `a hottest frame from stack sampling wins over span correlation`() {
        val starts = listOf(
            start("MainThreadWatchdog", "stall", "stall-1", 1000, params = mapOf("hottestFrame" to "at HotFrame"))
        )
        val completions = listOf(completed("stall-1", 500, 1500))

        val findings = computeFindings(starts, completions)
        assertTrue(findings.single { it.category == "stall" }.detail.contains("at HotFrame"))
    }

    @Test
    fun `slow reducer phases become critical findings`() {
        val starts = listOf(
            start("DispatchPhase", "reducer", "p1", 100, params = mapOf("actionType" to "Increment")),
            start("DispatchPhase", "captureMiddleware[0]", "p2", 100, params = mapOf("actionType" to "Increment"))
        )
        val completions = listOf(
            completed("p1", 12, 112),
            completed("p2", 3, 115)
        )

        val findings = computeFindings(starts, completions)

        val reducer = findings.single { it.category == "dispatch-phase" }
        assertEquals(FindingSeverity.CRITICAL, reducer.severity)
        assertTrue(reducer.title.contains("reducer"))
        assertTrue(reducer.detail.contains("Increment"))
    }

    @Test
    fun `queue wait warnings aggregate and name the worst dispatch`() {
        val starts = listOf(
            start("StoreDispatch", "SlowAction", "d1", 100, params = mapOf("queueWaitMs" to "250")),
            start("StoreDispatch", "FastAction", "d2", 100, params = mapOf("queueWaitMs" to "3")),
            start("StoreDispatch", "MediumAction", "d3", 100, params = mapOf("queueWaitMs" to "120"))
        )

        val findings = computeFindings(starts, emptyList())

        val latency = findings.single { it.category == "dispatch-latency" }
        assertTrue(latency.title.contains("2 dispatches"))
        assertTrue(latency.detail.contains("SlowAction"))
    }

    @Test
    fun `a dispatch storm names the origin of the burst`() {
        val starts = (0 until 25).map { index ->
            start(
                "StoreDispatch", "ScrollTick", "d$index", 1000L + index * 10,
                params = if (index == 20) {
                    mapOf("dispatchedFrom" to "scrollHandler (Feed.kt:88)")
                } else {
                    emptyMap()
                }
            )
        }

        val findings = computeFindings(starts, emptyList())

        val storm = findings.single { it.category == "dispatch-storm" }
        assertTrue(storm.title.contains("ScrollTick"))
        assertTrue(storm.title.contains("25"))
        assertTrue(storm.detail.contains("Feed.kt:88"))
    }

    @Test
    fun `suspicious module growth names the fastest growing field`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.CacheState":{"type":"t","items":"a","count":1}}""")
        var payload = "a"
        repeat(12) { index ->
            payload += "xxxxxxxxxx".repeat(index + 1)
            tracker.feed(
                action("com.example.CacheState", index).copy(
                    stateDeltaJson = """{"type":"t","items":"$payload"}"""
                )
            )
        }

        val sizes = tracker.snapshot()
        val findings = computeFindings(emptyList(), emptyList(), sizes = sizes)

        val size = findings.single { it.category == "state-size" }
        assertTrue(size.detail.contains("items"))
    }

    @Test
    fun `churn ranks composables by state change volume`() {
        val actions = (0 until 60).map { action("com.example.FeedState", it) } +
            (0 until 5).map { action("com.example.SettingsState", it) }
        val reads = listOf(
            StateRead(stateClass = "com.example.FeedState", composable = "com.example.ui.FeedList"),
            StateRead(stateClass = "com.example.SettingsState", composable = "com.example.ui.SettingsPane")
        )

        val churn = aggregateChurn(actions, reads)
        assertEquals("com.example.ui.FeedList", churn.first().composable)
        assertEquals(60, churn.first().changeEvents)

        val findings = computeFindings(emptyList(), emptyList(), churn = churn)
        val recomposition = findings.single { it.category == "recomposition" }
        assertTrue(recomposition.title.contains("FeedList"))
        assertTrue(recomposition.detail.contains("FeedState"))
    }
}
