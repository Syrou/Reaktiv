package io.github.syrou.reaktiv.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaktivDebugLogTest {

    private data class Line(val level: String, val category: String, val message: String)

    private fun withSink(block: (MutableList<Line>) -> Unit) {
        val lines = mutableListOf<Line>()
        val sink = ReaktivLogSink { level, category, message -> lines.add(Line(level, category, message)) }
        ReaktivDebug.addSink(sink)
        try {
            block(lines)
        } finally {
            ReaktivDebug.removeSink(sink)
        }
    }

    @Test
    fun `log forwards level and category to every sink untouched`() = withSink { lines ->
        ReaktivDebug.log("INFO", "MyApp", "hello")
        ReaktivDebug.log("WARN", "Payments", "slow")
        assertEquals(
            listOf(Line("INFO", "MyApp", "hello"), Line("WARN", "Payments", "slow")),
            lines
        )
    }

    @Test
    fun `log normalises the level and folds the throwable into the message`() = withSink { lines ->
        ReaktivDebug.log("error", "MyApp", "failed", IllegalStateException("boom"))
        assertEquals(listOf(Line("ERROR", "MyApp", "failed: boom")), lines)
    }

    @Test
    fun `log reaches sinks while console output is disabled`() = withSink { lines ->
        val wasEnabled = ReaktivDebug.isEnabled
        ReaktivDebug.disable()
        try {
            ReaktivDebug.log("DEBUG", "MyApp", "quiet")
        } finally {
            if (wasEnabled) ReaktivDebug.enable()
        }
        assertEquals(listOf(Line("DEBUG", "MyApp", "quiet")), lines)
    }
}
