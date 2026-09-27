package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TracedProbeLogic : ModuleLogic() {

    suspend fun returningMethod(input: Int): Int = input * 2

    suspend fun unitMethod() {
        probeCounter += 1
    }

    var probeCounter: Int = 0
        private set
}

private class EarlyReturnLogic : ModuleLogic() {

    var reachedEnd: Boolean = false
        private set

    suspend fun skipWhenAsked(skip: Boolean) {
        if (skip) return
        reachedEnd = true
    }
}

class TracedLogicInstrumentationTest {

    @Test
    fun aTracedUnitMethodThatReturnsEarlyStillReportsCompletion() = runTest {
        val started = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val observer = object : LogicObserver {
            override fun onMethodStart(event: LogicMethodStart) {
                if (event.methodName == "skipWhenAsked") started += event.callId
            }

            override fun onMethodCompleted(event: LogicMethodCompleted) {
                completed += event.callId
            }

            override fun onMethodFailed(event: LogicMethodFailed) = Unit
        }
        LogicTracer.addObserver(observer)
        try {
            val logic = EarlyReturnLogic()
            logic.skipWhenAsked(skip = true)
            logic.skipWhenAsked(skip = false)

            assertEquals(2, started.size)
            assertTrue(completed.containsAll(started), "started $started, completed $completed")
        } finally {
            LogicTracer.removeObserver(observer)
        }
    }

    @Test
    fun tracedSuspendMethodReturnsNormallyWhenInstrumentedWithElapsedDuration() = runTest {
        val logic = TracedProbeLogic()

        assertEquals(84, logic.returningMethod(42))
    }

    @Test
    fun tracedUnitSuspendMethodCompletesWhenInstrumentedWithElapsedDuration() = runTest {
        val logic = TracedProbeLogic()

        logic.unitMethod()

        assertEquals(1, logic.probeCounter)
    }
}
