package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted as CoreLogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed as CoreLogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart as CoreLogicMethodStart
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import kotlinx.serialization.Serializable

/**
 * DevTools network protocol messages.
 *
 * These messages are used for communication between DevTools clients and server.
 * Data capture types (CapturedAction, etc.) are imported from reaktiv-crash-capture.
 */
@Serializable
public sealed class DevToolsMessage {

    @Serializable
    public data class ClientRegistration(
        val clientName: String,
        val clientId: String,
        val platform: String,
        val isGhost: Boolean = false
    ) : DevToolsMessage()

    @Serializable
    public data class RoleAssignment(
        val targetClientId: String,
        val role: ClientRole,
        val publisherClientId: String? = null
    ) : DevToolsMessage()

    /**
     * Sent when an action is dispatched. Wraps CapturedAction from introspection.
     */
    @Serializable
    public data class ActionDispatched(
        val event: CapturedAction
    ) : DevToolsMessage() {
        val clientId: String get() = event.clientId
    }

    @Serializable
    public data class StateSync(
        val fromClientId: String,
        val timestamp: Long,
        val stateJson: String,
        val moduleName: String = ""
    ) : DevToolsMessage()

    @Serializable
    public data class ClientListUpdate(
        val clients: List<ClientInfo>
    ) : DevToolsMessage()

    @Serializable
    public data class RoleAcknowledgment(
        val clientId: String,
        val role: ClientRole,
        val success: Boolean,
        val message: String? = null
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method starts execution.
     */
    @Serializable
    public data class LogicMethodStarted(
        val clientId: String,
        val event: CoreLogicMethodStart
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method completes successfully.
     */
    @Serializable
    public data class LogicMethodCompleted(
        val clientId: String,
        val event: CoreLogicMethodCompleted
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method fails with an exception.
     */
    @Serializable
    public data class LogicMethodFailed(
        val clientId: String,
        val event: CoreLogicMethodFailed
    ) : DevToolsMessage()

    /**
     * Registers a ghost device from an imported session.
     * Ghost devices represent recorded sessions that can be replayed.
     *
     * Note: This is a lightweight message - actual event data stays on WASM UI side.
     * The server only needs metadata to show the ghost in the client list.
     * State syncing happens via StateSync messages during playback.
     */
    @Serializable
    public data class GhostDeviceRegistration(
        val sessionId: String,
        val originalClientInfo: ClientInfo,
        val crashException: CrashException? = null,
        val eventCount: Int = 0,
        val logicEventCount: Int = 0,
        val sessionStartTime: Long,
        val sessionEndTime: Long,
        val sessionExportJson: String? = null
    ) : DevToolsMessage()

    /**
     * Request to remove a ghost device.
     */
    @Serializable
    public data class GhostDeviceRemoval(
        val ghostClientId: String
    ) : DevToolsMessage()

    /**
     * Sent by the server to restore ghost session data on client reconnect.
     * Contains the full session export JSON so the WASM UI can rebuild its state.
     */
    @Serializable
    public data class GhostSessionRestore(
        val ghostClientId: String,
        val sessionExportJson: String
    ) : DevToolsMessage()

    /**
     * Sent by the server to a publisher when a new observer attaches.
     *
     * The publisher answers with the baseline that observer needs: a full StateSync for a
     * [ClientRole.LISTENER], which replicates state, and a SessionHistorySync for a
     * [ClientRole.ORCHESTRATOR], which needs the captured initial state plus the action
     * history in order to reconstruct the full application state at any point.
     *
     * [role] defaults to [ClientRole.LISTENER] so older publishers, which only ever received
     * this for listeners, keep their previous behaviour.
     */
    @Serializable
    public data class ListenerAttached(
        val listenerId: String,
        val role: ClientRole = ClientRole.LISTENER
    ) : DevToolsMessage()

    /**
     * A client reporting its own tooling status so observers can see it.
     *
     * Followers are otherwise opaque: their diagnostics go to [ServiceStatus], which is only
     * visible in an app's own debug menu, and to ReaktivDebug, which is silent unless the host
     * app enabled it. Reporting upstream means a failure to replicate is visible in the
     * DevTools UI next to the client it concerns.
     */
    @Serializable
    public data class ClientStatus(
        val clientId: String,
        val status: ServiceStatus
    ) : DevToolsMessage()

    /**
     * Notification when the publisher changes.
     */
    @Serializable
    public data class PublisherChanged(
        val newPublisherId: String?,
        val previousPublisherId: String?,
        val reason: String
    ) : DevToolsMessage()

    /**
     * Sent when a crash is reported on the publisher.
     * Carries the canonical crash envelope and an optional session snapshot.
     */
    @Serializable
    public data class CrashReport(
        val clientId: String,
        val crash: CrashInfo,
        val sessionJson: String?
    ) : DevToolsMessage()

    @Serializable
    public data class StateReadReport(
        val clientId: String,
        val read: StateRead
    ) : DevToolsMessage()

    /**
     * Sent by a publisher to sync its session history on connect.
     * Allows the WASM orchestrator to track and export the session.
     */
    @Serializable
    public data class SessionHistorySync(
        val clientId: String,
        val history: SessionHistory
    ) : DevToolsMessage()


    /**
     * One slice of a large session history. The first chunk carries the
     * initial state snapshot; receivers append slices in chunkIndex order.
     */
    @Serializable
    public data class SessionHistoryChunk(
        val clientId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val history: SessionHistory
    ) : DevToolsMessage()
}

@Serializable
public enum class ClientRole {
    UNASSIGNED,
    PUBLISHER,
    LISTENER,
    ORCHESTRATOR
}

@Serializable
public data class ClientInfo(
    val clientId: String,
    val clientName: String,
    val platform: String,
    val role: ClientRole,
    val publisherClientId: String? = null,
    val connectedAt: Long,
    val isGhost: Boolean = false
)

// Re-export types from introspection for convenience
public typealias GhostSessionExport = SessionExport
public typealias GhostSessionFormat = SessionExportFormat
