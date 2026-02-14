package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicComplete
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicFailed
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicStart

/**
 * Extension functions to convert WASM UI state types directly to Captured protocol types.
 *
 * These converters eliminate the triple conversion path identified in the architecture review:
 * - OLD PATH: ActionStateEvent → DevToolsMessage.ActionDispatched → CapturedAction
 * - NEW PATH: ActionStateEvent → CapturedAction
 *
 * This simplifies the export process and eliminates redundant object creation during
 * ghost session exports from the WASM UI.
 *
 * Usage example:
 * ```kotlin
 * val export = GhostSessionExport(
 *     actions = actionHistory.map { it.toCaptured() },
 *     logicStartedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Started>()
 *         .map { it.toCaptured() }
 * )
 * ```
 */

/**
 * Converts ActionStateEvent to CapturedAction.
 * Eliminates the intermediate DevToolsMessage.ActionDispatched conversion step.
 */
fun ActionStateEvent.toCaptured(): CapturedAction {
    return CapturedAction(
        clientId = this.clientId,
        timestamp = this.timestamp,
        actionType = this.actionType,
        actionData = this.actionData,
        stateDeltaJson = this.stateDeltaJson,
        moduleName = this.moduleName
    )
}

/**
 * Converts LogicMethodEvent.Started to CapturedLogicStart.
 * Eliminates the intermediate DevToolsMessage.LogicMethodStarted conversion step.
 */
fun LogicMethodEvent.Started.toCaptured(): CapturedLogicStart {
    return CapturedLogicStart(
        clientId = this.clientId,
        timestamp = this.timestamp,
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
 * Converts LogicMethodEvent.Completed to CapturedLogicComplete.
 * Eliminates the intermediate DevToolsMessage.LogicMethodCompleted conversion step.
 */
fun LogicMethodEvent.Completed.toCaptured(): CapturedLogicComplete {
    return CapturedLogicComplete(
        clientId = this.clientId,
        timestamp = this.timestamp,
        callId = this.callId,
        result = this.result,
        resultType = this.resultType,
        durationMs = this.durationMs
    )
}

/**
 * Converts LogicMethodEvent.Failed to CapturedLogicFailed.
 * Eliminates the intermediate DevToolsMessage.LogicMethodFailed conversion step.
 */
fun LogicMethodEvent.Failed.toCaptured(): CapturedLogicFailed {
    return CapturedLogicFailed(
        clientId = this.clientId,
        timestamp = this.timestamp,
        callId = this.callId,
        exceptionType = this.exceptionType,
        exceptionMessage = this.exceptionMessage,
        stackTrace = this.stackTrace,
        durationMs = this.durationMs
    )
}
