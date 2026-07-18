package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StallWatchdogTest {

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

        fun stallCompletions() = completed.filter { completion ->
            started.any { it.logicClass == StallWatchdog.TRACE_CLASS && it.callId == completion.callId }
        }
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    @Test
    fun `blocked monitored dispatcher reports a stall with its duration`() {
        val executor = Executors.newSingleThreadExecutor()
        val monitored = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)

        val watchdog = StallWatchdog(
            scope = scope,
            thresholdMs = 150L,
            heartbeatMs = 30L,
            monitoredDispatcher = monitored
        )
        try {
            assertTrue(watchdog.start())
            runBlocking {
                delay(120)
                withContext(monitored) {
                    Thread.sleep(400)
                }
                delay(300)
            }

            val stalls = observer.stallCompletions()
            assertTrue(stalls.isNotEmpty(), "Expected at least one stall report")
            assertTrue(stalls[0].durationMs >= 150L, "Stall duration was ${stalls[0].durationMs}")

            val stallStart = observer.started.first { it.logicClass == StallWatchdog.TRACE_CLASS }
            val stack = stallStart.params["stack"]
            assertTrue(stack != null && stack.contains("Thread"), "Expected a captured main stack, got: $stack")
        } finally {
            watchdog.stop()
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun `responsive dispatcher reports nothing`() {
        val executor = Executors.newSingleThreadExecutor()
        val monitored = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)

        val watchdog = StallWatchdog(
            scope = scope,
            thresholdMs = 150L,
            heartbeatMs = 30L,
            monitoredDispatcher = monitored
        )
        try {
            assertTrue(watchdog.start())
            runBlocking { delay(400) }
            assertEquals(0, observer.stallCompletions().size)
        } finally {
            watchdog.stop()
            scope.cancel()
            executor.shutdown()
        }
    }
}
