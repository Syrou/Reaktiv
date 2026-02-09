package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionExport
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionFormat
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

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

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

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

    suspend fun sendTimeTravelSync(event: ActionStateEvent, publisherClientId: String) {
        try {
            val message = DevToolsMessage.StateSync(
                fromClientId = publisherClientId,
                timestamp = event.timestamp,
                stateJson = event.resultingStateJson
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
                sessionEndTime = export.session.endTime
            )

            connection.send(message)

            val crashInfo = export.crash
            if (crashInfo != null) {
                val crashEvent = CrashEventInfo(
                    timestamp = crashInfo.timestamp,
                    clientId = export.clientInfo.clientId,
                    exception = crashInfo.exception
                )
                storeAccessor.dispatch(DevToolsAction.SetCrashEvent(crashEvent))
            }

            val actionEvents = export.session.actions.map { action ->
                ActionStateEvent(
                    clientId = action.clientId,
                    timestamp = action.timestamp,
                    actionType = action.actionType,
                    actionData = action.actionData,
                    resultingStateJson = action.resultingStateJson
                )
            }
            storeAccessor.dispatch(DevToolsAction.BulkAddActionStateEvents(actionEvents))

            val logicEvents = buildList<LogicMethodEvent> {
                export.session.logicStartedEvents.forEach { msg ->
                    add(LogicMethodEvent.Started(
                        clientId = msg.clientId,
                        timestamp = msg.timestamp,
                        callId = msg.callId,
                        logicClass = msg.logicClass,
                        methodName = msg.methodName,
                        params = msg.params,
                        sourceFile = msg.sourceFile,
                        lineNumber = msg.lineNumber,
                        githubSourceUrl = msg.githubSourceUrl
                    ))
                }
                export.session.logicCompletedEvents.forEach { msg ->
                    add(LogicMethodEvent.Completed(
                        clientId = msg.clientId,
                        timestamp = msg.timestamp,
                        callId = msg.callId,
                        result = msg.result,
                        resultType = msg.resultType,
                        durationMs = msg.durationMs
                    ))
                }
                export.session.logicFailedEvents.forEach { msg ->
                    add(LogicMethodEvent.Failed(
                        clientId = msg.clientId,
                        timestamp = msg.timestamp,
                        callId = msg.callId,
                        exceptionType = msg.exceptionType,
                        exceptionMessage = msg.exceptionMessage,
                        stackTrace = msg.stackTrace,
                        durationMs = msg.durationMs
                    ))
                }
            }
            storeAccessor.dispatch(DevToolsAction.BulkAddLogicMethodEvents(logicEvents))

            val ghostId = "ghost-${export.sessionId}"
            storeAccessor.dispatch(DevToolsAction.SelectPublisher(ghostId))
            storeAccessor.dispatch(DevToolsAction.EnableTimeTravelWithGhost(ghostId))
            storeAccessor.dispatch(DevToolsAction.HideImportGhostDialog)

            println("DevTools UI: Ghost session imported - ${export.sessionId}")
        } catch (e: Exception) {
            println("DevTools UI: Failed to import ghost session - ${e.message}")
            throw e
        }
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
        actionHistory: List<ActionStateEvent>,
        logicEvents: List<LogicMethodEvent>,
        sessionStartTime: Long
    ): String {
        val now = Clock.System.now().toEpochMilliseconds()

        val actionMessages = actionHistory.map { event ->
            DevToolsMessage.ActionDispatched(
                clientId = event.clientId,
                timestamp = event.timestamp,
                actionType = event.actionType,
                actionData = event.actionData,
                resultingStateJson = event.resultingStateJson
            )
        }

        val logicStarted = mutableListOf<DevToolsMessage.LogicMethodStarted>()
        val logicCompleted = mutableListOf<DevToolsMessage.LogicMethodCompleted>()
        val logicFailed = mutableListOf<DevToolsMessage.LogicMethodFailed>()

        logicEvents.forEach { event ->
            when (event) {
                is LogicMethodEvent.Started -> logicStarted.add(
                    DevToolsMessage.LogicMethodStarted(
                        clientId = event.clientId,
                        timestamp = event.timestamp,
                        callId = event.callId,
                        logicClass = event.logicClass,
                        methodName = event.methodName,
                        params = event.params,
                        sourceFile = event.sourceFile,
                        lineNumber = event.lineNumber,
                        githubSourceUrl = event.githubSourceUrl
                    )
                )
                is LogicMethodEvent.Completed -> logicCompleted.add(
                    DevToolsMessage.LogicMethodCompleted(
                        clientId = event.clientId,
                        timestamp = event.timestamp,
                        callId = event.callId,
                        result = event.result,
                        resultType = event.resultType,
                        durationMs = event.durationMs
                    )
                )
                is LogicMethodEvent.Failed -> logicFailed.add(
                    DevToolsMessage.LogicMethodFailed(
                        clientId = event.clientId,
                        timestamp = event.timestamp,
                        callId = event.callId,
                        exceptionType = event.exceptionType,
                        exceptionMessage = event.exceptionMessage,
                        stackTrace = event.stackTrace,
                        durationMs = event.durationMs
                    )
                )
            }
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
            crash = null,
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                actions = actionMessages.map { it.toCaptured() },
                logicStartedEvents = logicStarted.map { it.toCaptured() },
                logicCompletedEvents = logicCompleted.map { it.toCaptured() },
                logicFailedEvents = logicFailed.map { it.toCaptured() }
            )
        )

        return json.encodeToString(export)
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.ClientListUpdate -> {
                storeAccessor.dispatch(DevToolsAction.UpdateClientList(message.clients))
            }

            is DevToolsMessage.ActionDispatched -> {
                val event = ActionStateEvent(
                    clientId = message.clientId,
                    timestamp = message.timestamp,
                    actionType = message.actionType,
                    actionData = message.actionData,
                    resultingStateJson = message.resultingStateJson
                )
                storeAccessor.dispatch(DevToolsAction.AddActionStateEvent(event))
            }

            is DevToolsMessage.LogicMethodStarted -> {
                val event = LogicMethodEvent.Started(
                    clientId = message.clientId,
                    timestamp = message.timestamp,
                    callId = message.callId,
                    logicClass = message.logicClass,
                    methodName = message.methodName,
                    params = message.params,
                    sourceFile = message.sourceFile,
                    lineNumber = message.lineNumber,
                    githubSourceUrl = message.githubSourceUrl
                )
                storeAccessor.dispatch(DevToolsAction.AddLogicMethodEvent(event))
            }

            is DevToolsMessage.LogicMethodCompleted -> {
                val event = LogicMethodEvent.Completed(
                    clientId = message.clientId,
                    timestamp = message.timestamp,
                    callId = message.callId,
                    result = message.result,
                    resultType = message.resultType,
                    durationMs = message.durationMs
                )
                storeAccessor.dispatch(DevToolsAction.AddLogicMethodEvent(event))
            }

            is DevToolsMessage.LogicMethodFailed -> {
                val event = LogicMethodEvent.Failed(
                    clientId = message.clientId,
                    timestamp = message.timestamp,
                    callId = message.callId,
                    exceptionType = message.exceptionType,
                    exceptionMessage = message.exceptionMessage,
                    stackTrace = message.stackTrace,
                    durationMs = message.durationMs
                )
                storeAccessor.dispatch(DevToolsAction.AddLogicMethodEvent(event))
            }

            is DevToolsMessage.SessionHistorySync -> {
                println("DevTools UI: Received session history sync from ${message.clientId}")
                storeAccessor.dispatch(DevToolsAction.SetPublisherSessionStart(message.sessionStartTime))
                storeAccessor.dispatch(DevToolsAction.SetCanExportSession(true))

                val actionEvents = message.actionEvents.map { action ->
                    ActionStateEvent(
                        clientId = action.clientId,
                        timestamp = action.timestamp,
                        actionType = action.actionType,
                        actionData = action.actionData,
                        resultingStateJson = action.resultingStateJson
                    )
                }
                if (actionEvents.isNotEmpty()) {
                    storeAccessor.dispatch(DevToolsAction.BulkAddActionStateEvents(actionEvents))
                }

                val logicEvents = buildList<LogicMethodEvent> {
                    message.logicStartedEvents.forEach { msg ->
                        add(LogicMethodEvent.Started(
                            clientId = msg.clientId,
                            timestamp = msg.timestamp,
                            callId = msg.callId,
                            logicClass = msg.logicClass,
                            methodName = msg.methodName,
                            params = msg.params,
                            sourceFile = msg.sourceFile,
                            lineNumber = msg.lineNumber,
                            githubSourceUrl = msg.githubSourceUrl
                        ))
                    }
                    message.logicCompletedEvents.forEach { msg ->
                        add(LogicMethodEvent.Completed(
                            clientId = msg.clientId,
                            timestamp = msg.timestamp,
                            callId = msg.callId,
                            result = msg.result,
                            resultType = msg.resultType,
                            durationMs = msg.durationMs
                        ))
                    }
                    message.logicFailedEvents.forEach { msg ->
                        add(LogicMethodEvent.Failed(
                            clientId = msg.clientId,
                            timestamp = msg.timestamp,
                            callId = msg.callId,
                            exceptionType = msg.exceptionType,
                            exceptionMessage = msg.exceptionMessage,
                            stackTrace = msg.stackTrace,
                            durationMs = msg.durationMs
                        ))
                    }
                }
                if (logicEvents.isNotEmpty()) {
                    storeAccessor.dispatch(DevToolsAction.BulkAddLogicMethodEvents(logicEvents))
                }
            }

            is DevToolsMessage.CrashReport -> {
                println("DevTools UI: Received CrashReport from ${message.clientId}")
                val crashEvent = CrashEventInfo(
                    timestamp = message.timestamp,
                    clientId = message.clientId,
                    exception = CrashException(
                        exceptionType = message.exceptionType,
                        message = message.exceptionMessage,
                        stackTrace = message.stackTrace ?: ""
                    )
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
                    storeAccessor.dispatch(DevToolsAction.SetPublisherSessionStart(Clock.System.now().toEpochMilliseconds()))
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

            else -> {
                println("DevTools UI: Unhandled message type: ${message::class.simpleName}")
            }
        }
    }
}
