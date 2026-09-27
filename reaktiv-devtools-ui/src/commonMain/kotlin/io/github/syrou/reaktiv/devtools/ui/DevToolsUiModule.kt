package io.github.syrou.reaktiv.devtools.ui

import kotlinx.coroutines.Job
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
import io.github.syrou.reaktiv.devtools.service.DevToolsCommands
import io.github.syrou.reaktiv.devtools.service.SESSION_EXPORT_REQUEST
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import io.github.syrou.reaktiv.introspection.protocol.NavigationStatePatch
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionExport
import io.github.syrou.reaktiv.devtools.protocol.GhostSessionFormat
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.decodeSessionPayload
import io.github.syrou.reaktiv.introspection.encodeSessionPayload
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_SERVICE
import io.github.syrou.reaktiv.devtools.ui.navmap.OPEN_LINK_REQUEST
import io.github.syrou.reaktiv.devtools.ui.navmap.APP_LINKS_REQUEST
import io.github.syrou.reaktiv.devtools.ui.navmap.appLinksPayload
import io.github.syrou.reaktiv.devtools.ui.navmap.openLinkPayload
import kotlinx.serialization.encodeToString
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.json.Json

/**
 * Reaktiv module for DevTools UI state management.
 */
internal const val LINK_ANSWER_TIMEOUT_MS: Long = 20_000L

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
                val selected = if (state.activeGhostId == null || state.activeGhostId == action.clientId) {
                    state.copy(selectedPublisher = action.clientId)
                } else {
                    state.copy(selectedPublisher = action.clientId, activeGhostId = null, timeTravelEnabled = false)
                }
                if (action.clientId == null) selected.copy(publisherSessionStart = null) else selected
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

            is DevToolsUiAction.SetDestination -> {
                state.copy(destination = action.destination)
            }

            is DevToolsUiAction.ClearHistory -> {
                val history = state.actionStateHistory
                state.copy(
                    initialStateJson = if (history.isEmpty()) {
                        state.initialStateJson
                    } else {
                        Reconstruction.stateAt(state.initialStateJson, history, history.size - 1)
                    },
                    actionStateHistory = emptyList(),
                    logicMethodEvents = emptyList(),
                    selection = state.selection.takeIf { it.isMapSelection } ?: Selection.None,
                    followLatest = !state.selection.isMapSelection,
                    crashEvent = null,
                    stateReads = emptyList(),
                    logicEventKeys = emptySet()
                )
            }

            is DevToolsUiAction.ClearSession -> {
                val kept = state.selection.takeIf { it.isMapSelection } ?: Selection.None
                state.copy(
                    actionStateHistory = emptyList(),
                    logicMethodEvents = emptyList(),
                    logicEventKeys = emptySet(),
                    callIdToMethodIdentifier = emptyMap(),
                    crashEvent = null,
                    stateReads = emptyList(),
                    markers = emptyList(),
                    deviceLogs = emptyList(),
                    networkEvents = emptyList(),
                    networkBodies = emptyMap(),
                    initialStateJson = "{}",
                    extensions = emptyMap(),
                    timeTravelPosition = 0,
                    autoPlaying = false,
                    pinnedTimeMs = null,
                    selection = kept,
                    followLatest = !kept.isMapSelection
                )
            }

            is DevToolsUiAction.GhostImportFailed -> {
                state.copy(ghostImportError = action.reason)
            }

            is DevToolsUiAction.ResetHistoryForSync -> {
                val kept = state.selection.takeIf { it.isMapSelection } ?: Selection.None
                state.copy(
                    actionStateHistory = emptyList(),
                    selection = kept,
                    followLatest = !kept.isMapSelection
                )
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

            is DevToolsUiAction.ToggleTimeTravel -> if (state.actionStateHistory.isEmpty()) state else {
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

            is DevToolsUiAction.SetOverlay -> {
                state.copy(overlay = action.overlay, ghostImportError = null)
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

            is DevToolsUiAction.SetViewChoices -> {
                state.copy(view = action.choices)
            }

            is DevToolsUiAction.SessionSliceArrived -> {
                action.parts.fold(state) { current, part -> reduce(current, part) }
            }

            is DevToolsUiAction.SetPublisherSessionStart -> {
                state.copy(publisherSessionStart = action.startTime)
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

            is DevToolsUiAction.SetInspectorView -> {
                state.copy(inspectorView = action.view)
            }

            is DevToolsUiAction.SetSplitFraction -> {
                state.copy(splitFraction = action.fraction.coerceIn(0.3f, 0.8f))
            }

            is DevToolsUiAction.ToggleLogLevel -> {
                val hidden = if (action.level in state.hiddenLogLevels) {
                    state.hiddenLogLevels - action.level
                } else {
                    state.hiddenLogLevels + action.level
                }
                state.copy(hiddenLogLevels = hidden)
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
                val shown = state.deviceLogs.toHashSet()
                state.copy(deviceLogs = (state.deviceLogs + action.logs.filterNot { it in shown }).takeLast(3000))
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

            is DevToolsUiAction.SetExtensions -> {
                state.copy(extensions = action.extensions)
            }

            is DevToolsUiAction.SetNavigationView -> {
                state.copy(navigationView = action.view)
            }

            is DevToolsUiAction.ToggleGraphCollapsed -> {
                val collapsed = state.collapsedGraphs
                state.copy(
                    collapsedGraphs = if (action.graphId in collapsed) collapsed - action.graphId else collapsed + action.graphId
                )
            }

            is DevToolsUiAction.SetCollapsedGraphs -> {
                state.copy(collapsedGraphs = action.graphIds)
            }

            is DevToolsUiAction.SelectRoute -> {
                state.copy(
                    selection = action.path?.let { Selection.Route(it) } ?: Selection.None,
                    followLatest = false
                )
            }

            is DevToolsUiAction.SelectGraph -> {
                state.copy(
                    selection = action.graphId?.let { Selection.Graph(it) } ?: Selection.None,
                    followLatest = false
                )
            }

            is DevToolsUiAction.SetLinkDraft -> {
                state.copy(linkDrafts = state.linkDrafts + (action.path to action.draft))
            }

            is DevToolsUiAction.LinkAttemptStarted -> {
                state.copy(linkAttempts = (state.linkAttempts + action.attempt).takeLast(MAX_LINK_ATTEMPTS))
            }

            is DevToolsUiAction.ServiceReplyReceived -> {
                state.updateCall(action.requestId) { it.answer(action.result, action.error) }
            }

            is DevToolsUiAction.ServiceRequestUnanswered -> {
                state.updateCall(action.requestId) { it.giveUp() }
            }

            is DevToolsUiAction.SetAppLinksForm -> {
                state.copy(appLinksForm = action.form)
            }

            is DevToolsUiAction.SetAppLinksTab -> {
                state.copy(appLinksTab = action.tab)
            }

            is DevToolsUiAction.AppLinksRequested -> {
                state.copy(appLinksCall = action.call)
            }
        }
    }

    override val reducer: (DevToolsUiState, DevToolsUiAction) -> DevToolsUiState = baseReducer

    private fun reduce(state: DevToolsUiState, action: DevToolsUiAction): DevToolsUiState = baseReducer(state, action)

    override val createLogic: (StoreAccessor) -> DevToolsUiLogic = { storeAccessor ->
        DevToolsUiLogic(storeAccessor)
    }

    override val createMiddleware: (() -> Middleware) = {
        Middleware { action, _, storeAccessor, updatedState ->
            updatedState(action)
            if (action is DevToolsUiAction) {
                storeAccessor.launch { selectLogicTyped(storeAccessor).react(action) }
            }
        }
    }
}

/**
 * Logic for handling DevTools UI side effects.
 */
internal class DevToolsUiLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
    private lateinit var connection: DevToolsConnection

    private val json = reaktivJson()

    private val ghostSessionRequests = mutableSetOf<String>()

    private var importedSession: Pair<String, GhostSessionExport>? = null

    private val pendingReplies = mutableMapOf<String, CompletableDeferred<DevToolsMessage.ServiceReply>>()
    private val pendingRepliesLock = Mutex()

    private var autoplay: Job? = null

    private suspend fun currentState(): DevToolsUiState = storeAccessor.selectState<DevToolsUiState>().value

    suspend fun connect(serverUrl: String) {
        ReaktivDebug.general("DevTools UI connecting to $serverUrl")
        val connection = DevToolsConnection(serverUrl)
        setConnection(connection)
        connection.connect(DEVTOOLS_UI_CLIENT_ID, "DevTools UI", "WASM Browser")
    }

    internal suspend fun react(action: DevToolsUiAction) {
        when (action) {
            is DevToolsUiAction.SessionSliceArrived -> action.parts.forEach { react(it) }
            is DevToolsUiAction.SetTimeTravelPosition,
            is DevToolsUiAction.ToggleTimeTravel,
            is DevToolsUiAction.EnableTimeTravelWithGhost,
            is DevToolsUiAction.SelectPublisher -> syncFollowers()
            is DevToolsUiAction.SetAutoPlaying -> {
                autoplay?.cancel()
                autoplay = if (action.playing) storeAccessor.launch { play() } else null
                syncFollowers()
            }
            is DevToolsUiAction.SelectNetworkRequest -> action.requestId?.let { fetchTruncatedBodies(it) }
            is DevToolsUiAction.AppendNetworkEvents -> {
                val selected = currentState().selectedNetworkRequestId ?: return
                if (action.events.any { it.event.id == selected }) fetchTruncatedBodies(selected)
            }
            else -> Unit
        }
    }

    private suspend fun syncFollowers() {
        val state = currentState()
        val publisher = state.selectedPublisher ?: return
        if (!state.timeTravelEnabled || state.timeTravelPosition >= state.actionStateHistory.size) return
        sendTimeTravelSync(state.actionStateHistory, state.initialStateJson, state.timeTravelPosition, publisher)
    }

    private suspend fun play() {
        while (true) {
            val state = currentState()
            if (!state.autoPlaying) return
            val playhead = state.selectedActionIndex ?: 0
            if (playhead >= state.actionStateHistory.size - 1) {
                storeAccessor.dispatch(DevToolsUiAction.SetAutoPlaying(false))
                return
            }
            delay((1000 / state.playbackSpeed).toLong())
            val now = currentState()
            if (!now.autoPlaying) return
            now.seekTo(playhead + 1)?.let { storeAccessor.dispatchAndAwait(it) }
        }
    }

    private suspend fun fetchTruncatedBodies(requestId: String) {
        val state = currentState()
        val row = state.networkEvents.lastOrNull { it.event.id == requestId } ?: return
        val event = row.event
        listOf(
            NetworkBodyPart.REQUEST to event.requestBodyTruncated,
            NetworkBodyPart.RESPONSE to event.responseBodyTruncated
        ).forEach { (part, truncated) ->
            if (truncated && networkBodyKey(requestId, part) !in state.networkBodies) {
                fetchNetworkBody(row.clientId, requestId, part)
            }
        }
    }

    suspend fun addMarkerAtPinnedTime(label: String, note: String) {
        val state = currentState()
        val publisher = state.selectedPublisher ?: return
        val pinned = state.pinnedTimeMs ?: return
        addMarkerOnPublisher(
            publisherClientId = publisher,
            label = label,
            note = note,
            timestampMs = pinned,
            afterActionIndex = state.actionStateHistory.nearestIndexTo(pinned) ?: -1
        )
        storeAccessor.dispatch(DevToolsUiAction.SetPinnedTime(null))
    }

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
        val sent = sendToServer(
            DevToolsMessage.AddMarkerRequest(
                targetClientId = publisherClientId,
                label = label,
                note = note,
                timestampMs = timestampMs,
                afterActionIndex = afterActionIndex
            )
        )
        if (!sent) ReaktivDebug.warn("DevTools UI: Could not request a marker, $SERVER_UNREACHABLE")
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
        if (storeAccessor.selectState<DevToolsUiState>().value.activeGhostId == publisherClientId) {
            storeAccessor.dispatch(DevToolsUiAction.NetworkBodyNotFetchable(requestId, part))
            return
        }
        if (offset == 0) {
            storeAccessor.dispatch(DevToolsUiAction.NetworkBodyRequested(requestId, part))
        }
        val sent = sendToServer(
            DevToolsMessage.FetchNetworkBody(
                targetClientId = publisherClientId,
                requestId = requestId,
                part = part,
                offset = offset,
                maxBytes = BODY_CHUNK_BYTES
            )
        )
        if (!sent) ReaktivDebug.warn("DevTools UI: Could not request a body chunk, $SERVER_UNREACHABLE")
    }

    suspend fun reconnect(clientId: String, clientName: String, platform: String) {
        if (!::connection.isInitialized) return
        connection.connect(clientId, clientName, platform)
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
        val sent = sendToServer(
            DevToolsMessage.RoleAssignment(
                targetClientId = clientId,
                role = role,
                publisherClientId = publisherClientId
            )
        )
        if (sent) {
            ReaktivDebug.general("DevTools UI: Asked for $clientId to be $role")
        } else {
            ReaktivDebug.warn("DevTools UI: Could not assign $role to $clientId, $SERVER_UNREACHABLE")
        }
    }

    private var lastSyncedClientId: String? = null

    private suspend fun appendHistorySlice(
        clientId: String,
        history: SessionHistory,
        isFirstSlice: Boolean
    ) {
        val start = when {
            !isFirstSlice -> SliceStart.CONTINUE
            lastSyncedClientId != null && clientId != lastSyncedClientId -> SliceStart.NEW_SESSION
            else -> SliceStart.RESYNC
        }
        if (isFirstSlice) lastSyncedClientId = clientId
        storeAccessor.dispatch(DevToolsUiAction.SessionSliceArrived(sliceParts(clientId, history, start)))
    }

    private enum class SliceStart { NEW_SESSION, RESYNC, CONTINUE }

    private fun sliceParts(clientId: String, history: SessionHistory, start: SliceStart): List<DevToolsUiAction> = buildList {
        when (start) {
            SliceStart.NEW_SESSION -> add(DevToolsUiAction.ClearSession)
            SliceStart.RESYNC -> add(DevToolsUiAction.ResetHistoryForSync)
            SliceStart.CONTINUE -> Unit
        }
        if (start != SliceStart.CONTINUE) {
            add(DevToolsUiAction.SetPublisherSessionStart(history.startTime))
            add(DevToolsUiAction.SetInitialState(history.initialStateJson))
            add(DevToolsUiAction.SetExtensions(history.extensions))
        }
        if (history.actions.isNotEmpty()) add(DevToolsUiAction.BulkAddActionStateEvents(history.actions))
        if (history.stateReads.isNotEmpty()) add(DevToolsUiAction.SetStateReads(history.stateReads))
        if (history.network.isNotEmpty()) {
            add(DevToolsUiAction.AppendNetworkEvents(history.network.map { NetworkEventRow(clientId = clientId, event = it) }))
        }
        if (history.logs.isNotEmpty()) add(DevToolsUiAction.AppendDeviceLogs(history.logs.map { it.toRow(clientId) }))
        val logicEvents = buildList<LogicMethodEvent> {
            history.logicStarted.forEach { add(LogicMethodEvent.Started(clientId, it)) }
            history.logicCompleted.forEach { add(LogicMethodEvent.Completed(clientId, it)) }
            history.logicFailed.forEach { add(LogicMethodEvent.Failed(clientId, it)) }
        }
        if (logicEvents.isNotEmpty()) add(DevToolsUiAction.BulkAddLogicMethodEvents(logicEvents))
        if (history.markers.isNotEmpty()) add(DevToolsUiAction.SetMarkers(history.markers))
    }

    private fun GhostSessionExport.toHistory(): SessionHistory = SessionHistory(
        startTime = session.startTime,
        initialStateJson = session.initialStateJson,
        actions = session.actions,
        logicStarted = session.logicStartedEvents,
        logicCompleted = session.logicCompletedEvents,
        logicFailed = session.logicFailedEvents,
        stateReads = session.stateReads,
        markers = session.markers,
        network = session.network,
        logs = session.logs,
        extensions = extensions
    )

    suspend fun sendTimeTravelSync(
        actionHistory: List<CapturedAction>,
        initialStateJson: String,
        position: Int,
        publisherClientId: String
    ) {
        val event = actionHistory.getOrNull(position) ?: return
        val fullStateJson = try {
            Reconstruction.stateAt(initialStateJson, actionHistory, position)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools UI: Could not rebuild the state at action $position - ${e.message}")
            return
        }
        val sent = sendToServer(
            DevToolsMessage.StateSync(
                fromClientId = publisherClientId,
                timestamp = event.timestamp,
                stateJson = NavigationStatePatch.clearBootstrapping(fullStateJson)
            )
        )
        if (!sent) ReaktivDebug.warn("DevTools UI: Could not send the time travel state, $SERVER_UNREACHABLE")
    }

    /**
     * Imports a ghost session from JSON.
     */
    suspend fun importGhostSession(jsonString: String) {
        val export = try {
            json.decodeFromString<GhostSessionExport>(jsonString)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storeAccessor.dispatch(DevToolsUiAction.GhostImportFailed("This is not a session file: ${e.message}"))
            return
        }

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
            sessionStartTime = export.session.startTime,
            sessionEndTime = export.session.endTime,
            sessionExportJson = encodeSessionPayload(jsonString)
        )

        if (!sendToServer(message)) {
            storeAccessor.dispatch(
                DevToolsUiAction.GhostImportFailed("The session opened here, but the DevTools server could not be reached")
            )
            return
        }

        storeAccessor.dispatch(DevToolsUiAction.SetOverlay(Overlay.None))

        ReaktivDebug.general("DevTools UI: Ghost session imported - ${export.sessionId}")
    }

    private suspend fun sendToServer(message: DevToolsMessage): Boolean =
        ::connection.isInitialized && connection.send(message)

    /**
     * Asks the server for any ghost the UI does not already hold.
     *
     * Ghost payloads are pulled rather than pushed, so a UI that connects after an import still
     * gets the session, while devices that would only discard it never receive it. The in-flight
     * set stops a burst of client list updates from requesting the same payload repeatedly.
     */
    private suspend fun requestMissingGhostSessions(clients: List<ClientInfo>) {
        clients.filter { it.isGhost }.forEach { ghost ->
            if (!ghostSessionRequests.add(ghost.clientId)) return@forEach
            if (sendToServer(DevToolsMessage.GhostSessionRequest(ghost.clientId))) {
                ReaktivDebug.general("DevTools UI: Requested ghost session - ${ghost.clientId}")
            } else {
                ghostSessionRequests.remove(ghost.clientId)
            }
        }
    }

    /**
     * Restores a ghost session from server-stored data without re-registering on the server.
     */
    private suspend fun importGhostSessionFromRestore(sessionExportJson: String, ghostClientId: String) {
        try {
            val export = json.decodeFromString<GhostSessionExport>(
                decodeSessionPayload(sessionExportJson)
            )

            applyGhostSessionToState(export)

            ReaktivDebug.general("DevTools UI: Ghost session restored from server - $ghostClientId")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools UI: Could not restore ghost session $ghostClientId - ${e.message}")
        }
    }

    /**
     * Applies a parsed ghost session export to the UI state.
     * Shared by both initial import and server-side restore paths.
     */
    private suspend fun applyGhostSessionToState(export: GhostSessionExport) {
        val ghostId = "ghost-${export.sessionId}"
        importedSession = ghostId to export
        ghostSessionRequests.add(ghostId)
        lastSyncedClientId = ghostId
        val crash = (export.crashes.lastOrNull() ?: export.crash)?.let { info ->
            DevToolsUiAction.SetCrashEvent(
                CrashEventInfo(clientId = export.clientInfo.clientId, info = info, diagnosis = export.diagnosis)
            )
        }
        storeAccessor.dispatch(
            DevToolsUiAction.SessionSliceArrived(
                sliceParts(export.clientInfo.clientId, export.toHistory(), SliceStart.NEW_SESSION) +
                    listOfNotNull(crash) +
                    DevToolsUiAction.SelectPublisher(ghostId) +
                    DevToolsUiAction.EnableTimeTravelWithGhost(ghostId)
            )
        )
    }

    /**
     * Removes a ghost device.
     */
    suspend fun removeGhostDevice(ghostClientId: String) {
        if (!sendToServer(DevToolsMessage.GhostDeviceRemoval(ghostClientId))) {
            ReaktivDebug.warn("DevTools UI: Could not remove ghost $ghostClientId, $SERVER_UNREACHABLE")
        }
    }

    suspend fun exportSession(): String {
        val state = storeAccessor.selectState<DevToolsUiState>().value
        val publisher = state.selectedPublisher ?: error("No device is selected")
        val imported = importedSession
        if (state.activeGhostId == publisher && imported != null && imported.first == publisher) {
            val export = imported.second
            return json.encodeToString(
                export.copy(
                    exportedAt = currentTimeMillis(),
                    session = export.session.copy(markers = state.markers)
                )
            )
        }
        if (state.connectedClients.any { it.clientId == publisher && !it.isGhost }) {
            try {
                return exportFromDevice(publisher)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                ReaktivDebug.warn("DevTools UI: $publisher could not export its session (${e.message}), exporting what was observed")
            }
        }
        return exportObserved(state, publisher)
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun exportFromDevice(publisher: String): String {
        val reply = askDevice(
            target = publisher,
            requestId = Uuid.random().toString(),
            service = DevToolsCommands.SERVICE_NAME,
            request = SESSION_EXPORT_REQUEST,
            timeoutMs = EXPORT_ANSWER_TIMEOUT_MS
        ) ?: error("The device did not answer")
        reply.error?.let { error(it) }
        val payload = (reply.result as? JsonPrimitive)?.contentOrNull ?: error("The device sent no session")
        return decodeSessionPayload(payload)
    }

    private fun exportObserved(state: DevToolsUiState, publisher: String): String {
        val now = currentTimeMillis()
        val info = state.connectedClients.firstOrNull { it.clientId == publisher }
        val crash = state.crashEvent?.takeIf { it.clientId == publisher }
        val export = GhostSessionExport(
            version = GhostSessionFormat.VERSION,
            sessionId = "$publisher-$now",
            exportedAt = now,
            clientInfo = ExportedClientInfo(
                clientId = publisher,
                clientName = info?.clientName ?: publisher,
                platform = info?.platform ?: "unknown"
            ),
            crash = crash?.info,
            crashes = listOfNotNull(crash?.info),
            session = SessionData(
                startTime = state.publisherSessionStart ?: state.actionStateHistory.firstOrNull()?.timestamp ?: 0L,
                endTime = now,
                initialStateJson = state.initialStateJson,
                actions = state.actionStateHistory,
                logicStartedEvents = LogicTrace.of(state.logicMethodEvents).started,
                logicCompletedEvents = LogicTrace.of(state.logicMethodEvents).completed,
                logicFailedEvents = LogicTrace.of(state.logicMethodEvents).failed,
                stateReads = state.stateReads,
                markers = state.markers,
                network = state.networkEvents.filter { it.clientId == publisher }.map { it.event },
                logs = state.deviceLogs
                    .filter { it.clientId == publisher }
                    .map { CapturedLog(it.level, it.category, it.message, it.timestampMs) }
            ),
            diagnosis = crash?.diagnosis,
            extensions = state.extensions
        )
        return json.encodeToString(export)
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun openLinkOnPublisher(path: String, link: String, params: Map<String, String>) {
        val state = storeAccessor.selectState<DevToolsUiState>().value
        val publisher = state.selectedPublisher?.takeIf { it != state.activeGhostId } ?: return
        val call = ServiceCall(Uuid.random().toString(), currentTimeMillis())
        storeAccessor.dispatch(DevToolsUiAction.LinkAttemptStarted(LinkAttempt(path, link, call)))
        storeAccessor.launch {
            askDevice(publisher, call.requestId, NAVIGATION_LINKS_SERVICE, OPEN_LINK_REQUEST, openLinkPayload(link, params))
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun requestAppLinks(form: AppLinksForm) {
        val state = storeAccessor.selectState<DevToolsUiState>().value
        val publisher = state.selectedPublisher?.takeIf { it != state.activeGhostId } ?: return
        val call = ServiceCall(Uuid.random().toString(), currentTimeMillis())
        storeAccessor.dispatch(DevToolsUiAction.AppLinksRequested(call))
        val payload = appLinksPayload(
            host = form.host.trim(),
            basePath = form.basePath.trim().ifEmpty { null },
            appleAppIds = form.appleAppIdList,
            androidPackage = form.androidPackage.trim().ifEmpty { null },
            androidCertFingerprints = form.fingerprintList,
            androidDynamicPaths = form.androidDynamicPaths,
            paths = form.selectedPaths
        )
        storeAccessor.launch {
            askDevice(publisher, call.requestId, NAVIGATION_LINKS_SERVICE, APP_LINKS_REQUEST, payload)
        }
    }

    private suspend fun askDevice(
        target: String,
        requestId: String,
        service: String,
        request: String,
        payload: JsonElement = JsonNull,
        timeoutMs: Long = LINK_ANSWER_TIMEOUT_MS
    ): DevToolsMessage.ServiceReply? {
        val reply = CompletableDeferred<DevToolsMessage.ServiceReply>()
        pendingRepliesLock.withLock { pendingReplies[requestId] = reply }
        try {
            if (!sendToServer(DevToolsMessage.ServiceRequest(target, requestId, service, request, payload))) {
                storeAccessor.dispatch(DevToolsUiAction.ServiceReplyReceived(requestId, null, SERVER_UNREACHABLE))
                return null
            }
            val answered = withTimeoutOrNull(timeoutMs) { reply.await() }
            if (answered == null) storeAccessor.dispatch(DevToolsUiAction.ServiceRequestUnanswered(requestId))
            return answered
        } finally {
            pendingRepliesLock.withLock { pendingReplies.remove(requestId) }
        }
    }

    private var ownRegistration: Long? = null

    suspend fun attachToPublisher(publisherId: String) {
        storeAccessor.dispatch(DevToolsUiAction.SelectPublisher(publisherId))
        assignRole(DEVTOOLS_UI_CLIENT_ID, ClientRole.ORCHESTRATOR, publisherId)
    }

    private suspend fun handleServerMessage(message: DevToolsMessage) {
        when (message) {
            is DevToolsMessage.ClientListUpdate -> {
                storeAccessor.dispatch(DevToolsUiAction.UpdateClientList(message.clients))

                // Auto-select devices based on their server-assigned roles
                val state = storeAccessor.selectState<DevToolsUiState>().value

                requestMissingGhostSessions(message.clients)
                val publisher = message.clients.find { it.role == ClientRole.PUBLISHER && !it.isGhost }
                val listener = message.clients.find {
                    it.role == ClientRole.LISTENER && it.clientId != DEVTOOLS_UI_CLIENT_ID
                }
                val self = message.clients.find { it.clientId == DEVTOOLS_UI_CLIENT_ID }

                if (self != null && self.connectedAt != ownRegistration) {
                    ownRegistration = self.connectedAt
                    val observing = self.role == ClientRole.ORCHESTRATOR &&
                        self.publisherClientId == publisher?.clientId
                    if (publisher != null && !observing) {
                        attachToPublisher(publisher.clientId)
                    }
                }
                if (publisher != null && state.activeGhostId == null && state.selectedPublisher != publisher.clientId) {
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

            is DevToolsMessage.SessionHistoryChunk -> {
                appendHistorySlice(message.clientId, message.history, isFirstSlice = message.chunkIndex == 0)
            }

            is DevToolsMessage.CrashReport -> {
                val crashEvent = CrashEventInfo(
                    clientId = message.clientId,
                    info = message.crash,
                    diagnosis = message.diagnosis
                )
                storeAccessor.dispatch(DevToolsUiAction.SetCrashEvent(crashEvent))
            }

            is DevToolsMessage.StateReadReport -> {
                storeAccessor.dispatch(DevToolsUiAction.AddStateRead(message.read))
            }

            is DevToolsMessage.PublisherChanged -> {
                ReaktivDebug.general("DevTools UI: Publisher changed - ${message.previousPublisherId} -> ${message.newPublisherId}: ${message.reason}")
                val newPublisherId = message.newPublisherId
                if (newPublisherId != null) {
                    attachToPublisher(newPublisherId)
                    ReaktivDebug.general("DevTools UI: Auto-assigned as orchestrator for $newPublisherId")
                } else {
                    storeAccessor.dispatch(DevToolsUiAction.SelectPublisher(null))
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
                        sendToServer(syncMessage)
                    }
                    ReaktivDebug.general("DevTools UI: Sent ghost state at position $position to new listener ${message.listenerId}")
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
                    storeAccessor.dispatch(DevToolsUiAction.ResetHistoryForSync)
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

            is DevToolsMessage.ServiceReply -> {
                pendingRepliesLock.withLock { pendingReplies.remove(message.requestId) }?.complete(message)
                storeAccessor.dispatch(DevToolsUiAction.ServiceReplyReceived(message.requestId, message.result, message.error))
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
                        content = message.content.orEmpty(),
                        offset = message.offset,
                        nextOffset = message.nextOffset,
                        totalBytes = message.totalBytes,
                        isLast = message.isLast,
                        available = message.content != null
                    )
                )
                if (message.content != null && !message.isLast && message.nextOffset > message.offset) {
                    fetchNetworkBody(
                        publisherClientId = message.clientId,
                        requestId = message.requestId,
                        part = message.part,
                        offset = message.nextOffset
                    )
                }
            }

            else -> {
                ReaktivDebug.general("DevTools UI: Unhandled message type: ${message::class.simpleName}")
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

private fun DevToolsUiState.updateCall(requestId: String, update: (ServiceCall) -> ServiceCall): DevToolsUiState =
    copy(
        linkAttempts = linkAttempts.map { attempt ->
            if (attempt.requestId == requestId) attempt.copy(call = update(attempt.call)) else attempt
        },
        appLinksCall = appLinksCall?.let { call -> if (call.requestId == requestId) update(call) else call }
    )

private const val SERVER_UNREACHABLE: String = "The DevTools server could not be reached"

private const val EXPORT_ANSWER_TIMEOUT_MS: Long = 60_000L
