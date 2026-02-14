package io.github.syrou.reaktiv.devtools.tracing

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.devtools.DevToolsLogic
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.toCaptured
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
 */
@OptIn(ExperimentalTime::class)
class DevToolsLogicObserver(
    private val config: DevToolsConfig,
    private val devToolsLogic: DevToolsLogic,
    private val scope: CoroutineScope
) : LogicObserver {

    // Track method names by callId for better logging
    private val callIdToMethod = mutableMapOf<String, String>()

    override fun onMethodStart(event: LogicMethodStart) {
        val fullMethodName = "${event.logicClass}.${event.methodName}"
        callIdToMethod[event.callId] = fullMethodName
        println("DevToolsLogicObserver: onMethodStart called for $fullMethodName [callId=${event.callId}], connected=${devToolsLogic.isConnected()}")

        if (!devToolsLogic.isConnected()) {
            println("DevToolsLogicObserver: Not connected, dropping event")
            return
        }

        val message = DevToolsMessage.LogicMethodStarted.fromCaptured(event.toCaptured(config.clientId))

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

        if (!devToolsLogic.isConnected()) return

        val message = DevToolsMessage.LogicMethodCompleted.fromCaptured(event.toCaptured(config.clientId))

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

        if (!devToolsLogic.isConnected()) return

        val message = DevToolsMessage.LogicMethodFailed.fromCaptured(event.toCaptured(config.clientId))

        scope.launch {
            try {
                devToolsLogic.send(message)

                val sessionCapture = devToolsLogic.getSessionCapture()
                val sessionJson = if (sessionCapture != null) {
                    val crashInfo = CrashInfo(
                        timestamp = Clock.System.now().toEpochMilliseconds(),
                        exception = CrashException(
                            exceptionType = event.exceptionType,
                            message = event.exceptionMessage,
                            stackTrace = event.stackTrace ?: ""
                        )
                    )
                    sessionCapture.exportSessionWithCrash(crashInfo)
                } else {
                    null
                }
                devToolsLogic.send(
                    DevToolsMessage.CrashReport(
                        clientId = config.clientId,
                        timestamp = Clock.System.now().toEpochMilliseconds(),
                        exceptionType = event.exceptionType,
                        exceptionMessage = event.exceptionMessage,
                        stackTrace = event.stackTrace,
                        failedCallId = event.callId,
                        sessionJson = sessionJson
                    )
                )
            } catch (e: Exception) {
                println("DevToolsLogicObserver: Failed to send method failed - ${e.message}")
            }
        }
    }
}
