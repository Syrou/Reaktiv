package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalAtomicApi::class)
public class StallWatchdog(
    private val scope: CoroutineScope,
    private val thresholdMs: Long = 300L,
    private val heartbeatMs: Long = 100L,
    private val monitoredDispatcher: CoroutineDispatcher? = null
) {

    private val lastBeat = AtomicLong(0L)
    private var heartbeatJob: Job? = null
    private var monitorJob: Job? = null

    @Volatile
    private var stackCapturer: (() -> String?)? = null

    public fun start(): Boolean {
        val dispatcher = monitoredDispatcher
            ?: runCatching { Dispatchers.Main }.getOrNull()
            ?: return false

        val dispatcherUsable = runCatching { dispatcher.isDispatchNeeded(EmptyCoroutineContext) }.isSuccess
        if (!dispatcherUsable) {
            ReaktivDebug.warn("StallWatchdog: monitored dispatcher unavailable, watchdog disabled")
            return false
        }

        lastBeat.store(currentTimeMillis())
        heartbeatJob = scope.launch(dispatcher) {
            if (stackCapturer == null) {
                stackCapturer = bindThreadStackCapturer()
            }
            while (isActive) {
                lastBeat.store(currentTimeMillis())
                delay(heartbeatMs)
            }
        }

        monitorJob = scope.launch(Dispatchers.Default) {
            var inStall = false
            var stallStartBeat = 0L
            var frozenStack: String? = null
            while (isActive) {
                delay(heartbeatMs)
                if (heartbeatJob?.isActive != true) break
                val gap = currentTimeMillis() - lastBeat.load()
                if (!inStall && gap > thresholdMs) {
                    inStall = true
                    stallStartBeat = lastBeat.load()
                    frozenStack = stackCapturer?.invoke()
                } else if (inStall && gap <= heartbeatMs * 2) {
                    val stallMs = lastBeat.load() - stallStartBeat
                    inStall = false
                    if (stallMs > thresholdMs) {
                        reportStall(stallMs, frozenStack)
                    }
                    frozenStack = null
                }
            }
        }
        return true
    }

    public fun stop() {
        heartbeatJob?.cancel()
        monitorJob?.cancel()
        heartbeatJob = null
        monitorJob = null
    }

    private suspend fun reportStall(stallMs: Long, stack: String?) {
        val params = buildMap {
            put("thresholdMs", thresholdMs.toString())
            if (!stack.isNullOrBlank()) put("stack", stack)
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

    public companion object {
        public const val TRACE_CLASS: String = "MainThreadWatchdog"
    }
}
