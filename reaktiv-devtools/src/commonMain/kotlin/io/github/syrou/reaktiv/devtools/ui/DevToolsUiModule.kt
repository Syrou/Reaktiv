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
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.decodeSessionPayload
import io.github.syrou.reaktiv.introspection.encodeSessionPayload
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.json.Json

/**
 * Reaktiv module for DevTools UI state management.
 */
private fun DevToolsUiState.followHead(before: DevToolsUiState): DevToolsUiState {
    if (!before.followLatest || timeTravelEnabled) return this
    val head = latestSelectableIndex
    if (head < 0) return this
    return copy(selection = Selection.Action(head))
}

internal object DevToolsUiModule : ModuleWithLogic<DevToolsUiState, DevToolsUiAction, DevToolsUiLogic> {
    override val initialState: DevToolsUiState = DevToolsUiState()

    private val baseReducer: (DevToolsUiState, DevToolsUiAction) -> DevToolsUiState = { state, action ->
        when (action) {
            is DevToolsUiAction.UpdateConnectionState -> {
                state.copy(connectionState = action.state)
            }

            is DevToolsUiAction.UpdateClientList -> {
                state.copy(connectedClients = action.clients)
            }

            is DevToolsUiAction.AddActionStateEvent -> {
                state.copy(actionStateHistory = state.actionStateHistory + action.event)
                    .followHead(state)
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
                val index = action.index
                if (index == null) {
                    state.copy(selection = Selection.None, followLatest = false)
                } else {
                    state.copy(
                        selection = Selection.Action(index),
                        followLatest = index >= state.latestSelectableIndex
                    )
                }
            }

            is DevToolsUiAction.ToggleDevicePanel -> {
                state.copy(devicePanelExpanded = !state.devicePanelExpanded)
            }

            is DevToolsUiAction.ClearHistory -> {
                state.copy(
                    actionStateHistory = emptyList(),
                    logicMethodEvents = emptyList(),
                    selection = Selection.None,
                    followLatest = true,
                                        crashEvent = null,
                                        stateReads = emptyList(),
                    logicEventKeys = emptySet()
                )
            }

            is DevToolsUiAction.ResetHistoryForSync -> {
                if (action.clearLogicEvents) {
                    state.copy(
                        actionStateHistory = emptyList(),
                        logicMethodEvents = emptyList(),
                        selection = Selection.None,
                        followLatest = true,
                        logicEventKeys = emptySet()
                    )
                } else {
                    state.copy(
                        actionStateHistory = emptyList(),
                        selection = Selection.None,
                        followLatest = true
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
                    selection = if (newEnabled) Selection.Action(state.actionStateHistory.size - 1) else state.selection,
                    autoPlaying = false
                )
            }

            is DevToolsUiAction.SetTimeTravelPosition -> {
                state.copy(
                    timeTravelPosition = action.position,
                    selection = Selection.Action(action.position),
                    followLatest = false
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
                    selection = action.callId?.let { Selection.LogicCall(it) } ?: Selection.None,
                    followLatest = false
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

            is DevToolsUiAction.AddStateRead -> {
                if (action.read in state.stateReads) state
                else state.copy(stateReads = state.stateReads + action.read)
            }

            is DevToolsUiAction.SetStateReads -> {
                state.copy(stateReads = (state.stateReads + action.reads).distinct())
            }

            is DevToolsUiAction.SelectCrash -> {
                state.copy(
                    selection = if (action.selected) Selection.Crash else Selection.None,
                    followLatest = false
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
                    selection = if (state.actionStateHistory.isNotEmpty()) {
                        Selection.Action(state.actionStateHistory.size - 1)
                    } else {
                        Selection.None
                    },
                    followLatest = false
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
                    .followHead(state)
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

            is DevToolsUiAction.SetClientStatus -> {
                state.copy(clientStatuses = state.clientStatuses + (action.clientId to action.status))
            }

            is DevToolsUiAction.ClearSelection -> {
                state.copy(selection = Selection.None, followLatest = false)
            }

            is DevToolsUiAction.SetMode -> {
                if (action.mode == state.mode) {
                    state
                } else {
                    state.copy(
                        mode = action.mode,
                        selection = Selection.None,
                        followLatest = false
                    )
                }
            }

            is DevToolsUiAction.SetPerformanceView -> {
                state.copy(performanceView = action.view)
            }

            is DevToolsUiAction.SetInspectorTab -> {
                state.copy(inspectorTab = action.tab)
            }

            is DevToolsUiAction.SetPlaybackSpeed -> {
                state.copy(playbackSpeed = action.speed)
            }

            is DevToolsUiAction.SetAutoPlaying -> {
                when {
                    !action.playing -> state.copy(autoPlaying = false)
                    state.actionStateHistory.isEmpty() -> state
                    state.timeTravelEnabled -> state.copy(autoPlaying = true)
                    else -> {
                        val from = (state.selection as? Selection.Action)?.index ?: 0
                        state.copy(
                            autoPlaying = true,
                            timeTravelEnabled = true,
                            timeTravelPosition = from,
                            selection = Selection.Action(from)
                        )
                    }
                }
            }

            is DevToolsUiAction.AddMarker -> {
                if (state.markers.any { it.id == action.marker.id }) {
                    state
                } else {
                    state.copy(markers = state.markers + action.marker)
                }
            }

            is DevToolsUiAction.ReplaceMarker -> {
                state.copy(
                    markers = state.markers.map { if (it.id == action.marker.id) action.marker else it }
                )
            }

            is DevToolsUiAction.SetMarkers -> {
                val known = state.markers.map { it.id }.toSet()
                state.copy(markers = state.markers + action.markers.filter { it.id !in known })
            }

            is DevToolsUiAction.SetSearchQuery -> {
                state.copy(searchQuery = action.query)
            }

            is DevToolsUiAction.SetPinnedTime -> {
                state.copy(pinnedTimeMs = action.timeMs)
            }

            is DevToolsUiAction.AppendDeviceLogs -> {
                state.copy(deviceLogs = (state.deviceLogs + action.logs).takeLast(3000))
            }

            is DevToolsUiAction.ToggleShowLogs -> {
                state.copy(showLogs = !state.showLogs)
            }

            is DevToolsUiAction.AppendNetworkEvents -> {
                state.copy(
                    networkEvents = state.networkEvents.mergeNetworkEvents(action.events).takeLast(2000)
                )
            }

            is DevToolsUiAction.SelectNetworkRequest -> {
                if (action.requestId == null) {
                    state.copy(selection = Selection.None, followLatest = false)
                } else {
                    state.copy(
                        selection = Selection.NetworkRequest(action.requestId),
                        followLatest = false
                    )
                }
            }

            is DevToolsUiAction.ToggleShowNetwork -> {
                state.copy(showNetwork = !state.showNetwork)
            }

            is DevToolsUiAction.SetNetworkFilter -> {
                state.copy(networkFilter = action.filter)
            }

            is DevToolsUiAction.ToggleNetworkStats -> {
                state.copy(showNetworkStats = !state.showNetworkStats)
            }

            is DevToolsUiAction.NetworkBodyNotFetchable -> {
                val key = networkBodyKey(action.requestId, action.part)
                state.copy(
                    networkBodies = state.networkBodies + (key to NetworkBodyLoad(
                        loading = false,
                        complete = true,
                        unavailable = true,
                        capturedOnly = true
                    ))
                )
            }

            is DevToolsUiAction.NetworkBodyRequested -> {
                val key = networkBodyKey(action.requestId, action.part)
                state.copy(
                    networkBodies = state.networkBodies + (key to NetworkBodyLoad(loading = true))
                )
            }

            is DevToolsUiAction.NetworkBodyChunkArrived -> {
                val key = networkBodyKey(action.requestId, action.part)
                val current = state.networkBodies[key] ?: NetworkBodyLoad(loading = true)
                when {
                    !action.available -> state.copy(
                        networkBodies = state.networkBodies + (key to current.copy(
                            loading = false,
                            complete = true,
                            unavailable = true
                        ))
                    )
                    action.offset != current.receivedBytes -> state
                    else -> state.copy(
                        networkBodies = state.networkBodies + (key to current.copy(
                            text = current.text + action.content,
                            receivedBytes = action.nextOffset,
                            totalBytes = action.totalBytes,
                            loading = !action.isLast,
                            complete = action.isLast,
                            unavailable = false
                        ))
                    )
                }
            }

            is DevToolsUiAction.SetInitialState -> {
                state.copy(initialStateJson = action.json)
            }
        }
    }

    override val reducer: (DevToolsUiState, DevToolsUiAction) -> DevToolsUiState = { state, action ->
        val next = baseReducer(state, action)
        if (carriesDifferentData(state, next)) {
            next.copy(dataRevision = state.dataRevision + 1)
        } else {
            next
        }
    }

    private fun carriesDifferentData(before: DevToolsUiState, after: DevToolsUiState): Boolean =
        before.actionStateHistory !== after.actionStateHistory ||
            before.logicMethodEvents !== after.logicMethodEvents ||
            before.stateReads !== after.stateReads ||
            before.networkEvents !== after.networkEvents ||
            before.deviceLogs !== after.deviceLogs ||
            before.markers !== after.markers ||
            before.crashEvent !== after.crashEvent ||
            before.initialStateJson != after.initialStateJson

    override val createLogic: (StoreAccessor) -> DevToolsUiLogic = { storeAccessor ->
        DevToolsUiLogic(storeAccessor)
    }
}

/**
 * Logic for handling DevTools UI side effects.
 */
internal class DevToolsUiLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    private lateinit var connection: DevToolsConnection

    private val json = reaktivJson()

    private val ghostSessionRequests = mutableSetOf<String>()

    /**
     * Adds a marker to the selected publisher, whether it is a live device or an imported session.
     *
     * A live device owns its own capture, so the marker is requested from it and comes back through
     * [DevToolsMessage.MarkerAdded]. A ghost has no device to ask, so the marker is created here and
     * tagged `analyst` rather than `device`, which keeps post-session annotation distinguishable
     * from what the device recorded while it ran. Either way it lands in the same state and is
     * carried by the next export.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun addMarkerOnPublisher(
        publisherClientId: String,
        label: String,
        note: String = "",
        timestampMs: Long? = null,
        afterActionIndex: Int = -1
    ) {
        val isGhost = storeAccessor.selectState<DevToolsUiState>().value.activeGhostId == publisherClientId
        if (isGhost) {
            storeAccessor.dispatch(
                DevToolsUiAction.AddMarker(
                    SessionMarker(
                        id = Uuid.random().toString(),
                        label = label,
                        note = note,
                        timestampMs = timestampMs ?: currentTimeMillis(),
                        afterActionIndex = afterActionIndex,
                        source = ANALYST_MARKER_SOURCE
                    )
                )
            )
            return
        }
        try {
            connection.send(
                DevToolsMessage.AddMarkerRequest(
                    targetClientId = publisherClientId,
                    label = label,
                    note = note,
                    timestampMs = timestampMs,
                    afterActionIndex = afterActionIndex
                )
            )
        } catch (e: Exception) {
            println("DevTools UI: Failed to request marker - ${e.message}")
        }
    }

    /**
     * Replaces the label and note of an existing marker, keeping its id and position.
     *
     * Only analyst markers are editable. A device marker records what the session did and stays as
     * the device wrote it, so re-exporting cannot quietly rewrite history.
     */
    suspend fun updateMarker(markerId: String, label: String, note: String) {
        val state = storeAccessor.selectState<DevToolsUiState>().value
        val existing = state.markers.firstOrNull { it.id == markerId } ?: return
        if (existing.source != ANALYST_MARKER_SOURCE) {
            println("DevTools UI: Refusing to edit device marker $markerId")
            return
        }
        storeAccessor.dispatch(
            DevToolsUiAction.ReplaceMarker(existing.copy(label = label, note = note))
        )
    }

    /**
     * Asks the publisher for the next slice of a captured body.
     *
     * An imported session has no device behind it, so the request would never be answered and the
     * panel would sit on "waiting" forever. Whatever the session captured is all there will ever
     * be, so the load is closed as unavailable and the panel falls back to the captured preview.
     */
    suspend fun fetchNetworkBody(
        publisherClientId: String,
        requestId: String,
        part: NetworkBodyPart,
        offset: Int = 0
    ) {
        if (storeAccessor.selectState<DevToolsUiState>().value.activeGhostId != null) {
            storeAccessor.dispatch(DevToolsUiAction.NetworkBodyNotFetchable(requestId, part))
            return
        }
        try {
            if (offset == 0) {
                storeAccessor.dispatch(DevToolsUiAction.NetworkBodyRequested(requestId, part))
            }
            connection.send(
                DevToolsMessage.FetchNetworkBody(
                    targetClientId = publisherClientId,
                    requestId = requestId,
                    part = part,
                    offset = offset,
                    maxBytes = BODY_CHUNK_BYTES
                )
            )
        } catch (e: Exception) {
            println("DevTools UI: Failed to request body chunk - ${e.message}")
        }
    }

    suspend fun reconnect(clientId: String, clientName: String, platform: String) {
        if (!::connection.isInitialized) return
        try {
            connection.connect(clientId, clientName, platform)
        } catch (e: Exception) {
            println("DevTools UI: Reconnect failed - ${e.message}")
        }
    }

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
        if (history.network.isNotEmpty()) {
            storeAccessor.dispatch(
                DevToolsUiAction.AppendNetworkEvents(
                    history.network.map { NetworkEventRow(clientId = clientId, event = it) }
                )
            )
        }
        if (history.logs.isNotEmpty()) {
            storeAccessor.dispatch(
                DevToolsUiAction.AppendDeviceLogs(history.logs.map { it.toRow(clientId) })
            )
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

            // Apply locally before telling the server. Registering the ghost makes the server
            // broadcast a client list, and the handler for that asks for any ghost the UI does not
            // already hold. Applying first sets activeGhostId, so the UI recognises this ghost as
            // its own and does not pull back a copy it just imported.
            applyGhostSessionToState(export)

            val message = DevToolsMessage.GhostDeviceRegistration(
                sessionId = export.sessionId,
                originalClientInfo = originalClientInfo,
                crashException = (export.crashes.lastOrNull() ?: export.crash)?.exception,
                eventCount = export.session.actions.size,
                logicEventCount = totalLogicEvents,
                sessionStartTime = export.session.startTime,
                sessionEndTime = export.session.endTime,
                sessionExportJson = encodeSessionPayload(jsonString)
            )

            connection.send(message)

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
    /**
     * Asks the server for any ghost the UI does not already hold.
     *
     * Ghost payloads are pulled rather than pushed, so a UI that connects after an import still
     * gets the session, while devices that would only discard it never receive it. The in-flight
     * set stops a burst of client list updates from requesting the same payload repeatedly.
     */
    private suspend fun requestMissingGhostSessions(clients: List<ClientInfo>, activeGhostId: String?) {
        clients.filter { it.isGhost }.forEach { ghost ->
            if (ghost.clientId == activeGhostId) return@forEach
            if (!ghostSessionRequests.add(ghost.clientId)) return@forEach
            try {
                connection.send(DevToolsMessage.GhostSessionRequest(ghost.clientId))
                println("DevTools UI: Requested ghost session - ${ghost.clientId}")
            } catch (e: Exception) {
                ghostSessionRequests.remove(ghost.clientId)
                println("DevTools UI: Failed to request ghost session - ${e.message}")
            }
        }
    }

    private suspend fun importGhostSessionFromRestore(sessionExportJson: String, ghostClientId: String) {
        ghostSessionRequests.remove(ghostClientId)
        try {
            val export = json.decodeFromString<GhostSessionExport>(
                decodeSessionPayload(sessionExportJson)
            )

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

        if (export.session.network.isNotEmpty()) {
            storeAccessor.dispatch(
                DevToolsUiAction.AppendNetworkEvents(
                    export.session.network.map { NetworkEventRow(clientId = ghostClientId, event = it) }
                )
            )
        }

        if (export.session.logs.isNotEmpty()) {
            storeAccessor.dispatch(
                DevToolsUiAction.AppendDeviceLogs(export.session.logs.map { it.toRow(ghostClientId) })
            )
        }

        if (export.session.markers.isNotEmpty()) {
            storeAccessor.dispatch(DevToolsUiAction.SetMarkers(export.session.markers))
        }

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
        stateReads: List<StateRead> = emptyList(),
        markers: List<SessionMarker> = emptyList(),
        network: List<NetworkRequestCapture> = emptyList()
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
                stateReads = stateReads,
                markers = markers,
                network = network
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

                requestMissingGhostSessions(message.clients, state.activeGhostId)
                val publisher = message.clients.find { it.role == ClientRole.PUBLISHER && !it.isGhost }
                val listener = message.clients.find {
                    it.role == ClientRole.LISTENER && it.clientId != DEVTOOLS_UI_CLIENT_ID
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
                    assignRole(DEVTOOLS_UI_CLIENT_ID, ClientRole.ORCHESTRATOR, message.newPublisherId)
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

            is DevToolsMessage.ClientStatus -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.SetClientStatus(message.clientId, message.status)
                )
            }

            is DevToolsMessage.StateSync -> {
                // Normally the orchestrator gets its baseline from SessionHistorySync. A
                // publisher predating the role field answers an attach with a full StateSync
                // instead, so adopt that as the baseline when none has been established yet.
                // Adopting it later would misalign the reconstruction, because already
                // recorded actions predate this snapshot.
                val state = storeAccessor.selectState<DevToolsUiState>().value
                if (message.moduleName.isBlank() && state.initialStateJson == "{}") {
                    storeAccessor.dispatch(DevToolsUiAction.ResetHistoryForSync(clearLogicEvents = false))
                    storeAccessor.dispatch(DevToolsUiAction.SetInitialState(message.stateJson))
                }
            }

            is DevToolsMessage.RoleAssignment -> {
                // WASM UI handles role changes via PublisherChanged
            }

            is DevToolsMessage.RoleAcknowledgment -> {
                // Informational only
            }

            is DevToolsMessage.MarkerAdded -> {
                storeAccessor.dispatch(DevToolsUiAction.AddMarker(message.marker))
            }

            is DevToolsMessage.LogBatch -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.AppendDeviceLogs(
                        message.entries.map { it.toRow(message.clientId) }
                    )
                )
            }

            is DevToolsMessage.NetworkBatch -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.AppendNetworkEvents(
                        message.events.map { NetworkEventRow(clientId = message.clientId, event = it) }
                    )
                )
            }

            is DevToolsMessage.NetworkBodyChunk -> {
                storeAccessor.dispatch(
                    DevToolsUiAction.NetworkBodyChunkArrived(
                        requestId = message.requestId,
                        part = message.part,
                        content = message.content,
                        offset = message.offset,
                        nextOffset = message.nextOffset,
                        totalBytes = message.totalBytes,
                        isLast = message.isLast,
                        available = message.available
                    )
                )
                if (message.available && !message.isLast && message.nextOffset > message.offset) {
                    fetchNetworkBody(
                        publisherClientId = message.clientId,
                        requestId = message.requestId,
                        part = message.part,
                        offset = message.nextOffset
                    )
                }
            }

            else -> {
                println("DevTools UI: Unhandled message type: ${message::class.simpleName}")
            }
        }
    }

    internal companion object {
        /**
         * Marker source for annotations authored in the UI after a session ended, as opposed to
         * `device`, which is what a running capture writes.
         */
        const val ANALYST_MARKER_SOURCE: String = "analyst"
    }
}

private const val BODY_CHUNK_BYTES: Int = 64 * 1024
