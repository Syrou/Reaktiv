package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.devtools.protocol.FindingSeverity
import io.github.syrou.reaktiv.devtools.protocol.StateSizeTracker
import io.github.syrou.reaktiv.devtools.protocol.aggregateChurn
import io.github.syrou.reaktiv.devtools.protocol.computeFindings
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.syrou.reaktiv.devtools.protocol.DISPATCH_TRACE_CLASS
import kotlin.test.assertNotNull
import io.github.syrou.reaktiv.devtools.protocol.DISPATCH_QUEUE_WAIT_WARN_MS
import io.github.syrou.reaktiv.devtools.protocol.PERFORMANCE_FINDING_CATEGORIES
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
    fun `a stall carries the main thread stack it was caught with`() {
        val starts = listOf(
            start("MainThreadWatchdog", "stall", "stall-1", 1000, params = mapOf("stack" to "at Blocker.run"))
        )
        val completions = listOf(completed("stall-1", 700, 1700))

        val stall = computeFindings(starts, completions).single { it.category == "stall" }

        assertEquals(listOf("at Blocker.run"), stall.stacks.map { it.stack })
    }

    @Test
    fun `a method running three calls at once is a congestion finding naming it`() {
        val starts = listOf(
            start("SyncLogic", "sync", "a1", 0, thread = "w1"),
            start("SyncLogic", "sync", "a2", 10, thread = "w1"),
            start("SyncLogic", "sync", "a3", 20, thread = "w1")
        )
        val completions = listOf(completed("a1", 100, 100), completed("a2", 100, 110), completed("a3", 100, 120))

        val congestion = computeFindings(starts, completions).single { it.category == "congestion" }

        assertEquals(listOf("SyncLogic.sync"), congestion.culprits)
        assertTrue(congestion.detail.contains("interleaving on w1"))
    }

    @Test
    fun `different methods crowding one thread are a contention finding naming them`() {
        val starts = listOf(
            start("UserLogic", "fetchUser", "a1", 0, thread = "main"),
            start("SyncLogic", "sync", "a2", 5, thread = "main"),
            start("NewsLogic", "load", "a3", 10, thread = "main")
        )
        val completions = listOf(completed("a1", 100, 100), completed("a2", 100, 105), completed("a3", 100, 110))

        val contention = computeFindings(starts, completions).single { it.category == "contention" }

        assertEquals(setOf("UserLogic.fetchUser", "SyncLogic.sync", "NewsLogic.load"), contention.culprits.toSet())
    }

    @Test
    fun `every performance finding explains its meaning impact and fix`() {
        val starts = listOf(
            start("MainThreadWatchdog", "stall", "stall-1", 1000),
            start("SyncLogic", "sync", "a1", 0, thread = "w1"),
            start("SyncLogic", "sync", "a2", 10, thread = "w1"),
            start("SyncLogic", "sync", "a3", 20, thread = "w1"),
            start(
                DISPATCH_TRACE_CLASS, "Tick", "d1", 30,
                params = mapOf("queueWaitMs" to "${DISPATCH_QUEUE_WAIT_WARN_MS + 1}")
            )
        )
        val completions = listOf(
            completed("stall-1", 700, 1700),
            completed("a1", 100, 100),
            completed("a2", 100, 110),
            completed("a3", 100, 120)
        )

        val performance = computeFindings(starts, completions).filter { it.category in PERFORMANCE_FINDING_CATEGORIES }

        assertTrue(performance.map { it.category }.containsAll(listOf("stall", "congestion", "contention", "dispatch-latency")))
        performance.forEach { finding ->
            val advice = assertNotNull(finding.advice, "${finding.category} has no advice")
            assertTrue(advice.meaning.isNotBlank() && advice.impact.isNotBlank() && advice.fix.isNotBlank())
        }
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

    private fun exchange(
        id: String,
        url: String = "https://api.example.com/user",
        startedAtMs: Long = 1_000,
        decodeError: String? = null
    ) = NetworkRequestCapture(
        id = id,
        startedAtMs = startedAtMs,
        durationMs = 25,
        method = "GET",
        url = url,
        responseStatus = 200,
        decodeError = decodeError
    )

    @Test
    fun `decode failures surface as critical findings`() {
        val network = listOf(
            exchange("n1"),
            exchange("n2", decodeError = "JsonDecodingException: Unexpected null for name")
        )

        val findings = computeFindings(emptyList(), emptyList(), network = network)

        val decode = findings.single { it.category == "network-decode" }
        assertEquals(FindingSeverity.CRITICAL, decode.severity)
        assertTrue(decode.detail.contains("https://api.example.com/user"))
        assertTrue(decode.detail.contains("Unexpected null for name"))
        assertEquals(1_025L, decode.timestampMs)
    }

    @Test
    fun `repeated decode failures on one endpoint collapse into a single finding`() {
        val network = (1..4).map {
            exchange("n$it", startedAtMs = it * 100L, decodeError = "MissingFieldException: name")
        }

        val findings = computeFindings(emptyList(), emptyList(), network = network)

        val decode = findings.single { it.category == "network-decode" }
        assertTrue(decode.title.contains("4 times"), decode.title)
    }

    @Test
    fun `distinct decode failures stay separate findings`() {
        val network = listOf(
            exchange("n1", decodeError = "MissingFieldException: name"),
            exchange("n2", url = "https://api.example.com/orders", decodeError = "MissingFieldException: total")
        )

        val findings = computeFindings(emptyList(), emptyList(), network = network)

        assertEquals(2, findings.count { it.category == "network-decode" })
    }
}
