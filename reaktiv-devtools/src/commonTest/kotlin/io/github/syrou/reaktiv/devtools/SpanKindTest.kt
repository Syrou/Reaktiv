package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.protocol.GUARD_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.SYNTHETIC_TRACE_CLASSES
import io.github.syrou.reaktiv.devtools.protocol.SpanKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SpanKindTest {

    @Test
    fun `a trace class maps to its kind and an app class is plain logic`() {
        assertEquals(SpanKind.GUARD, SpanKind.of(GUARD_TRACE_CLASS))
        assertEquals(SpanKind.LOGIC, SpanKind.of("com.example.UserLogic"))
    }

    @Test
    fun `pipeline kinds are exactly the synthetic trace classes`() {
        val pipeline = SpanKind.entries.filter { it.pipeline }.mapNotNull { it.traceClass }.toSet()

        assertEquals(SYNTHETIC_TRACE_CLASSES, pipeline)
    }
}
