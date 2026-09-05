package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicFailureKind
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.introspection.protocol.CrashDiagnosis
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import kotlinx.serialization.Serializable

/**
 * State for the DevTools WASM UI.
 */
@Serializable
internal data class DevToolsUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedClients: List<ClientInfo> = emptyList(),
    val actionStateHistory: List<CapturedAction> = emptyList(),
    val logicMethodEvents: List<LogicMethodEvent> = emptyList(),
    val selectedPublisher: String? = null,
    val selectedListener: String? = null,
    val showStateAsDiff: Boolean = false,
    val followLatest: Boolean = true,
    val excludedActionTypes: Set<String> = emptySet(),
    val timeTravelEnabled: Boolean = false,
    val timeTravelPosition: Int = 0,
    val showActions: Boolean = true,
    val showLogicMethods: Boolean = true,
    val excludedLogicMethods: Set<String> = emptySet(),
    val callIdToMethodIdentifier: Map<String, String> = emptyMap(),
    val crashEvent: CrashEventInfo? = null,
    val destination: DevToolsDestination = DevToolsDestination.STREAM,
    val inspectorView: InspectorView = InspectorView.DELTA,
    val overlay: Overlay = Overlay.None,
    val splitFraction: Float = 0.6f,
    val hiddenLogLevels: Set<String> = emptySet(),
    val stateReads: List<StateRead> = emptyList(),
    val logicEventKeys: Set<String> = emptySet(),
    val publisherSessionStart: Long? = null,
    val canExportSession: Boolean = false,
    val activeGhostId: String? = null,
    val initialStateJson: String = "{}",
    val clientStatuses: Map<String, ServiceStatus> = emptyMap(),
    val markers: List<SessionMarker> = emptyList(),
    val searchQuery: String = "",
    val deviceLogs: List<DeviceLogRow> = emptyList(),
    val showLogs: Boolean = false,
    val pinnedTimeMs: Long? = null,
    val networkEvents: List<NetworkEventRow> = emptyList(),
    val showNetwork: Boolean = true,
    val networkBodies: Map<String, NetworkBodyLoad> = emptyMap(),
    val dataRevision: Long = 0L,
    val selection: Selection = Selection.None,
    val networkFilter: NetworkFilter = NetworkFilter(),
    val showNetworkStats: Boolean = false,
    val playbackSpeed: Float = 1f,
    val autoPlaying: Boolean = false
) : ModuleState

@Serializable
internal data class NetworkEventRow(
    val clientId: String,
    val event: NetworkRequestCapture
)

@Serializable
internal data class NetworkBodyLoad(
    val text: String = "",
    val receivedBytes: Int = 0,
    val totalBytes: Int = 0,
    val loading: Boolean = false,
    val complete: Boolean = false,
    val unavailable: Boolean = false,
    /** The body can never arrive, because the session is imported and has no device behind it. */
    val capturedOnly: Boolean = false
)

internal fun networkBodyKey(requestId: String, part: NetworkBodyPart): String = "$requestId:${part.name}"

internal fun CapturedLog.toRow(clientId: String): DeviceLogRow = DeviceLogRow(
    clientId = clientId,
    level = level,
    category = category,
    message = message,
    timestampMs = timestampMs
)

@Serializable
internal data class DeviceLogRow(
    val clientId: String,
    val level: String,
    val category: String,
    val message: String,
    val timestampMs: Long
)

/**
 * Represents crash information displayed in the timeline.
 */
@Serializable
internal data class CrashEventInfo(
    val clientId: String,
    val info: CrashInfo,
    val diagnosis: CrashDiagnosis? = null
) {
    val timestamp: Long get() = info.timestamp
    val exception: CrashException get() = info.exception
}

/**
 * Actions for the DevTools UI.
 */
internal sealed class DevToolsUiAction : ModuleAction(DevToolsUiModule::class) {
    data class UpdateConnectionState(val state: ConnectionState) : DevToolsUiAction()
    data class UpdateClientList(val clients: List<ClientInfo>) : DevToolsUiAction()
    data class AddActionStateEvent(val event: CapturedAction) : DevToolsUiAction()
    data class AddLogicMethodEvent(val event: LogicMethodEvent) : DevToolsUiAction()
    data class SelectPublisher(val clientId: String?) : DevToolsUiAction()
    data class SelectListener(val clientId: String?) : DevToolsUiAction()
    data object ToggleStateViewMode : DevToolsUiAction()
    data class SelectAction(val index: Int?) : DevToolsUiAction()
    data class SetDestination(val destination: DevToolsDestination) : DevToolsUiAction()
    data object ClearHistory : DevToolsUiAction()
    data class AddActionExclusion(val actionType: String) : DevToolsUiAction()
    data class RemoveActionExclusion(val actionType: String) : DevToolsUiAction()
    data class SetActionExclusions(val actionTypes: Set<String>) : DevToolsUiAction()
    data object ToggleTimeTravel : DevToolsUiAction()
    data class SetTimeTravelPosition(val position: Int) : DevToolsUiAction()
    data object ToggleShowActions : DevToolsUiAction()
    data object ToggleShowLogicMethods : DevToolsUiAction()
    data class SelectLogicMethodEvent(val callId: String?) : DevToolsUiAction()

    data class AddLogicMethodExclusion(val methodIdentifier: String) : DevToolsUiAction()
    data class RemoveLogicMethodExclusion(val methodIdentifier: String) : DevToolsUiAction()
    data class SetLogicMethodExclusions(val methodIdentifiers: Set<String>) : DevToolsUiAction()
    data class SetOverlay(val overlay: Overlay) : DevToolsUiAction()
    data class SetCrashEvent(val crashEvent: CrashEventInfo?) : DevToolsUiAction()
    data class SelectCrash(val selected: Boolean) : DevToolsUiAction()
    data object ClearSelection : DevToolsUiAction()
    data class SetInspectorView(val view: InspectorView) : DevToolsUiAction()
    data class SetSplitFraction(val fraction: Float) : DevToolsUiAction()
    data class ToggleLogLevel(val level: String) : DevToolsUiAction()
    data class SetPlaybackSpeed(val speed: Float) : DevToolsUiAction()
    data class SetAutoPlaying(val playing: Boolean) : DevToolsUiAction()
    data class AddMarker(val marker: SessionMarker) : DevToolsUiAction()
    data class ReplaceMarker(val marker: SessionMarker) : DevToolsUiAction()
    data class SetMarkers(val markers: List<SessionMarker>) : DevToolsUiAction()
    data class SetSearchQuery(val query: String) : DevToolsUiAction()
    data class AppendDeviceLogs(val logs: List<DeviceLogRow>) : DevToolsUiAction()
    data object ToggleShowLogs : DevToolsUiAction()
    data class AppendNetworkEvents(val events: List<NetworkEventRow>) : DevToolsUiAction()



    data class NetworkBodyNotFetchable(
        val requestId: String,
        val part: NetworkBodyPart
    ) : DevToolsUiAction()

    data class NetworkBodyRequested(
        val requestId: String,
        val part: NetworkBodyPart
    ) : DevToolsUiAction()

    data class NetworkBodyChunkArrived(
        val requestId: String,
        val part: NetworkBodyPart,
        val content: String,
        val offset: Int,
        val nextOffset: Int,
        val totalBytes: Int,
        val isLast: Boolean,
        val available: Boolean
    ) : DevToolsUiAction()
    data class SelectNetworkRequest(val requestId: String?) : DevToolsUiAction()
    data object ToggleShowNetwork : DevToolsUiAction()
    data class SetNetworkFilter(val filter: NetworkFilter) : DevToolsUiAction()
    data object ToggleNetworkStats : DevToolsUiAction()
    data class SetPinnedTime(val timeMs: Long?) : DevToolsUiAction()
    data class AddStateRead(val read: StateRead) : DevToolsUiAction()
    data class SetStateReads(val reads: List<StateRead>) : DevToolsUiAction()
    data class ResetHistoryForSync(val clearLogicEvents: Boolean) : DevToolsUiAction()
    data class SetPublisherSessionStart(val startTime: Long?) : DevToolsUiAction()
    data class SetCanExportSession(val canExport: Boolean) : DevToolsUiAction()
    data class BulkAddActionStateEvents(val events: List<CapturedAction>) : DevToolsUiAction()
    data class BulkAddLogicMethodEvents(val events: List<LogicMethodEvent>) : DevToolsUiAction()
    data class SetActiveGhostId(val ghostId: String?) : DevToolsUiAction()
    data class EnableTimeTravelWithGhost(val ghostId: String) : DevToolsUiAction()
    data class SetInitialState(val json: String) : DevToolsUiAction()

    data class SetClientStatus(val clientId: String, val status: ServiceStatus) : DevToolsUiAction()
}

@Serializable
/**
 * The client id the DevTools UI registers under.
 *
 * It appears in the client roster like any other client, so several places filter it out of
 * device lists and role assignment. Naming it once keeps those filters from drifting apart.
 */
internal const val DEVTOOLS_UI_CLIENT_ID: String = "devtools-ui"

internal enum class DevToolsDestination(val label: String) {
    STREAM("Stream"),
    STATE("State"),
    NAVIGATION("Nav"),
    PERFORMANCE("Perf"),
    NETWORK("Net"),
    FINDINGS("Findings"),
    LOGS("Logs"),
    DEVICES("Devices"),
    SESSIONS("Sessions")
}

internal val DevToolsDestination.showsCapturedData: Boolean
    get() = this != DevToolsDestination.DEVICES && this != DevToolsDestination.SESSIONS

internal enum class InspectorView(val label: String) {
    EVENT("Event"),
    DELTA("Delta")
}

@Serializable
internal sealed interface Overlay {
    @Serializable
    data object None : Overlay

    @Serializable
    data object Palette : Overlay

    @Serializable
    data object Help : Overlay

    @Serializable
    data object Marker : Overlay

    @Serializable
    data object ImportGhost : Overlay
}

internal fun logicEventKey(event: LogicMethodEvent): String = when (event) {
    is LogicMethodEvent.Started -> "S:${event.callId}"
    is LogicMethodEvent.Completed -> "C:${event.callId}"
    is LogicMethodEvent.Failed -> "F:${event.callId}"
}

/**
 * Represents a logic method tracing event from a client, wrapping the canonical
 * core tracing event together with the originating client ID.
 */
@Serializable
internal sealed class LogicMethodEvent {
    abstract val clientId: String
    abstract val timestamp: Long
    abstract val callId: String

    @Serializable
    data class Started(
        override val clientId: String,
        val event: LogicMethodStart
    ) : LogicMethodEvent() {
        override val timestamp: Long get() = event.timestampMs
        override val callId: String get() = event.callId
        val logicClass: String get() = event.logicClass
        val methodName: String get() = event.methodName
        val params: Map<String, String> get() = event.params
        val sourceFile: String? get() = event.sourceFile
        val lineNumber: Int? get() = event.lineNumber
        val githubSourceUrl: String? get() = event.githubSourceUrl
    }

    @Serializable
    data class Completed(
        override val clientId: String,
        val event: LogicMethodCompleted
    ) : LogicMethodEvent() {
        override val timestamp: Long get() = event.timestampMs
        override val callId: String get() = event.callId
        val result: String? get() = event.result
        val resultType: String get() = event.resultType
        val durationMs: Long get() = event.durationMs
    }

    @Serializable
    data class Failed(
        override val clientId: String,
        val event: LogicMethodFailed
    ) : LogicMethodEvent() {
        override val timestamp: Long get() = event.timestampMs
        override val callId: String get() = event.callId
        val exceptionType: String get() = event.exceptionType
        val exceptionMessage: String? get() = event.exceptionMessage
        val stackTrace: String? get() = event.stackTrace
        val durationMs: Long get() = event.durationMs
        val kind: LogicFailureKind get() = event.kind
    }
}

@Serializable
internal sealed interface Selection {
    @Serializable
    data object None : Selection

    @Serializable
    data class Action(val index: Int) : Selection

    @Serializable
    data class LogicCall(val callId: String) : Selection

    @Serializable
    data object Crash : Selection

    @Serializable
    data class NetworkRequest(val requestId: String) : Selection
}

internal val DevToolsUiState.selectedActionIndex: Int?
    get() = (selection as? Selection.Action)?.index

internal val DevToolsUiState.selectedLogicMethodCallId: String?
    get() = (selection as? Selection.LogicCall)?.callId

internal val DevToolsUiState.selectedNetworkRequestId: String?
    get() = (selection as? Selection.NetworkRequest)?.requestId

internal val DevToolsUiState.crashSelected: Boolean
    get() = selection is Selection.Crash

internal val DevToolsUiState.latestSelectableIndex: Int
    get() = actionStateHistory.indexOfLast { it.actionType !in excludedActionTypes }

internal val DevToolsUiState.newEventsWhilePaused: Int
    get() {
        if (followLatest) return 0
        val head = latestSelectableIndex
        val at = (selection as? Selection.Action)?.index ?: return 0
        return (head - at).coerceAtLeast(0)
    }
