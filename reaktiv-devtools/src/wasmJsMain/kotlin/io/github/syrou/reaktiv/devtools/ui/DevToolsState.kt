package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import kotlinx.serialization.Serializable

/**
 * State for the DevTools WASM UI.
 */
@Serializable
data class DevToolsState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedClients: List<ClientInfo> = emptyList(),
    val actionStateHistory: List<ActionStateEvent> = emptyList(),
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
    val selectedLogicMethodCallId: String? = null
) : ModuleState

/**
 * Actions for the DevTools UI.
 */
sealed class DevToolsAction : ModuleAction(DevToolsModule::class) {
    data class UpdateConnectionState(val state: ConnectionState) : DevToolsAction()
    data class UpdateClientList(val clients: List<ClientInfo>) : DevToolsAction()
    data class AddActionStateEvent(val event: ActionStateEvent) : DevToolsAction()
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
}

/**
 * Represents an action dispatched by a client with its resulting state.
 */
@Serializable
data class ActionStateEvent(
    val clientId: String,
    val timestamp: Long,
    val actionType: String,
    val actionData: String,
    val resultingStateJson: String
)

/**
 * Represents a logic method tracing event from a client.
 */
@Serializable
sealed class LogicMethodEvent {
    abstract val clientId: String
    abstract val timestamp: Long
    abstract val callId: String

    @Serializable
    data class Started(
        override val clientId: String,
        override val timestamp: Long,
        override val callId: String,
        val logicClass: String,
        val methodName: String,
        val params: Map<String, String>,
        val sourceFile: String? = null,
        val lineNumber: Int? = null,
        val githubSourceUrl: String? = null
    ) : LogicMethodEvent()

    @Serializable
    data class Completed(
        override val clientId: String,
        override val timestamp: Long,
        override val callId: String,
        val result: String?,
        val resultType: String,
        val durationMs: Long
    ) : LogicMethodEvent()

    @Serializable
    data class Failed(
        override val clientId: String,
        override val timestamp: Long,
        override val callId: String,
        val exceptionType: String,
        val exceptionMessage: String?,
        val durationMs: Long
    ) : LogicMethodEvent()
}
