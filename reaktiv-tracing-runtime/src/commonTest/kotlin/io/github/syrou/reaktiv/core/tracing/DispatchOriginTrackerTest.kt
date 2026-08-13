package io.github.syrou.reaktiv.core.tracing

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DispatchOriginTrackerTest {

    private object NoOpObserver : LogicObserver {
        override fun onMethodStart(event: LogicMethodStart) {}
        override fun onMethodCompleted(event: LogicMethodCompleted) {}
        override fun onMethodFailed(event: LogicMethodFailed) {}
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
        DispatchOriginTracker.clear()
    }

    @Test
    fun `record and consume pair in order per action identity`() {
        LogicTracer.addObserver(NoOpObserver)
        val action = Any()
        val other = Any()
        DispatchOriginTracker.record(action, "first")
        DispatchOriginTracker.record(action, "second")
        DispatchOriginTracker.record(other, "elsewhere")

        assertEquals("first", DispatchOriginTracker.consume(action))
        assertEquals("second", DispatchOriginTracker.consume(action))
        assertNull(DispatchOriginTracker.consume(action))
        assertEquals("elsewhere", DispatchOriginTracker.consume(other))
    }

    @Test
    fun `record without an active tracer is a no-op`() {
        val action = Any()
        DispatchOriginTracker.record(action, "ignored")
        assertNull(DispatchOriginTracker.consume(action))
    }
}
