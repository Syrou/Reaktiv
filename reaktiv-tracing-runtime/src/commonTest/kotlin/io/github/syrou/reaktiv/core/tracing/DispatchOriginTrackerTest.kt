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
    fun `a full registry forgets only the oldest action`() {
        LogicTracer.addObserver(NoOpObserver)
        val actions = List(ORIGIN_CAPACITY + 1) { Any() }
        actions.forEachIndexed { index, action -> DispatchOriginTracker.record(action, "origin-$index") }

        assertNull(DispatchOriginTracker.consume(actions.first()))
        assertEquals("origin-1", DispatchOriginTracker.consume(actions[1]))
        assertEquals("origin-$ORIGIN_CAPACITY", DispatchOriginTracker.consume(actions.last()))
    }

    @Test
    fun `record without an active tracer is a no-op`() {
        val action = Any()
        DispatchOriginTracker.record(action, "ignored")
        assertNull(DispatchOriginTracker.consume(action))
    }
}
