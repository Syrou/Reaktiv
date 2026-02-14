package io.github.syrou.reaktiv.introspection.tracing

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.toCaptured

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
class IntrospectionLogicObserver(
    private val clientId: String,
    private val sessionCapture: SessionCapture
) : LogicObserver {

    override fun onMethodStart(event: LogicMethodStart) {
        if (!sessionCapture.isStarted()) return
        sessionCapture.captureLogicStarted(event.toCaptured(clientId))
    }

    override fun onMethodCompleted(event: LogicMethodCompleted) {
        if (!sessionCapture.isStarted()) return
        sessionCapture.captureLogicCompleted(event.toCaptured(clientId))
    }

    override fun onMethodFailed(event: LogicMethodFailed) {
        if (!sessionCapture.isStarted()) return
        val captured = event.toCaptured(clientId)
        sessionCapture.captureLogicFailed(captured)
        sessionCapture.captureCrashFromLogicFailure(captured)
    }
}
