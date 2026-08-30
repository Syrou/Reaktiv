package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
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
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
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

    public sealed interface FromClient {
        public val clientId: String
    }

    public sealed interface ObservabilityOnly : FromClient

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
        override val clientId: String,
        val event: CoreLogicMethodStart
    ) : DevToolsMessage(), FromClient

    /**
     * Sent when a traced logic method completes successfully.
     */
    @Serializable
    public data class LogicMethodCompleted(
        override val clientId: String,
        val event: CoreLogicMethodCompleted
    ) : DevToolsMessage(), FromClient

    /**
     * Sent when a traced logic method fails with an exception.
     */
    @Serializable
    public data class LogicMethodFailed(
        override val clientId: String,
        val event: CoreLogicMethodFailed
    ) : DevToolsMessage(), FromClient

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
     * Asks the server for a ghost's session export.
     *
     * Ghost payloads are pulled rather than pushed. A session export can be tens of megabytes and
     * only an orchestrator renders it, so the server advertises ghosts through the client list and
     * sends the payload to whoever asks for it.
     */
    @Serializable
    public data class GhostSessionRequest(
        val ghostClientId: String
    ) : DevToolsMessage()

    /**
     * Sent by the server in answer to [GhostSessionRequest].
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
        override val clientId: String,
        val crash: CrashInfo,
        val sessionJson: String?
    ) : DevToolsMessage(), FromClient

    @Serializable
    public data class StateReadReport(
        override val clientId: String,
        val read: StateRead
    ) : DevToolsMessage(), FromClient

    /**
     * A batch of device log lines forwarded from a publisher.
     */
    @Serializable
    public data class LogBatch(
        override val clientId: String,
        val entries: List<CapturedLog>
    ) : DevToolsMessage(), ObservabilityOnly

    @Serializable
    public data class NetworkBatch(
        override val clientId: String,
        val events: List<NetworkRequestCapture>
    ) : DevToolsMessage(), ObservabilityOnly

    @Serializable
    public data class FetchNetworkBody(
        val targetClientId: String,
        val requestId: String,
        val part: NetworkBodyPart,
        val offset: Int = 0,
        val maxBytes: Int = 64 * 1024
    ) : DevToolsMessage()

    @Serializable
    public data class NetworkBodyChunk(
        override val clientId: String,
        val requestId: String,
        val part: NetworkBodyPart,
        val content: String,
        val offset: Int,
        val nextOffset: Int,
        val totalBytes: Int,
        val isLast: Boolean,
        val available: Boolean = true
    ) : DevToolsMessage(), FromClient

    /**
     * A marker captured on the publisher, relayed to observers.
     */
    @Serializable
    public data class MarkerAdded(
        override val clientId: String,
        val marker: SessionMarker
    ) : DevToolsMessage(), FromClient

    /**
     * A request from an observer to drop a marker into the target client's capture.
     */
    @Serializable
    public data class AddMarkerRequest(
        val targetClientId: String,
        val label: String,
        val note: String = "",
        val timestampMs: Long? = null,
        val afterActionIndex: Int = -1
    ) : DevToolsMessage()

    /**
     * Sent by a publisher to sync its session history on connect.
     * Allows the WASM orchestrator to track and export the session.
     */
    @Serializable
    public data class SessionHistorySync(
        override val clientId: String,
        val history: SessionHistory
    ) : DevToolsMessage(), FromClient


    /**
     * One slice of a large session history. The first chunk carries the
     * initial state snapshot; receivers append slices in chunkIndex order.
     */
    @Serializable
    public data class SessionHistoryChunk(
        override val clientId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val history: SessionHistory
    ) : DevToolsMessage(), FromClient
}

@Serializable
@Deprecated(
    "The log wire and the captured session now share CapturedLog, so a session keeps its logs.",
    ReplaceWith("CapturedLog", "io.github.syrou.reaktiv.introspection.protocol.CapturedLog"),
    DeprecationLevel.WARNING
)
public data class DeviceLogEntry(
    val level: String,
    val category: String,
    val message: String,
    val timestampMs: Long
)

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
