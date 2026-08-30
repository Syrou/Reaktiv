package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.DispatchInstrumentation
import io.github.syrou.reaktiv.core.DispatchStepDecorator
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.tracing.DispatchOriginTracker
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.currentTimeMillis

public class DispatchTracingInstrumentation : DispatchInstrumentation {

    override val isActive: Boolean get() = LogicTracer.active

    override suspend fun onDispatchStarted(
        action: ModuleAction,
        queueWaitMs: Long,
        queueDepth: Long
    ): String = LogicTracer.notifyMethodStart(
        logicClass = DISPATCH_TRACE_CLASS,
        methodName = action::class.simpleName ?: "Action",
        params = buildMap {
            put("queueWaitMs", queueWaitMs.toString())
            put("queueDepth", queueDepth.toString())
            DispatchOriginTracker.consume(action)?.let { put("dispatchedFrom", it) }
        }
    )

    override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) {
        if (token.isEmpty()) return
        LogicTracer.notifyMethodCompleted(
            callId = token,
            result = if (applied) "Processed" else "Blocked",
            resultType = "DispatchResult",
            durationMs = durationMs
        )
    }

    override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) {
        if (token.isEmpty()) return
        LogicTracer.notifyMethodFailed(token, error, durationMs)
    }

    override suspend fun onEvaluationStarted(
        scope: String,
        name: String,
        params: Map<String, String>
    ): String = LogicTracer.notifyMethodStart(
        logicClass = scope,
        methodName = name,
        params = params
    )

    override fun onEvaluationCompleted(
        token: String,
        result: String?,
        resultType: String,
        durationMs: Long
    ) {
        if (token.isEmpty()) return
        LogicTracer.notifyMethodCompleted(token, result, resultType, durationMs)
    }

    override fun onEvaluationFailed(token: String, error: Throwable, durationMs: Long) {
        if (token.isEmpty()) return
        LogicTracer.notifyMethodFailed(token, error, durationMs)
    }

    override fun newDispatchDecorator(): DispatchStepDecorator = PhaseTracingDecorator()

    private class PhaseTracingDecorator : DispatchStepDecorator {

        private class Frame(var childMs: Long = 0L)

        private val stack = ArrayList<Frame>()

        override fun decorate(
            name: String,
            step: suspend (ModuleAction) -> Unit
        ): suspend (ModuleAction) -> Unit = { action ->
            val frame = Frame()
            stack.add(frame)
            val start = currentTimeMillis()
            try {
                step(action)
            } finally {
                stack.removeAt(stack.size - 1)
                val totalMs = currentTimeMillis() - start
                stack.lastOrNull()?.let { it.childMs += totalMs }
                val selfMs = totalMs - frame.childMs
                if (selfMs >= PHASE_TRACE_THRESHOLD_MS && LogicTracer.active) {
                    emitPhase(action, name, selfMs, start)
                }
            }
        }

        private suspend fun emitPhase(
            action: ModuleAction,
            phase: String,
            selfMs: Long,
            startedAtMs: Long
        ) {
            val callId = LogicTracer.notifyMethodStart(
                logicClass = PHASE_TRACE_CLASS,
                methodName = phase,
                params = mapOf("actionType" to (action::class.simpleName ?: "Action")),
                startedAtMs = startedAtMs
            )
            if (callId.isNotEmpty()) {
                LogicTracer.notifyMethodCompleted(
                    callId = callId,
                    result = "took ${selfMs}ms",
                    resultType = PHASE_TRACE_CLASS,
                    durationMs = selfMs
                )
            }
        }
    }

    override suspend fun onDispatchDropped(action: ModuleAction) {
        val callId = LogicTracer.notifyMethodStart(
            logicClass = DISPATCH_TRACE_CLASS,
            methodName = action::class.simpleName ?: "Action",
            params = mapOf("externalControl" to "dropped")
        )
        if (callId.isNotEmpty()) {
            LogicTracer.notifyMethodCompleted(callId, "Blocked", "DispatchResult", 0L)
        }
    }

    override suspend fun onExternalControlChanged(enabled: Boolean) {
        val callId = LogicTracer.notifyMethodStart(
            logicClass = DISPATCH_TRACE_CLASS,
            methodName = if (enabled) "beginExternalControl" else "endExternalControl",
            params = emptyMap()
        )
        if (callId.isNotEmpty()) {
            LogicTracer.notifyMethodCompleted(callId, "Applied", "Unit", 0L)
        }
    }

    public companion object {
        public const val DISPATCH_TRACE_CLASS: String = "StoreDispatch"
        public const val PHASE_TRACE_CLASS: String = "DispatchPhase"
        public const val PHASE_TRACE_THRESHOLD_MS: Long = 4L
    }
}
