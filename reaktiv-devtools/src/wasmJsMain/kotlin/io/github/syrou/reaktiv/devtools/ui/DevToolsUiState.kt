package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import kotlinx.serialization.Serializable

/**
 * State for the DevTools WASM UI.
 */
@Serializable
data class DevToolsUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedClients: List<ClientInfo> = emptyList(),
    val actionStateHistory: List<CapturedAction> = emptyList(),
    val logicMethodEvents: List<LogicMethodEvent> = emptyList(),
    val selectedPublisher: String? = null,
    val selectedListener: String? = null,
    val showStateAsDiff: Boolean = false,
    val selectedActionIndex: Int? = null,
    val devicePanelExpanded: Boolean = false,
    val autoSelectLatest: Boolean = true,
    val excludedActionTypes: Set<String> = emptySet(),
    val timeTravelEnabled: Boolean = false,
    val timeTravelPosition: Int = 0,
    val showActions: Boolean = true,
    val showLogicMethods: Boolean = true,
    val selectedLogicMethodCallId: String? = null,
    val excludedLogicMethods: Set<String> = emptySet(),
    val callIdToMethodIdentifier: Map<String, String> = emptyMap(),
    val showImportGhostDialog: Boolean = false,
    val crashEvent: CrashEventInfo? = null,
    val crashSelected: Boolean = false,
    val showPerformancePanel: Boolean = false,
    val performanceWarningFilter: WarningFilter = WarningFilter.ALL,
    val stateReads: List<StateRead> = emptyList(),
    val logicEventKeys: Set<String> = emptySet(),
    val publisherSessionStart: Long? = null,
    val canExportSession: Boolean = false,
    val activeGhostId: String? = null,
    val initialStateJson: String = "{}"
) : ModuleState

/**
 * Represents crash information displayed in the timeline.
 */
@Serializable
data class CrashEventInfo(
    val clientId: String,
    val info: CrashInfo
) {
    val timestamp: Long get() = info.timestamp
    val exception: CrashException get() = info.exception
}

/**
 * Actions for the DevTools UI.
 */
sealed class DevToolsUiAction : ModuleAction(DevToolsUiModule::class) {
    data class UpdateConnectionState(val state: ConnectionState) : DevToolsUiAction()
    data class UpdateClientList(val clients: List<ClientInfo>) : DevToolsUiAction()
    data class AddActionStateEvent(val event: CapturedAction) : DevToolsUiAction()
    data class AddLogicMethodEvent(val event: LogicMethodEvent) : DevToolsUiAction()
    data class SelectPublisher(val clientId: String?) : DevToolsUiAction()
    data class SelectListener(val clientId: String?) : DevToolsUiAction()
    data object ToggleStateViewMode : DevToolsUiAction()
    data class SelectAction(val index: Int?) : DevToolsUiAction()
    data object ToggleDevicePanel : DevToolsUiAction()
    data object ToggleAutoSelectLatest : DevToolsUiAction()
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
    data object ShowImportGhostDialog : DevToolsUiAction()
    data object HideImportGhostDialog : DevToolsUiAction()
    data class SetCrashEvent(val crashEvent: CrashEventInfo?) : DevToolsUiAction()
    data class SelectCrash(val selected: Boolean) : DevToolsUiAction()
    data class SetPerformancePanel(val visible: Boolean) : DevToolsUiAction()
    data class AddStateRead(val read: StateRead) : DevToolsUiAction()
    data class SetStateReads(val reads: List<StateRead>) : DevToolsUiAction()
    data class ResetHistoryForSync(val clearLogicEvents: Boolean) : DevToolsUiAction()
    data class SetPerformanceWarningFilter(val filter: WarningFilter) : DevToolsUiAction()
    data class SetPublisherSessionStart(val startTime: Long?) : DevToolsUiAction()
    data class SetCanExportSession(val canExport: Boolean) : DevToolsUiAction()
    data class BulkAddActionStateEvents(val events: List<CapturedAction>) : DevToolsUiAction()
    data class BulkAddLogicMethodEvents(val events: List<LogicMethodEvent>) : DevToolsUiAction()
    data class SetActiveGhostId(val ghostId: String?) : DevToolsUiAction()
    data class EnableTimeTravelWithGhost(val ghostId: String) : DevToolsUiAction()
    data class SetInitialState(val json: String) : DevToolsUiAction()
}

@Serializable
enum class WarningFilter {
    ALL,
    WARNINGS_ONLY,
    HIDDEN
}

fun logicEventKey(event: LogicMethodEvent): String = when (event) {
    is LogicMethodEvent.Started -> "S:${event.callId}"
    is LogicMethodEvent.Completed -> "C:${event.callId}"
    is LogicMethodEvent.Failed -> "F:${event.callId}"
}

/**
 * Represents a logic method tracing event from a client, wrapping the canonical
 * core tracing event together with the originating client ID.
 */
@Serializable
sealed class LogicMethodEvent {
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
    }
}
