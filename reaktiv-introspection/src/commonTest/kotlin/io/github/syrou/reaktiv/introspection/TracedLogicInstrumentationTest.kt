package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleLogic
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class TracedProbeLogic : ModuleLogic() {

    suspend fun returningMethod(input: Int): Int = input * 2

    suspend fun unitMethod() {
        probeCounter += 1
    }

    var probeCounter: Int = 0
        private set
}

class TracedLogicInstrumentationTest {

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
