package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionExport
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionFormat
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.StateReconstructor
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.json.Json

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

            is DevToolsAction.AddActionStateEvent -> {
                state.copy(actionStateHistory = state.actionStateHistory + action.event)
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
                state.copy(
                    selectedActionIndex = action.index,
                    selectedLogicMethodCallId = if (action.index != null) null else state.selectedLogicMethodCallId,
                    crashSelected = if (action.index != null) false else state.crashSelected
                )
            }

            is DevToolsAction.ToggleDevicePanel -> {
                state.copy(devicePanelExpanded = !state.devicePanelExpanded)
            }

            is DevToolsAction.ToggleAutoSelectLatest -> {
                state.copy(autoSelectLatest = !state.autoSelectLatest)
            }

            is DevToolsAction.ClearHistory -> {
                state.copy(
                    actionStateHistory = emptyList(),
                    logicMethodEvents = emptyList(),
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

            is DevToolsAction.ToggleTimeTravel -> {
                val newEnabled = !state.timeTravelEnabled
                state.copy(
                    timeTravelEnabled = newEnabled,
                    timeTravelPosition = if (newEnabled) state.actionStateHistory.size - 1 else 0,
                    selectedActionIndex = if (newEnabled) state.actionStateHistory.size - 1 else state.selectedActionIndex
                )
            }

            is DevToolsAction.SetTimeTravelPosition -> {
                state.copy(
                    timeTravelPosition = action.position,
                    selectedActionIndex = action.position
                )
            }

            is DevToolsAction.AddLogicMethodEvent -> {
                val newCallIdMap = if (action.event is LogicMethodEvent.Started) {
                    val started = action.event as LogicMethodEvent.Started
                    state.callIdToMethodIdentifier + (started.callId to "${started.logicClass}.${started.methodName}")
                } else {
                    state.callIdToMethodIdentifier
                }
                state.copy(
                    logicMethodEvents = state.logicMethodEvents + action.event,
                    callIdToMethodIdentifier = newCallIdMap
                )
            }

            is DevToolsAction.ToggleShowActions -> {
                state.copy(showActions = !state.showActions)
            }

            is DevToolsAction.ToggleShowLogicMethods -> {
                state.copy(showLogicMethods = !state.showLogicMethods)
            }

            is DevToolsAction.SelectLogicMethodEvent -> {
                state.copy(
                    selectedLogicMethodCallId = action.callId,
                    selectedActionIndex = if (action.callId != null) null else state.selectedActionIndex,
                    crashSelected = if (action.callId != null) false else state.crashSelected
                )
            }

            is DevToolsAction.AddLogicMethodExclusion -> {
                state.copy(excludedLogicMethods = state.excludedLogicMethods + action.methodIdentifier)
            }

            is DevToolsAction.RemoveLogicMethodExclusion -> {
                state.copy(excludedLogicMethods = state.excludedLogicMethods - action.methodIdentifier)
            }

            is DevToolsAction.SetLogicMethodExclusions -> {
                state.copy(excludedLogicMethods = action.methodIdentifiers)
            }

            is DevToolsAction.ShowImportGhostDialog -> {
                state.copy(showImportGhostDialog = true)
            }

            is DevToolsAction.HideImportGhostDialog -> {
                state.copy(showImportGhostDialog = false)
            }

            is DevToolsAction.SetCrashEvent -> {
                state.copy(crashEvent = action.crashEvent)
            }

            is DevToolsAction.SelectCrash -> {
                state.copy(
                    crashSelected = action.selected,
                    selectedActionIndex = if (action.selected) null else state.selectedActionIndex,
                    selectedLogicMethodCallId = if (action.selected) null else state.selectedLogicMethodCallId
                )
            }

            is DevToolsAction.SetActiveGhostId -> {
                state.copy(activeGhostId = action.ghostId)
            }

            is DevToolsAction.EnableTimeTravelWithGhost -> {
                state.copy(
                    activeGhostId = action.ghostId,
                    timeTravelEnabled = true,
                    timeTravelPosition = if (state.actionStateHistory.isNotEmpty()) state.actionStateHistory.size - 1 else 0,
                    selectedActionIndex = if (state.actionStateHistory.isNotEmpty()) state.actionStateHistory.size - 1 else null
                )
            }

            is DevToolsAction.SetPublisherSessionStart -> {
                state.copy(publisherSessionStart = action.startTime)
            }

            is DevToolsAction.SetCanExportSession -> {
                state.copy(canExportSession = action.canExport)
            }

            is DevToolsAction.BulkAddActionStateEvents -> {
                state.copy(actionStateHistory = state.actionStateHistory + action.events)
            }

            is DevToolsAction.BulkAddLogicMethodEvents -> {
                val newCallIdEntries = action.events
                    .filterIsInstance<LogicMethodEvent.Started>()
                    .associate { it.callId to "${it.logicClass}.${it.methodName}" }
                state.copy(
                    logicMethodEvents = state.logicMethodEvents + action.events,
                    callIdToMethodIdentifier = state.callIdToMethodIdentifier + newCallIdEntries
                )
            }

            is DevToolsAction.SetInitialState -> {
                state.copy(initialStateJson = action.json)
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
class DevToolsLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    private lateinit var connection: DevToolsConnection

    private val json = reaktivJson()

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

    suspend fun sendTimeTravelSync(
        actionHistory: List<CapturedAction>,
        initialStateJson: String,
        position: Int,
        publisherClientId: String
    ) {
        try {
            val fullStateJson = StateReconstructor.reconstructAtIndex(
                initialStateJson, actionHistory, position
            )

            val event = actionHistory.getOrNull(position) ?: return
            val message = DevToolsMessage.StateSync(
                fromClientId = publisherClientId,
                timestamp = event.timestamp,
                stateJson = fullStateJson
            )
            connection.send(message)
            println("DevTools UI: Sent time travel sync for action ${event.actionType} from publisher $publisherClientId")
        } catch (e: Exception) {
            println("DevTools UI: Failed to send time travel sync - ${e.message}")
        }
    }

    /**
     * Imports a ghost session from JSON.
     */
    suspend fun importGhostSession(jsonString: String) {
        try {
            val export = json.decodeFromString<GhostSessionExport>(jsonString)

            val originalClientInfo = ClientInfo(
                clientId = export.clientInfo.clientId,
                clientName = export.clientInfo.clientName,
                platform = export.clientInfo.platform,
                role = ClientRole.UNASSIGNED,
                publisherClientId = null,
                connectedAt = export.session.startTime,
                isGhost = true
            )

            val totalLogicEvents = export.session.logicStartedEvents.size +
                export.session.logicCompletedEvents.size +
                export.session.logicFailedEvents.size

            val message = DevToolsMessage.GhostDeviceRegistration(
                sessionId = export.sessionId,
                originalClientInfo = originalClientInfo,
                crashException = export.crash?.exception,
                eventCount = export.session.actions.size,
                logicEventCount = totalLogicEvents,
                sessionStartTime = export.session.startTime,
                sessionEndTime = export.session.endTime,
                sessionExportJson = jsonString
            )

            connection.send(message)

            applyGhostSessionToState(export)

            storeAccessor.dispatch(DevToolsAction.HideImportGhostDialog)

            println("DevTools UI: Ghost session imported - ${export.sessionId}")
        } catch (e: Exception) {
            println("DevTools UI: Failed to import ghost session - ${e.message}")
            throw e
        }
    }

    /**
     * Restores a ghost session from server-stored data without re-registering on the server.
     */
    private suspend fun importGhostSessionFromRestore(sessionExportJson: String, ghostClientId: String) {
        try {
            val export = json.decodeFromString<GhostSessionExport>(sessionExportJson)

            applyGhostSessionToState(export)

            println("DevTools UI: Ghost session restored from server - $ghostClientId")
        } catch (e: Exception) {
            println("DevTools UI: Failed to restore ghost session - ${e.message}")
        }
    }

    /**
     * Applies a parsed ghost session export to the UI state.
     * Shared by both initial import and server-side restore paths.
     */
    private suspend fun applyGhostSessionToState(export: GhostSessionExport) {
        storeAccessor.dispatch(DevToolsAction.SetInitialState(export.session.initialStateJson))

        val crashInfo = export.crash
        if (crashInfo != null) {
            val crashEvent = CrashEventInfo(
                timestamp = crashInfo.timestamp,
                clientId = export.clientInfo.clientId,
                exception = crashInfo.exception
            )
            storeAccessor.dispatch(DevToolsAction.SetCrashEvent(crashEvent))
        }

        storeAccessor.dispatch(DevToolsAction.BulkAddActionStateEvents(export.session.actions))

        val ghostClientId = export.clientInfo.clientId
        val logicEvents = buildList<LogicMethodEvent> {
            export.session.logicStartedEvents.forEach { add(LogicMethodEvent.Started(ghostClientId, it)) }
            export.session.logicCompletedEvents.forEach { add(LogicMethodEvent.Completed(ghostClientId, it)) }
            export.session.logicFailedEvents.forEach { add(LogicMethodEvent.Failed(ghostClientId, it)) }
        }
        storeAccessor.dispatch(DevToolsAction.BulkAddLogicMethodEvents(logicEvents))

        val ghostId = "ghost-${export.sessionId}"
        storeAccessor.dispatch(DevToolsAction.SelectPublisher(ghostId))
        storeAccessor.dispatch(DevToolsAction.EnableTimeTravelWithGhost(ghostId))
    }

    /**
     * Removes a ghost device.
     */
    suspend fun removeGhostDevice(ghostClientId: String) {
        try {
            val message = DevToolsMessage.GhostDeviceRemoval(ghostClientId)
            connection.send(message)
            println("DevTools UI: Requested ghost removal - $ghostClientId")
        } catch (e: Exception) {
            println("DevTools UI: Failed to remove ghost device - ${e.message}")
        }
    }

    /**
     * Exports the current session history as a ghost JSON string.
     */
    fun exportSessionAsGhost(
        clientInfo: ClientInfo,
        actionHistory: List<CapturedAction>,
        logicEvents: List<LogicMethodEvent>,
        sessionStartTime: Long,
        initialStateJson: String = "{}",
        crashEvent: CrashEventInfo? = null
    ): String {
        val now = currentTimeMillis()

        val crashInfo = crashEvent?.let {
            CrashInfo(
                timestamp = it.timestamp,
                exception = it.exception
            )
        }

        val export = GhostSessionExport(
            version = GhostSessionFormat.VERSION,
            sessionId = "${clientInfo.clientId}-$now",
            exportedAt = now,
            clientInfo = ExportedClientInfo(
                clientId = clientInfo.clientId,
                clientName = clientInfo.clientName,
                platform = clientInfo.platform
            ),
            crash = crashInfo,
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                initialStateJson = initialStateJson,
                actions = actionHistory,
                logicStartedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Started>().map { it.event },
                logicCompletedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Completed>().map { it.event },
                logicFailedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Failed>().map { it.event }
            )
        )

        return json.encodeToString(export)
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.ClientListUpdate -> {
                storeAccessor.dispatch(DevToolsAction.UpdateClientList(message.clients))

                // Auto-select devices based on their server-assigned roles
                val state = storeAccessor.selectState<DevToolsState>().value
                val publisher = message.clients.find { it.role == ClientRole.PUBLISHER && !it.isGhost }
                val listener = message.clients.find {
                    it.role == ClientRole.LISTENER && it.clientId != "devtools-ui"
                }

                if (publisher != null && state.selectedPublisher != publisher.clientId) {
                    storeAccessor.dispatch(DevToolsAction.SelectPublisher(publisher.clientId))
                }
                if (listener != null && state.selectedListener != listener.clientId) {
                    storeAccessor.dispatch(DevToolsAction.SelectListener(listener.clientId))
                }
            }

            is DevToolsMessage.ActionDispatched -> {
                storeAccessor.dispatch(DevToolsAction.AddActionStateEvent(message.event))
            }

            is DevToolsMessage.LogicMethodStarted -> {
                storeAccessor.dispatch(
                    DevToolsAction.AddLogicMethodEvent(LogicMethodEvent.Started(message.clientId, message.event))
                )
            }

            is DevToolsMessage.LogicMethodCompleted -> {
                storeAccessor.dispatch(
                    DevToolsAction.AddLogicMethodEvent(LogicMethodEvent.Completed(message.clientId, message.event))
                )
            }

            is DevToolsMessage.LogicMethodFailed -> {
                storeAccessor.dispatch(
                    DevToolsAction.AddLogicMethodEvent(LogicMethodEvent.Failed(message.clientId, message.event))
                )
            }

            is DevToolsMessage.SessionHistorySync -> {
                val history = message.history
                storeAccessor.dispatch(DevToolsAction.SetPublisherSessionStart(history.startTime))
                storeAccessor.dispatch(DevToolsAction.SetCanExportSession(true))
                storeAccessor.dispatch(DevToolsAction.SetInitialState(history.initialStateJson))

                if (history.actions.isNotEmpty()) {
                    storeAccessor.dispatch(DevToolsAction.BulkAddActionStateEvents(history.actions))
                }

                val logicEvents = buildList<LogicMethodEvent> {
                    history.logicStarted.forEach { add(LogicMethodEvent.Started(message.clientId, it)) }
                    history.logicCompleted.forEach { add(LogicMethodEvent.Completed(message.clientId, it)) }
                    history.logicFailed.forEach { add(LogicMethodEvent.Failed(message.clientId, it)) }
                }
                if (logicEvents.isNotEmpty()) {
                    storeAccessor.dispatch(DevToolsAction.BulkAddLogicMethodEvents(logicEvents))
                }
            }

            is DevToolsMessage.CrashReport -> {
                val crashEvent = CrashEventInfo(
                    timestamp = message.crash.timestamp,
                    clientId = message.clientId,
                    exception = message.crash.exception
                )
                storeAccessor.dispatch(DevToolsAction.SetCrashEvent(crashEvent))
            }

            is DevToolsMessage.PublisherChanged -> {
                println("DevTools UI: Publisher changed - ${message.previousPublisherId} -> ${message.newPublisherId}: ${message.reason}")
                if (message.newPublisherId != null) {
                    // Auto-select the new publisher
                    storeAccessor.dispatch(DevToolsAction.SelectPublisher(message.newPublisherId))
                    // Enable export capability immediately rather than waiting for SessionHistorySync
                    // which can be lost due to race conditions between publisher role assignment and
                    // orchestrator subscription
                    storeAccessor.dispatch(DevToolsAction.SetPublisherSessionStart(currentTimeMillis()))
                    storeAccessor.dispatch(DevToolsAction.SetCanExportSession(true))
                    // Auto-assign WASM UI as orchestrator for the new publisher
                    assignRole("devtools-ui", ClientRole.ORCHESTRATOR, message.newPublisherId)
                    println("DevTools UI: Auto-assigned as orchestrator for ${message.newPublisherId}")
                } else {
                    storeAccessor.dispatch(DevToolsAction.SelectPublisher(null))
                    storeAccessor.dispatch(DevToolsAction.SetPublisherSessionStart(null))
                    storeAccessor.dispatch(DevToolsAction.SetCanExportSession(false))
                }
            }

            is DevToolsMessage.ListenerAttached -> {
                // For ghost publishers: orchestrator sends reconstructed state to the new listener
                val state = storeAccessor.selectState<DevToolsState>().value
                val publisherId = state.selectedPublisher
                if (publisherId != null && state.initialStateJson != "{}") {
                    val actions = state.actionStateHistory
                    if (actions.isNotEmpty()) {
                        sendTimeTravelSync(actions, state.initialStateJson, actions.size - 1, publisherId)
                    } else {
                        val syncMessage = DevToolsMessage.StateSync(
                            fromClientId = publisherId,
                            timestamp = currentTimeMillis(),
                            stateJson = state.initialStateJson
                        )
                        connection.send(syncMessage)
                    }
                    println("DevTools UI: Sent ghost state to new listener ${message.listenerId}")
                }
            }

            is DevToolsMessage.GhostSessionRestore -> {
                importGhostSessionFromRestore(message.sessionExportJson, message.ghostClientId)
            }

            is DevToolsMessage.StateSync -> {
                // For listener middleware, not the WASM UI
            }

            is DevToolsMessage.RoleAssignment -> {
                // WASM UI handles role changes via PublisherChanged
            }

            is DevToolsMessage.RoleAcknowledgment -> {
                // Informational only
            }

            else -> {
                println("DevTools UI: Unhandled message type: ${message::class.simpleName}")
            }
        }
    }
}
