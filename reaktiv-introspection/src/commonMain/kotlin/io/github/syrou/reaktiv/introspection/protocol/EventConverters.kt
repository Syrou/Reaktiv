package io.github.syrou.reaktiv.introspection.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import kotlin.time.Clock

/**
 * Extension functions to convert LogicTracer events to Captured protocol types.
 *
 * These converters serve as the single source of truth for field mapping between
 * LogicTracer events and the introspection protocol types. Both IntrospectionLogicObserver
 * and DevToolsLogicObserver use these converters to eliminate code duplication.
 *
 * Usage example:
 * ```kotlin
 * // In IntrospectionLogicObserver
 * override fun onMethodStart(event: LogicMethodStart) {
 *     val captured = event.toCaptured(clientId)
 *     sessionCapture.captureLogicStarted(captured)
 * }
 *
 * // In DevToolsLogicObserver
 * override fun onMethodStart(event: LogicMethodStart) {
 *     val captured = event.toCaptured(clientId)
 *     val message = DevToolsMessage.LogicMethodStarted.fromCaptured(captured)
 *     devToolsLogic.send(message)
 * }
 * ```
 */

/**
 * Converts LogicMethodStart to CapturedLogicStart.
 *
 * @param clientId Client identifier for the session
 * @return CapturedLogicStart with all fields mapped from the event
 */
fun LogicMethodStart.toCaptured(clientId: String): CapturedLogicStart {
    return CapturedLogicStart(
        clientId = clientId,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        callId = this.callId,
        logicClass = this.logicClass,
        methodName = this.methodName,
        params = this.params,
        sourceFile = this.sourceFile,
        lineNumber = this.lineNumber,
        githubSourceUrl = this.githubSourceUrl
    )
}

/**
 * Converts LogicMethodCompleted to CapturedLogicComplete.
 *
 * @param clientId Client identifier for the session
 * @return CapturedLogicComplete with all fields mapped from the event
 */
fun LogicMethodCompleted.toCaptured(clientId: String): CapturedLogicComplete {
    return CapturedLogicComplete(
        clientId = clientId,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        callId = this.callId,
        result = this.result,
        resultType = this.resultType,
        durationMs = this.durationMs
    )
}

/**
 * Converts LogicMethodFailed to CapturedLogicFailed.
 *
 * @param clientId Client identifier for the session
 * @return CapturedLogicFailed with all fields mapped from the event
 */
fun LogicMethodFailed.toCaptured(clientId: String): CapturedLogicFailed {
    return CapturedLogicFailed(
        clientId = clientId,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        callId = this.callId,
        exceptionType = this.exceptionType,
        exceptionMessage = this.exceptionMessage,
        stackTrace = this.stackTrace,
        durationMs = this.durationMs
    )
}
