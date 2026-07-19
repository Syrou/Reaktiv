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
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.KeyframedReconstructor
import io.github.syrou.reaktiv.introspection.protocol.NavigationStatePatch
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionExport
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionFormat
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.StateReconstructor
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.json.Json

/**
 * Reaktiv module for DevTools UI state management.
 */
object DevToolsUiModule : ModuleWithLogic<DevToolsUiState, DevToolsUiAction, DevToolsUiLogic> {
    override val initialState: DevToolsUiState = DevToolsUiState()

    override val reducer: (DevToolsUiState, DevToolsUiAction) -> DevToolsUiState = { state, action ->
        when (action) {
            is DevToolsUiAction.UpdateConnectionState -> {
                state.copy(connectionState = action.state)
            }

            is DevToolsUiAction.UpdateClientList -> {
                state.copy(connectedClients = action.clients)
            }

            is DevToolsUiAction.AddActionStateEvent -> {
                state.copy(actionStateHistory = state.actionStateHistory + action.event)
            }

            is DevToolsUiAction.SelectPublisher -> {
                state.copy(selectedPublisher = action.clientId)
            }

            is DevToolsUiAction.SelectListener -> {
                state.copy(selectedListener = action.clientId)
            }

            is DevToolsUiAction.ToggleStateViewMode -> {
                state.copy(showStateAsDiff = !state.showStateAsDiff)
            }

            is DevToolsUiAction.SelectAction -> {
                state.copy(
                    selectedActionIndex = action.index,
                    selectedLogicMethodCallId = if (action.index != null) null else state.selectedLogicMethodCallId,
                    crashSelected = if (action.index != null) false else state.crashSelected
                )
            }

            is DevToolsUiAction.ToggleDevicePanel -> {
                state.copy(devicePanelExpanded = !state.devicePanelExpanded)
            }

            is DevToolsUiAction.ToggleAutoSelectLatest -> {
                state.copy(autoSelectLatest = !state.autoSelectLatest)
            }

            is DevToolsUiAction.ClearHistory -> {
                state.copy(
                    actionStateHistory = emptyList(),
                    logicMethodEvents = emptyList(),
                    selectedActionIndex = null,
                    selectedLogicMethodCallId = null,
                    crashEvent = null,
                    crashSelected = false,
                    stateReads = emptyList(),
                    logicEventKeys = emptySet()
                )
            }

            is DevToolsUiAction.ResetHistoryForSync -> {
                if (action.clearLogicEvents) {
                    state.copy(
                        actionStateHistory = emptyList(),
                        logicMethodEvents = emptyList(),
                        selectedActionIndex = null,
                        selectedLogicMethodCallId = null,
                        logicEventKeys = emptySet()
                    )
                } else {
                    state.copy(
                        actionStateHistory = emptyList(),
                        selectedActionIndex = null
                    )
                }
            }

            is DevToolsUiAction.AddActionExclusion -> {
                state.copy(excludedActionTypes = state.excludedActionTypes + action.actionType)
            }

            is DevToolsUiAction.RemoveActionExclusion -> {
                state.copy(excludedActionTypes = state.excludedActionTypes - action.actionType)
            }

            is DevToolsUiAction.SetActionExclusions -> {
                state.copy(excludedActionTypes = action.actionTypes)
            }

            is DevToolsUiAction.ToggleTimeTravel -> {
                val newEnabled = !state.timeTravelEnabled
                state.copy(
                    timeTravelEnabled = newEnabled,
                    timeTravelPosition = if (newEnabled) state.actionStateHistory.size - 1 else 0,
                    selectedActionIndex = if (newEnabled) state.actionStateHistory.size - 1 else state.selectedActionIndex
                )
            }

            is DevToolsUiAction.SetTimeTravelPosition -> {
                state.copy(
                    timeTravelPosition = action.position,
                    selectedActionIndex = action.position
                )
            }

            is DevToolsUiAction.AddLogicMethodEvent -> {
                val key = logicEventKey(action.event)
                if (key in state.logicEventKeys) {
                    state
                } else {
                    val newCallIdMap = if (action.event is LogicMethodEvent.Started) {
                        val started = action.event as LogicMethodEvent.Started
                        state.callIdToMethodIdentifier + (started.callId to "${started.logicClass}.${started.methodName}")
                    } else {
                        state.callIdToMethodIdentifier
                    }
                    state.copy(
                        logicMethodEvents = state.logicMethodEvents + action.event,
                        callIdToMethodIdentifier = newCallIdMap,
                        logicEventKeys = state.logicEventKeys + key
                    )
                }
            }

            is DevToolsUiAction.ToggleShowActions -> {
                state.copy(showActions = !state.showActions)
            }

            is DevToolsUiAction.ToggleShowLogicMethods -> {
                state.copy(showLogicMethods = !state.showLogicMethods)
            }

            is DevToolsUiAction.SelectLogicMethodEvent -> {
                state.copy(
                    selectedLogicMethodCallId = action.callId,
                    selectedActionIndex = if (action.callId != null) null else state.selectedActionIndex,
                    crashSelected = if (action.callId != null) false else state.crashSelected
                )
            }

            is DevToolsUiAction.AddLogicMethodExclusion -> {
                state.copy(excludedLogicMethods = state.excludedLogicMethods + action.methodIdentifier)
            }

            is DevToolsUiAction.RemoveLogicMethodExclusion -> {
                state.copy(excludedLogicMethods = state.excludedLogicMethods - action.methodIdentifier)
            }

            is DevToolsUiAction.SetLogicMethodExclusions -> {
                state.copy(excludedLogicMethods = action.methodIdentifiers)
            }

            is DevToolsUiAction.ShowImportGhostDialog -> {
                state.copy(showImportGhostDialog = true)
            }

            is DevToolsUiAction.HideImportGhostDialog -> {
                state.copy(showImportGhostDialog = false)
            }

            is DevToolsUiAction.SetCrashEvent -> {
                state.copy(crashEvent = action.crashEvent)
            }

            is DevToolsUiAction.SetPerformancePanel -> {
                state.copy(showPerformancePanel = action.visible)
            }

            is DevToolsUiAction.SetPerformanceWarningFilter -> {
                state.copy(performanceWarningFilter = action.filter)
            }

            is DevToolsUiAction.AddStateRead -> {
                if (action.read in state.stateReads) state
                else state.copy(stateReads = state.stateReads + action.read)
            }

            is DevToolsUiAction.SetStateReads -> {
                state.copy(stateReads = (state.stateReads + action.reads).distinct())
            }

            is DevToolsUiAction.SelectCrash -> {
                state.copy(
                    crashSelected = action.selected,
                    selectedActionIndex = if (action.selected) null else state.selectedActionIndex,
                    selectedLogicMethodCallId = if (action.selected) null else state.selectedLogicMethodCallId
                )
            }

            is DevToolsUiAction.SetActiveGhostId -> {
                state.copy(activeGhostId = action.ghostId)
            }

            is DevToolsUiAction.EnableTimeTravelWithGhost -> {
                state.copy(
                    activeGhostId = action.ghostId,
                    timeTravelEnabled = true,
                    timeTravelPosition = if (state.actionStateHistory.isNotEmpty()) state.actionStateHistory.size - 1 else 0,
                    selectedActionIndex = if (state.actionStateHistory.isNotEmpty()) state.actionStateHistory.size - 1 else null
                )
            }

            is DevToolsUiAction.SetPublisherSessionStart -> {
                state.copy(publisherSessionStart = action.startTime)
            }

            is DevToolsUiAction.SetCanExportSession -> {
                state.copy(canExportSession = action.canExport)
            }

            is DevToolsUiAction.BulkAddActionStateEvents -> {
                state.copy(actionStateHistory = state.actionStateHistory + action.events)
            }

            is DevToolsUiAction.BulkAddLogicMethodEvents -> {
                val seen = state.logicEventKeys.toMutableSet()
                val fresh = action.events.filter { seen.add(logicEventKey(it)) }
                val newCallIdEntries = fresh
                    .filterIsInstance<LogicMethodEvent.Started>()
                    .associate { it.callId to "${it.logicClass}.${it.methodName}" }
                state.copy(
                    logicMethodEvents = state.logicMethodEvents + fresh,
                    callIdToMethodIdentifier = state.callIdToMethodIdentifier + newCallIdEntries,
                    logicEventKeys = seen
                )
            }

            is DevToolsUiAction.SetInitialState -> {
                state.copy(initialStateJson = action.json)
            }
        }
    }

    override val createLogic: (StoreAccessor) -> DevToolsUiLogic = { storeAccessor ->
        DevToolsUiLogic(storeAccessor)
    }
}

/**
 * Logic for handling DevTools UI side effects.
 */
class DevToolsUiLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    private lateinit var connection: DevToolsConnection

    private val json = reaktivJson()

    fun setConnection(conn: DevToolsConnection) {
        this.connection = conn

        storeAccessor.launch {
            connection.connectionState.collect { state ->
                storeAccessor.dispatch(DevToolsUiAction.UpdateConnectionState(state))
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

    private var reconstructorCache: Triple<String, Int, KeyframedReconstructor>? = null

    private fun reconstructorFor(
        initialStateJson: String,
        actionHistory: List<CapturedAction>
    ): KeyframedReconstructor {
        val cached = reconstructorCache
        if (cached != null && cached.first == initialStateJson && cached.second == actionHistory.size) {
            return cached.third
        }
        val fresh = KeyframedReconstructor(initialStateJson, actionHistory)
        reconstructorCache = Triple(initialStateJson, actionHistory.size, fresh)
        return fresh
    }

    private var lastSyncedClientId: String? = null

    private suspend fun appendHistorySlice(
        clientId: String,
        history: SessionHistory,
        isFirstSlice: Boolean
    ) {
        if (isFirstSlice) {
            val switchedPublisher = lastSyncedClientId != null && clientId != lastSyncedClientId
            lastSyncedClientId = clientId
            storeAccessor.dispatch(DevToolsUiAction.ResetHistoryForSync(clearLogicEvents = switchedPublisher))
            storeAccessor.dispatch(DevToolsUiAction.SetPublisherSessionStart(history.startTime))
            storeAccessor.dispatch(DevToolsUiAction.SetCanExportSession(true))
            storeAccessor.dispatch(DevToolsUiAction.SetInitialState(history.initialStateJson))
        }
        if (history.actions.isNotEmpty()) {
            storeAccessor.dispatch(DevToolsUiAction.BulkAddActionStateEvents(history.actions))
        }
        if (history.stateReads.isNotEmpty()) {
            storeAccessor.dispatch(DevToolsUiAction.SetStateReads(history.stateReads))
        }
        val logicEvents = buildList<LogicMethodEvent> {
            history.logicStarted.forEach { add(LogicMethodEvent.Started(clientId, it)) }
            history.logicCompleted.forEach { add(LogicMethodEvent.Completed(clientId, it)) }
            history.logicFailed.forEach { add(LogicMethodEvent.Failed(clientId, it)) }
        }
        if (logicEvents.isNotEmpty()) {
            storeAccessor.dispatch(DevToolsUiAction.BulkAddLogicMethodEvents(logicEvents))
        }
    }

    suspend fun sendTimeTravelSync(
        actionHistory: List<CapturedAction>,
        initialStateJson: String,
        position: Int,
        publisherClientId: String
    ) {
        try {
            val fullStateJson = reconstructorFor(initialStateJson, actionHistory).stateAt(position)

            val event = actionHistory.getOrNull(position) ?: return
            val message = DevToolsMessage.StateSync(
                fromClientId = publisherClientId,
                timestamp = event.timestamp,
                stateJson = NavigationStatePatch.clearBootstrapping(fullStateJson)
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
                crashException = (export.crashes.lastOrNull() ?: export.crash)?.exception,
                eventCount = export.session.actions.size,
                logicEventCount = totalLogicEvents,
                sessionStartTime = export.session.startTime,
                sessionEndTime = export.session.endTime,
                sessionExportJson = jsonString
            )

            connection.send(message)

            applyGhostSessionToState(export)

            storeAccessor.dispatch(DevToolsUiAction.HideImportGhostDialog)

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
        storeAccessor.dispatch(DevToolsUiAction.SetInitialState(export.session.initialStateJson))

        val crashInfo = export.crashes.lastOrNull() ?: export.crash
        if (crashInfo != null) {
            val crashEvent = CrashEventInfo(
                clientId = export.clientInfo.clientId,
                info = crashInfo,
                diagnosis = export.diagnosis
            )
            storeAccessor.dispatch(DevToolsUiAction.SetCrashEvent(crashEvent))
        }

        if (export.session.stateReads.isNotEmpty()) {
            storeAccessor.dispatch(DevToolsUiAction.SetStateReads(export.session.stateReads))
        }

        storeAccessor.dispatch(DevToolsUiAction.BulkAddActionStateEvents(export.session.actions))

        val ghostClientId = export.clientInfo.clientId
        val logicEvents = buildList<LogicMethodEvent> {
            export.session.logicStartedEvents.forEach { add(LogicMethodEvent.Started(ghostClientId, it)) }
            export.session.logicCompletedEvents.forEach { add(LogicMethodEvent.Completed(ghostClientId, it)) }
            export.session.logicFailedEvents.forEach { add(LogicMethodEvent.Failed(ghostClientId, it)) }
        }
        storeAccessor.dispatch(DevToolsUiAction.BulkAddLogicMethodEvents(logicEvents))

        val ghostId = "ghost-${export.sessionId}"
        storeAccessor.dispatch(DevToolsUiAction.SelectPublisher(ghostId))
        storeAccessor.dispatch(DevToolsUiAction.EnableTimeTravelWithGhost(ghostId))
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
        crashEvent: CrashEventInfo? = null,
        stateReads: List<StateRead> = emptyList()
    ): String {
        val now = currentTimeMillis()

        val crashInfo = crashEvent?.info

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
            crashes = listOfNotNull(crashInfo),
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                initialStateJson = initialStateJson,
                actions = actionHistory,
                logicStartedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Started>().map { it.event },
                logicCompletedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Completed>().map { it.event },
                logicFailedEvents = logicEvents.filterIsInstance<LogicMethodEvent.Failed>().map { it.event },
                stateReads = stateReads
            )
        )

        return json.encodeToString(export)
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.ClientListUpdate -> {
                storeAccessor.dispatch(DevToolsUiAction.UpdateClientList(message.clients))

                // Auto-select devices based on their server-assigned roles
                val state = storeAccessor.selectState<DevToolsUiState>().value
                val publisher = message.clients.find { it.role == ClientRole.PUBLISHER && !it.isGhost }
                val listener = message.clients.find {
                    it.role == ClientRole.LISTENER && it.clientId != "devtools-ui"
                }

                if (publisher != null && state.selectedPublisher != publisher.clientId) {
                    storeAccessor.dispatch(DevToolsUiAction.SelectPublisher(publisher.clientId))
                }
                if (listener != null && state.selectedListener != listener.clientId) {
                    storeAccessor.dispatch(DevToolsUiAction.SelectListener(listener.clientId))
                }
            }

            is DevToolsMessage.ActionDispatched -> {
                storeAccessor.dispatch(DevToolsUiAction.AddActionStateEvent(message.event))
            }

            is DevToolsMessage.LogicMethodStarted -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.AddLogicMethodEvent(LogicMethodEvent.Started(message.clientId, message.event))
                )
            }

            is DevToolsMessage.LogicMethodCompleted -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.AddLogicMethodEvent(LogicMethodEvent.Completed(message.clientId, message.event))
                )
            }

            is DevToolsMessage.LogicMethodFailed -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.AddLogicMethodEvent(LogicMethodEvent.Failed(message.clientId, message.event))
                )
            }

            is DevToolsMessage.SessionHistorySync -> {
                appendHistorySlice(message.clientId, message.history, isFirstSlice = true)
            }

            is DevToolsMessage.SessionHistoryChunk -> {
                appendHistorySlice(message.clientId, message.history, isFirstSlice = message.chunkIndex == 0)
            }

            is DevToolsMessage.CrashReport -> {
                val diagnosis = message.sessionJson?.let { sessionJson ->
                    runCatching {
                        json.decodeFromString<GhostSessionExport>(sessionJson).diagnosis
                    }.getOrNull()
                }
                val crashEvent = CrashEventInfo(
                    clientId = message.clientId,
                    info = message.crash,
                    diagnosis = diagnosis
                )
                storeAccessor.dispatch(DevToolsUiAction.SetCrashEvent(crashEvent))
            }

            is DevToolsMessage.StateReadReport -> {
                storeAccessor.dispatch(DevToolsUiAction.AddStateRead(message.read))
            }

            is DevToolsMessage.PublisherChanged -> {
                println("DevTools UI: Publisher changed - ${message.previousPublisherId} -> ${message.newPublisherId}: ${message.reason}")
                if (message.newPublisherId != null) {
                    // Auto-select the new publisher
                    storeAccessor.dispatch(DevToolsUiAction.SelectPublisher(message.newPublisherId))
                    // Enable export capability immediately rather than waiting for SessionHistorySync
                    // which can be lost due to race conditions between publisher role assignment and
                    // orchestrator subscription
                    storeAccessor.dispatch(DevToolsUiAction.SetPublisherSessionStart(currentTimeMillis()))
                    storeAccessor.dispatch(DevToolsUiAction.SetCanExportSession(true))
                    // Auto-assign WASM UI as orchestrator for the new publisher
                    assignRole("devtools-ui", ClientRole.ORCHESTRATOR, message.newPublisherId)
                    println("DevTools UI: Auto-assigned as orchestrator for ${message.newPublisherId}")
                } else {
                    storeAccessor.dispatch(DevToolsUiAction.SelectPublisher(null))
                    storeAccessor.dispatch(DevToolsUiAction.SetPublisherSessionStart(null))
                    storeAccessor.dispatch(DevToolsUiAction.SetCanExportSession(false))
                }
            }

            is DevToolsMessage.ListenerAttached -> {
                // For ghost publishers: orchestrator sends reconstructed state to the new listener
                val state = storeAccessor.selectState<DevToolsUiState>().value
                val publisherId = state.selectedPublisher
                if (publisherId != null && state.initialStateJson != "{}") {
                    val actions = state.actionStateHistory
                    val position = if (state.timeTravelEnabled) {
                        state.timeTravelPosition.coerceIn(0, (actions.size - 1).coerceAtLeast(0))
                    } else {
                        actions.size - 1
                    }
                    if (actions.isNotEmpty()) {
                        sendTimeTravelSync(actions, state.initialStateJson, position, publisherId)
                    } else {
                        val syncMessage = DevToolsMessage.StateSync(
                            fromClientId = publisherId,
                            timestamp = currentTimeMillis(),
                            stateJson = NavigationStatePatch.clearBootstrapping(state.initialStateJson)
                        )
                        connection.send(syncMessage)
                    }
                    println("DevTools UI: Sent ghost state at position $position to new listener ${message.listenerId}")
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
