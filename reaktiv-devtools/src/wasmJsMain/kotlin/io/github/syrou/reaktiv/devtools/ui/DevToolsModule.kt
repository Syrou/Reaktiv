package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import kotlinx.coroutines.launch

/**
 * Reaktiv module for DevTools UI state management.
 */
object DevToolsModule : ModuleWithLogic<DevToolsState, DevToolsAction, DevToolsLogic> {
    override val initialState: DevToolsState = DevToolsState()

    override val reducer: (DevToolsState, DevToolsAction) -> DevToolsState = { state, action ->
        when (action) {
            is DevToolsAction.UpdateConnectionState -> {
                state.copy(connectionState = action.state)
            }
            is DevToolsAction.UpdateClientList -> {
                state.copy(connectedClients = action.clients)
            }
            is DevToolsAction.AddActionEvent -> {
                state.copy(actionHistory = state.actionHistory + action.event)
            }
            is DevToolsAction.AddStateEvent -> {
                state.copy(stateHistory = state.stateHistory + action.event)
            }
            is DevToolsAction.SelectPublisher -> {
                state.copy(selectedPublisher = action.clientId)
            }
            is DevToolsAction.SelectListener -> {
                state.copy(selectedListener = action.clientId)
            }
            is DevToolsAction.ToggleStateViewMode -> {
                state.copy(showStateAsDiff = !state.showStateAsDiff)
            }
            is DevToolsAction.SelectAction -> {
                state.copy(selectedActionIndex = action.index)
            }
            is DevToolsAction.ToggleDevicePanel -> {
                state.copy(devicePanelExpanded = !state.devicePanelExpanded)
            }
            is DevToolsAction.ToggleAutoSelectLatest -> {
                state.copy(autoSelectLatest = !state.autoSelectLatest)
            }
            is DevToolsAction.ClearHistory -> {
                state.copy(
                    actionHistory = emptyList(),
                    stateHistory = emptyList(),
                    selectedActionIndex = null
                )
            }
            is DevToolsAction.AddActionExclusion -> {
                state.copy(excludedActionTypes = state.excludedActionTypes + action.actionType)
            }
            is DevToolsAction.RemoveActionExclusion -> {
                state.copy(excludedActionTypes = state.excludedActionTypes - action.actionType)
            }
            is DevToolsAction.SetActionExclusions -> {
                state.copy(excludedActionTypes = action.actionTypes)
            }
        }
    }

    override val createLogic: (StoreAccessor) -> DevToolsLogic = { storeAccessor ->
        DevToolsLogic(storeAccessor)
    }
}

/**
 * Logic for handling DevTools UI side effects.
 */
class DevToolsLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<DevToolsAction>() {
    private lateinit var connection: DevToolsConnection

    fun setConnection(conn: DevToolsConnection) {
        this.connection = conn

        storeAccessor.launch {
            connection.connectionState.collect { state ->
                storeAccessor.dispatch(DevToolsAction.UpdateConnectionState(state))
            }
        }

        connection.observeMessages { message ->
            handleServerMessage(message)
        }
    }

    suspend fun assignRole(clientId: String, role: ClientRole, publisherClientId: String? = null) {
        try {
            val message = DevToolsMessage.RoleAssignment(
                targetClientId = clientId,
                role = role,
                publisherClientId = publisherClientId
            )
            connection.send(message)
            println("DevTools UI: Assigned $clientId as $role")
        } catch (e: Exception) {
            println("DevTools UI: Failed to assign role - ${e.message}")
        }
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.ClientListUpdate -> {
                storeAccessor.dispatch(DevToolsAction.UpdateClientList(message.clients))
            }
            is DevToolsMessage.ActionDispatched -> {
                val event = ActionEvent(
                    clientId = message.clientId,
                    timestamp = message.timestamp,
                    actionType = message.actionType,
                    actionData = message.actionData
                )
                storeAccessor.dispatch(DevToolsAction.AddActionEvent(event))
            }
            is DevToolsMessage.StateUpdate -> {
                val event = StateEvent(
                    clientId = message.clientId,
                    timestamp = message.timestamp,
                    triggeringAction = message.triggeringAction,
                    stateJson = message.stateJson
                )
                storeAccessor.dispatch(DevToolsAction.AddStateEvent(event))
            }
            else -> {
                // Ignore other message types
            }
        }
    }
}
