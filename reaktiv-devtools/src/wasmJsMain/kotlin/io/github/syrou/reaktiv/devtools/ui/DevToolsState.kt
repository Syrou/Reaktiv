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
    val actionHistory: List<ActionEvent> = emptyList(),
    val stateHistory: List<StateEvent> = emptyList(),
    val selectedPublisher: String? = null,
    val selectedListener: String? = null,
    val showStateAsDiff: Boolean = false,
    val selectedActionIndex: Int? = null,
    val devicePanelExpanded: Boolean = false,
    val autoSelectLatest: Boolean = true,
    val excludedActionTypes: Set<String> = emptySet()
) : ModuleState

/**
 * Actions for the DevTools UI.
 */
sealed class DevToolsAction : ModuleAction(DevToolsModule::class) {
    data class UpdateConnectionState(val state: ConnectionState) : DevToolsAction()
    data class UpdateClientList(val clients: List<ClientInfo>) : DevToolsAction()
    data class AddActionEvent(val event: ActionEvent) : DevToolsAction()
    data class AddStateEvent(val event: StateEvent) : DevToolsAction()
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
}

/**
 * Represents an action dispatched by a client.
 */
@Serializable
data class ActionEvent(
    val clientId: String,
    val timestamp: Long,
    val actionType: String,
    val actionData: String
)

/**
 * Represents a state update from a client.
 */
@Serializable
data class StateEvent(
    val clientId: String,
    val timestamp: Long,
    val triggeringAction: String,
    val stateJson: String,
    val diff: String? = null
)
