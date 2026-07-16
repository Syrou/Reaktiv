package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import kotlinx.serialization.Serializable

/**
 * State for the DevTools WASM UI.
 */
@Serializable
data class DevToolsState(
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
    val timestamp: Long,
    val clientId: String,
    val exception: CrashException
)

/**
 * Actions for the DevTools UI.
 */
sealed class DevToolsAction : ModuleAction(DevToolsModule::class) {
    data class UpdateConnectionState(val state: ConnectionState) : DevToolsAction()
    data class UpdateClientList(val clients: List<ClientInfo>) : DevToolsAction()
    data class AddActionStateEvent(val event: CapturedAction) : DevToolsAction()
    data class AddLogicMethodEvent(val event: LogicMethodEvent) : DevToolsAction()
    data class SelectPublisher(val clientId: String?) : DevToolsAction()
    data class SelectListener(val clientId: String?) : DevToolsAction()
    data object ToggleStateViewMode : DevToolsAction()
    data class SelectAction(val index: Int?) : DevToolsAction()
    data object ToggleDevicePanel : DevToolsAction()
    data object ToggleAutoSelectLatest : DevToolsAction()
    data object ClearHistory : DevToolsAction()
    data class AddActionExclusion(val actionType: String) : DevToolsAction()
    data class RemoveActionExclusion(val actionType: String) : DevToolsAction()
    data class SetActionExclusions(val actionTypes: Set<String>) : DevToolsAction()
    data object ToggleTimeTravel : DevToolsAction()
    data class SetTimeTravelPosition(val position: Int) : DevToolsAction()
    data object ToggleShowActions : DevToolsAction()
    data object ToggleShowLogicMethods : DevToolsAction()
    data class SelectLogicMethodEvent(val callId: String?) : DevToolsAction()

    data class AddLogicMethodExclusion(val methodIdentifier: String) : DevToolsAction()
    data class RemoveLogicMethodExclusion(val methodIdentifier: String) : DevToolsAction()
    data class SetLogicMethodExclusions(val methodIdentifiers: Set<String>) : DevToolsAction()
    data object ShowImportGhostDialog : DevToolsAction()
    data object HideImportGhostDialog : DevToolsAction()
    data class SetCrashEvent(val crashEvent: CrashEventInfo?) : DevToolsAction()
    data class SelectCrash(val selected: Boolean) : DevToolsAction()
    data class SetPublisherSessionStart(val startTime: Long?) : DevToolsAction()
    data class SetCanExportSession(val canExport: Boolean) : DevToolsAction()
    data class BulkAddActionStateEvents(val events: List<CapturedAction>) : DevToolsAction()
    data class BulkAddLogicMethodEvents(val events: List<LogicMethodEvent>) : DevToolsAction()
    data class SetActiveGhostId(val ghostId: String?) : DevToolsAction()
    data class EnableTimeTravelWithGhost(val ghostId: String) : DevToolsAction()
    data class SetInitialState(val json: String) : DevToolsAction()
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
