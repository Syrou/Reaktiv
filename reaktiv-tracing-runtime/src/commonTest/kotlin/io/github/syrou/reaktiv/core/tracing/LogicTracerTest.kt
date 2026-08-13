package io.github.syrou.reaktiv.core.tracing

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class LogicTracerTest {

    private class RecordingObserver : LogicObserver {
        val started = mutableListOf<LogicMethodStart>()

        override fun onMethodStart(event: LogicMethodStart) {
            started.add(event)
        }

        override fun onMethodCompleted(event: LogicMethodCompleted) {}

        override fun onMethodFailed(event: LogicMethodFailed) {}
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    @Test
    fun `nested trace calls in the same coroutine link parent to child`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            val outer = LogicTracer.notifyMethodStart("OuterLogic", "outer", emptyMap())
            val inner = LogicTracer.notifyMethodStart("InnerLogic", "inner", emptyMap())
            LogicTracer.notifyMethodCompleted(inner, null, "Unit", 0L)
            LogicTracer.notifyMethodCompleted(outer, null, "Unit", 0L)
            val sibling = LogicTracer.notifyMethodStart("OuterLogic", "later", emptyMap())
            LogicTracer.notifyMethodCompleted(sibling, null, "Unit", 0L)

            val byCallId = observer.started.associateBy { it.callId }
            assertEquals(null, byCallId[outer]?.parentCallId)
            assertEquals(outer, byCallId[inner]?.parentCallId)
            assertEquals(null, byCallId[sibling]?.parentCallId)
        }

    @Test
    fun `a failed call also unwinds the parent stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            val outer = LogicTracer.notifyMethodStart("OuterLogic", "outer", emptyMap())
            LogicTracer.notifyMethodFailed(outer, IllegalStateException("boom"), 1L)
            val next = LogicTracer.notifyMethodStart("OuterLogic", "next", emptyMap())
            LogicTracer.notifyMethodCompleted(next, null, "Unit", 0L)

            val byCallId = observer.started.associateBy { it.callId }
            assertEquals(null, byCallId[next]?.parentCallId)
        }
}
