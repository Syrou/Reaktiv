package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicComplete
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicFailed
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicStart
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import kotlinx.serialization.Serializable

/**
 * DevTools network protocol messages.
 *
 * These messages are used for communication between DevTools clients and server.
 * Data capture types (CapturedAction, etc.) are imported from reaktiv-crash-capture.
 */
@Serializable
sealed class DevToolsMessage {

    @Serializable
    data class ClientRegistration(
        val clientName: String,
        val clientId: String,
        val platform: String,
        val isGhost: Boolean = false
    ) : DevToolsMessage()

    @Serializable
    data class RoleAssignment(
        val targetClientId: String,
        val role: ClientRole,
        val publisherClientId: String? = null
    ) : DevToolsMessage()

    /**
     * Sent when an action is dispatched. Wraps CapturedAction from crash-capture.
     */
    @Serializable
    data class ActionDispatched(
        val clientId: String,
        val timestamp: Long,
        val actionType: String,
        val actionData: String,
        val resultingStateJson: String
    ) : DevToolsMessage() {
        fun toCaptured() = CapturedAction(clientId, timestamp, actionType, actionData, resultingStateJson)

        companion object {
            fun fromCaptured(captured: CapturedAction) = ActionDispatched(
                captured.clientId, captured.timestamp, captured.actionType,
                captured.actionData, captured.resultingStateJson
            )
        }
    }

    @Serializable
    data class StateSync(
        val fromClientId: String,
        val timestamp: Long,
        val stateJson: String,
        val orchestrated: Boolean = false
    ) : DevToolsMessage()

    @Serializable
    data class ClientListUpdate(
        val clients: List<ClientInfo>
    ) : DevToolsMessage()

    @Serializable
    data class RoleAcknowledgment(
        val clientId: String,
        val role: ClientRole,
        val success: Boolean,
        val message: String? = null
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method starts execution.
     */
    @Serializable
    data class LogicMethodStarted(
        val clientId: String,
        val timestamp: Long,
        val callId: String,
        val logicClass: String,
        val methodName: String,
        val params: Map<String, String>,
        val sourceFile: String? = null,
        val lineNumber: Int? = null,
        val githubSourceUrl: String? = null
    ) : DevToolsMessage() {
        fun toCaptured() = CapturedLogicStart(
            clientId, timestamp, callId, logicClass, methodName, params,
            sourceFile, lineNumber, githubSourceUrl
        )

        companion object {
            fun fromCaptured(captured: CapturedLogicStart) = LogicMethodStarted(
                captured.clientId, captured.timestamp, captured.callId,
                captured.logicClass, captured.methodName, captured.params,
                captured.sourceFile, captured.lineNumber, captured.githubSourceUrl
            )
        }
    }

    /**
     * Sent when a traced logic method completes successfully.
     */
    @Serializable
    data class LogicMethodCompleted(
        val clientId: String,
        val timestamp: Long,
        val callId: String,
        val result: String?,
        val resultType: String,
        val durationMs: Long
    ) : DevToolsMessage() {
        fun toCaptured() = CapturedLogicComplete(clientId, timestamp, callId, result, resultType, durationMs)

        companion object {
            fun fromCaptured(captured: CapturedLogicComplete) = LogicMethodCompleted(
                captured.clientId, captured.timestamp, captured.callId,
                captured.result, captured.resultType, captured.durationMs
            )
        }
    }

    /**
     * Sent when a traced logic method fails with an exception.
     */
    @Serializable
    data class LogicMethodFailed(
        val clientId: String,
        val timestamp: Long,
        val callId: String,
        val exceptionType: String,
        val exceptionMessage: String?,
        val stackTrace: String? = null,
        val durationMs: Long
    ) : DevToolsMessage() {
        fun toCaptured() = CapturedLogicFailed(clientId, timestamp, callId, exceptionType, exceptionMessage, stackTrace, durationMs)

        companion object {
            fun fromCaptured(captured: CapturedLogicFailed) = LogicMethodFailed(
                captured.clientId, captured.timestamp, captured.callId,
                captured.exceptionType, captured.exceptionMessage, captured.stackTrace, captured.durationMs
            )
        }
    }

    /**
     * Registers a ghost device from an imported session.
     * Ghost devices represent recorded sessions that can be replayed.
     *
     * Note: This is a lightweight message - actual event data stays on WASM UI side.
     * The server only needs metadata to show the ghost in the client list.
     * State syncing happens via StateSync messages during playback.
     */
    @Serializable
    data class GhostDeviceRegistration(
        val sessionId: String,
        val originalClientInfo: ClientInfo,
        val crashException: CrashException? = null,
        val eventCount: Int = 0,
        val logicEventCount: Int = 0,
        val sessionStartTime: Long,
        val sessionEndTime: Long
    ) : DevToolsMessage()

    /**
     * Request to remove a ghost device.
     */
    @Serializable
    data class GhostDeviceRemoval(
        val ghostClientId: String
    ) : DevToolsMessage()

    /**
     * Notification when the publisher changes.
     */
    @Serializable
    data class PublisherChanged(
        val newPublisherId: String?,
        val previousPublisherId: String?,
        val reason: String
    ) : DevToolsMessage()

    /**
     * Sent when a crash occurs in a logic method.
     * Carries crash info and optional session snapshot for real-time crash reporting.
     */
    @Serializable
    data class CrashReport(
        val clientId: String,
        val timestamp: Long,
        val exceptionType: String,
        val exceptionMessage: String?,
        val stackTrace: String?,
        val failedCallId: String?,
        val sessionJson: String?
    ) : DevToolsMessage()

    /**
     * Sent by a publisher to sync its session history on connect.
     * Allows the WASM orchestrator to track and export the session.
     */
    @Serializable
    data class SessionHistorySync(
        val clientId: String,
        val sessionStartTime: Long,
        val actionEvents: List<ActionDispatched>,
        val logicStartedEvents: List<LogicMethodStarted>,
        val logicCompletedEvents: List<LogicMethodCompleted>,
        val logicFailedEvents: List<LogicMethodFailed>
    ) : DevToolsMessage()
}

@Serializable
enum class ClientRole {
    UNASSIGNED,
    PUBLISHER,
    LISTENER,
    ORCHESTRATOR
}

@Serializable
data class ClientInfo(
    val clientId: String,
    val clientName: String,
    val platform: String,
    val role: ClientRole,
    val publisherClientId: String? = null,
    val connectedAt: Long,
    val isGhost: Boolean = false
)

// Re-export types from introspection for convenience
typealias GhostSessionExport = SessionExport
typealias GhostSessionFormat = SessionExportFormat
