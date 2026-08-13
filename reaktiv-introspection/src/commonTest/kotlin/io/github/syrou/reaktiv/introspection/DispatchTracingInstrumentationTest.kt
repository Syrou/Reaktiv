package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DispatchTracingInstrumentationTest {

    private object DummyAction : ModuleAction()

    private class RecordingObserver : LogicObserver {
        val started = mutableListOf<LogicMethodStart>()
        val completed = mutableListOf<LogicMethodCompleted>()

        override fun onMethodStart(event: LogicMethodStart) {
            started.add(event)
        }

        override fun onMethodCompleted(event: LogicMethodCompleted) {
            completed.add(event)
        }

        override fun onMethodFailed(event: LogicMethodFailed) {}
    }

    private fun burn(ms: Long) {
        val start = currentTimeMillis()
        var spin = 0L
        while (currentTimeMillis() - start < ms) {
            spin += 1
        }
        check(spin >= 0)
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    @Test
    fun `dispatch callbacks forward as StoreDispatch spans with decorated phase children`() = runTest {
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)
        val instrumentation = DispatchTracingInstrumentation()

        assertTrue(instrumentation.isActive)

        val token = instrumentation.onDispatchStarted(DummyAction, queueWaitMs = 5, queueDepth = 2)
        val decorator = instrumentation.newDispatchDecorator()
        val reducerStep = decorator.decorate("reducer") { burn(12) }
        val middlewareStep = decorator.decorate("captureMiddleware[0]") { action ->
            burn(6)
            reducerStep(action)
        }
        val silentStep = decorator.decorate("fastMiddleware[1]") { action ->
            middlewareStep(action)
        }
        silentStep(DummyAction)
        instrumentation.onDispatchCompleted(token, applied = true, durationMs = 25)

        val dispatchStart = observer.started.single { it.logicClass == "StoreDispatch" }
        assertEquals("DummyAction", dispatchStart.methodName)
        assertEquals("5", dispatchStart.params["queueWaitMs"])
        assertEquals("2", dispatchStart.params["queueDepth"])

        val phaseStarts = observer.started.filter {
            it.logicClass == DispatchTracingInstrumentation.PHASE_TRACE_CLASS
        }
        assertEquals(setOf("reducer", "captureMiddleware[0]"), phaseStarts.map { it.methodName }.toSet())
        phaseStarts.forEach { assertEquals(token, it.parentCallId) }

        phaseStarts.forEach { phaseStart ->
            val completion = observer.completed.single { it.callId == phaseStart.callId }
            assertTrue(
                phaseStart.timestampMs + completion.durationMs <= completion.timestampMs + 2,
                "Phase spans must carry their true start time, not the emission time"
            )
        }

        val durations = phaseStarts.associate { start ->
            start.methodName to observer.completed.single { it.callId == start.callId }.durationMs
        }
        assertTrue(durations.getValue("reducer") >= 10L)
        val middlewareSelf = durations.getValue("captureMiddleware[0]")
        assertTrue(middlewareSelf >= 4L)
        assertTrue(
            middlewareSelf < durations.getValue("reducer") + 4L,
            "Middleware self time $middlewareSelf must exclude the reducer's time"
        )

        val dispatchCompletion = observer.completed.single { it.callId == token }
        assertEquals("Processed", dispatchCompletion.result)
        assertEquals(25, dispatchCompletion.durationMs)
    }

    @Test
    fun `inactive tracer makes the instrumentation report inactive`() {
        val instrumentation = DispatchTracingInstrumentation()
        assertEquals(false, instrumentation.isActive)
    }
}
