package io.github.syrou.reaktiv.devtools.tracing

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.devtools.DevToolsLogic
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * LogicObserver implementation that forwards tracing events to DevTools.
 *
 * This observer connects the LogicTracer (from reaktiv-core) with the DevTools
 * infrastructure, enabling logic method traces to be displayed in the DevTools UI.
 * Filtering of events is handled in the WASM UI, not here.
 *
 * @param config DevTools configuration for client identification
 * @param devToolsLogic DevToolsLogic instance for sending messages
 * @param scope CoroutineScope for async message sending
 * @param sessionCapture Optional session capture for crash reports
 */
@OptIn(ExperimentalTime::class)
class DevToolsLogicObserver(
    private val config: DevToolsConfig,
    private val devToolsLogic: DevToolsLogic,
    private val scope: CoroutineScope,
    private val sessionCapture: SessionCapture? = null
) : LogicObserver {

    // Track method names by callId for better logging
    private val callIdToMethod = mutableMapOf<String, String>()

    override fun onMethodStart(event: LogicMethodStart) {
        val fullMethodName = "${event.logicClass}.${event.methodName}"
        callIdToMethod[event.callId] = fullMethodName
        println("DevToolsLogicObserver: onMethodStart called for $fullMethodName [callId=${event.callId}], connected=${devToolsLogic.isConnected()}")

        val message = DevToolsMessage.LogicMethodStarted(
            clientId = config.clientId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            callId = event.callId,
            logicClass = event.logicClass,
            methodName = event.methodName,
            params = event.params,
            sourceFile = event.sourceFile,
            lineNumber = event.lineNumber,
            githubSourceUrl = event.githubSourceUrl
        )

        sessionCapture?.captureLogicStarted(message.toCaptured())

        if (!devToolsLogic.isConnected()) {
            println("DevToolsLogicObserver: Not connected, dropping event")
            return
        }

        scope.launch {
            try {
                println("DevToolsLogicObserver: Sending LogicMethodStarted message")
                devToolsLogic.send(message)
                println("DevToolsLogicObserver: Sent LogicMethodStarted message successfully")
            } catch (e: Exception) {
                println("DevToolsLogicObserver: Failed to send method start - ${e.message}")
            }
        }
    }

    override fun onMethodCompleted(event: LogicMethodCompleted) {
        val methodName = callIdToMethod.remove(event.callId) ?: "unknown"
        println("DevToolsLogicObserver: onMethodCompleted called for $methodName [callId=${event.callId}], connected=${devToolsLogic.isConnected()}")

        val message = DevToolsMessage.LogicMethodCompleted(
            clientId = config.clientId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            callId = event.callId,
            result = event.result,
            resultType = event.resultType,
            durationMs = event.durationMs
        )

        sessionCapture?.captureLogicCompleted(message.toCaptured())

        if (!devToolsLogic.isConnected()) return

        scope.launch {
            try {
                devToolsLogic.send(message)
                println("DevToolsLogicObserver: Sent LogicMethodCompleted message")
            } catch (e: Exception) {
                println("DevToolsLogicObserver: Failed to send method completed - ${e.message}")
            }
        }
    }

    override fun onMethodFailed(event: LogicMethodFailed) {
        val methodName = callIdToMethod.remove(event.callId) ?: "unknown"
        println("DevToolsLogicObserver: onMethodFailed called for $methodName [callId=${event.callId}], connected=${devToolsLogic.isConnected()}")

        val message = DevToolsMessage.LogicMethodFailed(
            clientId = config.clientId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            callId = event.callId,
            exceptionType = event.exceptionType,
            exceptionMessage = event.exceptionMessage,
            durationMs = event.durationMs
        )

        sessionCapture?.captureLogicFailed(message.toCaptured())

        if (!devToolsLogic.isConnected()) return

        scope.launch {
            try {
                devToolsLogic.send(message)
            } catch (e: Exception) {
                println("DevToolsLogicObserver: Failed to send method failed - ${e.message}")
            }
        }
    }
}
