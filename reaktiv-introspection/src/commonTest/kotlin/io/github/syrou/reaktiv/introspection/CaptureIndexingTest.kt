@file:Suppress("DEPRECATION")

package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.DispatchDropReason
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.tracing.DispatchOriginTracker
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureIndexingTest {

    private fun capture(maxActions: Int? = null, maxLogicEvents: Int? = null) =
        SessionCapture(maxActions = maxActions, maxLogicEvents = maxLogicEvents).also {
            it.start("test-client", "TestApp", "Test")
        }

    private fun action(index: Int) = CapturedAction(
        clientId = "test-client",
        timestamp = 1000L + index,
        actionType = "TestAction$index",
        actionData = "data-$index",
        stateDeltaJson = "{}",
        moduleName = "TestModule"
    )

    @Test
    fun `a marker points at the action it followed even after older actions were trimmed`() = runTest {
        val capture = capture(maxActions = 4)
        for (i in 1..10) capture.captureAction(action(i))

        capture.addMarker("after the tenth action")
        val history = capture.getSessionHistory()

        val marker = history.markers.single()
        assertEquals("TestAction10", history.actions[marker.afterActionIndex].actionType)
    }

    @Test
    fun `trimming logic events never keeps a completion whose start was dropped`() = runTest {
        val capture = capture(maxLogicEvents = 10)
        for (i in 1..20) {
            capture.captureLogicStarted(
                LogicMethodStart(
                    logicClass = "TestLogic",
                    methodName = "method$i",
                    params = emptyMap(),
                    callId = "call-$i",
                    timestampMs = 1000L + i
                )
            )
            capture.captureLogicCompleted(
                LogicMethodCompleted(
                    callId = "call-$i",
                    result = "ok",
                    resultType = "String",
                    durationMs = 1L,
                    timestampMs = 1000L + i
                )
            )
        }

        val history = capture.getSessionHistory()

        val startedIds = history.logicStarted.map { it.callId }.toSet()
        assertTrue(history.logicCompleted.isNotEmpty())
        assertTrue(
            history.logicCompleted.all { it.callId in startedIds },
            "orphaned completions: ${history.logicCompleted.map { it.callId } - startedIds}"
        )
        assertTrue(history.logicStarted.size + history.logicCompleted.size <= 10 + 10 / 4)
    }

    private data object Ping : ModuleAction(CaptureIndexingTest::class)

    @Test
    fun `a dropped dispatch uses up the origin recorded for it`() = runTest {
        val observer = object : LogicObserver {
            override fun onMethodStart(event: LogicMethodStart) = Unit
            override fun onMethodCompleted(event: LogicMethodCompleted) = Unit
            override fun onMethodFailed(event: LogicMethodFailed) = Unit
        }
        LogicTracer.addObserver(observer)
        try {
            DispatchOriginTracker.record(Ping, "Screen.kt:12")

            DispatchTracingInstrumentation().onDispatchDropped(Ping, DispatchDropReason.EXTERNAL_CONTROL)

            assertNull(DispatchOriginTracker.consume(Ping))
        } finally {
            LogicTracer.removeObserver(observer)
            DispatchOriginTracker.clear()
        }
    }
}
