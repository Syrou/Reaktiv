package io.github.syrou.reaktiv.introspection.tracing

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.toCrashInfo

/**
 * Observer that captures logic tracing events for session capture.
 *
 * This observer is automatically registered when IntrospectionModule is used.
 * It captures logic method start/complete/fail events from the LogicTracer
 * and stores them in the SessionCapture for later export.
 *
 * Note: This only captures events if the reaktiv-tracing compiler plugin
 * is applied. Without the plugin, logic methods are not instrumented
 * and no events will be captured.
 */
public class IntrospectionLogicObserver(
    private val sessionCapture: SessionCapture
) : LogicObserver {

    private val inFlight = mutableMapOf<String, Pair<String, String>>()

    override fun onMethodStart(event: LogicMethodStart) {
        inFlight[event.callId] = event.logicClass to event.methodName
        sessionCapture.captureLogicStarted(event)
    }

    override fun onMethodCompleted(event: LogicMethodCompleted) {
        inFlight.remove(event.callId)
        sessionCapture.captureLogicCompleted(event)
    }

    override fun onMethodFailed(event: LogicMethodFailed) {
        val method = inFlight.remove(event.callId)
        sessionCapture.captureLogicFailed(event)
        sessionCapture.reportCrash(
            event.toCrashInfo().copy(
                logicClass = method?.first,
                methodName = method?.second
            )
        )
    }
}
