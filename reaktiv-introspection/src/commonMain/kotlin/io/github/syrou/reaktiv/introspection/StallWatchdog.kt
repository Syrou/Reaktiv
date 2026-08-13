package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalAtomicApi::class)
public class StallWatchdog(
    private val scope: CoroutineScope,
    private val thresholdMs: Long = 300L,
    private val heartbeatMs: Long = 100L,
    private val monitoredDispatcher: CoroutineDispatcher? = null,
    stackCapturer: (() -> String?)? = null
) {

    private val lastBeat = AtomicLong(0L)

    private val watchJob = AtomicReference<Job?>(null)

    @Volatile
    private var stackCapturer: (() -> String?)? = stackCapturer

    public fun start(): Boolean {
        stop()
        val dispatcher = monitoredDispatcher
            ?: runCatching { Dispatchers.Main }.getOrNull()
            ?: return false

        val dispatcherUsable = runCatching { dispatcher.isDispatchNeeded(EmptyCoroutineContext) }.isSuccess
        if (!dispatcherUsable) {
            ReaktivDebug.warn("StallWatchdog: monitored dispatcher unavailable, watchdog disabled")
            return false
        }

        lastBeat.store(currentTimeMillis())
        val job = scope.launch {
            val heartbeat = launch(dispatcher) {
                if (stackCapturer == null) {
                    stackCapturer = bindThreadStackCapturer()
                }
                while (isActive) {
                    lastBeat.store(currentTimeMillis())
                    delay(heartbeatMs)
                }
            }

            launch(Dispatchers.Default) {
                monitor(heartbeat)
            }
        }
        watchJob.exchange(job)?.cancel()
        return true
    }

    public fun stop() {
        watchJob.exchange(null)?.cancel()
    }

    private suspend fun monitor(heartbeat: Job) {
        coroutineScope {
            var inStall = false
            var stallStartBeat = 0L
            val sampledStacks = ArrayList<String>()
            while (isActive) {
                delay(heartbeatMs)
                if (!heartbeat.isActive) break
                val gap = currentTimeMillis() - lastBeat.load()
                if (!inStall && gap > thresholdMs) {
                    inStall = true
                    stallStartBeat = lastBeat.load()
                    sampledStacks.clear()
                    stackCapturer?.invoke()?.let { sampledStacks.add(it) }
                } else if (inStall && gap > heartbeatMs * 2) {
                    if (sampledStacks.size < STACK_SAMPLE_LIMIT) {
                        stackCapturer?.invoke()?.let { sampledStacks.add(it) }
                    }
                } else if (inStall) {
                    val stallMs = lastBeat.load() - stallStartBeat
                    inStall = false
                    if (stallMs > thresholdMs) {
                        reportStall(stallMs, sampledStacks.toList())
                    }
                    sampledStacks.clear()
                }
            }
        }
    }

    private suspend fun reportStall(stallMs: Long, stacks: List<String>) {
        val params = buildMap {
            put("thresholdMs", thresholdMs.toString())
            val first = stacks.firstOrNull()
            if (!first.isNullOrBlank()) put("stack", first)
            if (stacks.isNotEmpty()) put("samples", stacks.size.toString())
            hottestFrame(stacks)?.let { put("hottestFrame", it) }
        }
        val callId = LogicTracer.notifyMethodStart(
            logicClass = TRACE_CLASS,
            methodName = "stall",
            params = params
        )
        if (callId.isNotEmpty()) {
            LogicTracer.notifyMethodCompleted(
                callId = callId,
                result = "recovered after ${stallMs}ms",
                resultType = "Stall",
                durationMs = stallMs
            )
        }
    }

    private fun hottestFrame(stacks: List<String>): String? =
        stacks.mapNotNull { stack ->
            stack.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    public companion object {
        public const val TRACE_CLASS: String = "MainThreadWatchdog"
        public const val STACK_SAMPLE_LIMIT: Int = 50
    }
}
