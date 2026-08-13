package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.core.util.currentTimeMillis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberDispatcher
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.ui.components.ActionStream
import io.github.syrou.reaktiv.devtools.ui.components.ClientList
import io.github.syrou.reaktiv.devtools.ui.components.ConnectionStatus
import io.github.syrou.reaktiv.devtools.ui.components.FindingsPanel
import io.github.syrou.reaktiv.devtools.ui.components.GhostImportDialog
import io.github.syrou.reaktiv.devtools.ui.components.CommandPalette
import io.github.syrou.reaktiv.devtools.ui.components.HelpOverlay
import io.github.syrou.reaktiv.devtools.ui.components.MarkerDialog
import io.github.syrou.reaktiv.devtools.ui.components.NetworkDetailPanel
import io.github.syrou.reaktiv.devtools.ui.components.OnboardingPanel
import io.github.syrou.reaktiv.devtools.ui.components.PaletteCommand
import io.github.syrou.reaktiv.devtools.ui.components.SessionTimeline
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import io.github.syrou.reaktiv.devtools.ui.components.PerformancePanel
import io.github.syrou.reaktiv.devtools.ui.components.StateViewer
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main DevTools WASM application.
 *
 * Usage:
 * ```kotlin
 * fun main() {
 *     CanvasBasedWindow("Reaktiv DevTools") {
 *         DevToolsApp(serverUrl = "ws://localhost:8080/ws")
 *     }
 * }
 * ```
 */
@Composable
internal fun DevToolsApp(store: Store, serverUrl: String = "ws://localhost:8080/ws") {
    println("DevToolsApp: Starting with serverUrl=$serverUrl")
    val storePrepared by store.initialized.collectAsState()
    println("storePrepared: $storePrepared")
    val connection = remember(storePrepared) {
        if(!storePrepared) return@remember null
        println("DevToolsApp: Creating connection")
        DevToolsConnection(serverUrl)
    }

    LaunchedEffect(storePrepared) {
        if(!storePrepared) return@LaunchedEffect
        try {
            println("DevToolsApp: LaunchedEffect starting")
            val logic = DevToolsUiModule.selectLogicTyped(store)
            println("DevToolsApp: Logic retrieved")
            connection?.let {
                logic.setConnection(it)
            }

            println("DevToolsApp: Connection set on logic")
            connection?.connect(
                clientId = "devtools-ui",
                clientName = "DevTools UI",
                platform = "WASM Browser"
            )
            println("DevToolsApp: Connection.connect() completed")
        } catch (e: Exception) {
            println("DevToolsApp: Error in LaunchedEffect - ${e.message}")
            e.printStackTrace()
        }
    }

    println("DevToolsApp: Inside StoreProvider")

    if(storePrepared) {
        DevToolsContent(store, serverUrl)
    }
}

@Composable
private fun DevToolsContent(store: Store, serverUrl: String) {
    val state by composeState<DevToolsUiState>()
    val dispatch = rememberDispatcher()
    val scope = rememberCoroutineScope()

    var showPalette by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showMarkerDialog by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var autoPlaying by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }
    var splitFraction by remember { mutableStateOf(0.6f) }
    var contentWidthPx by remember { mutableStateOf(0f) }

    fun seek(index: Int) {
        if (state.actionStateHistory.isEmpty()) return
        if (state.autoSelectLatest) {
            dispatch(DevToolsUiAction.ToggleAutoSelectLatest)
        }
        val clamped = index.coerceIn(0, state.actionStateHistory.size - 1)
        if (state.timeTravelEnabled) {
            dispatch(DevToolsUiAction.SetTimeTravelPosition(clamped))
        } else {
            dispatch(DevToolsUiAction.SelectAction(clamped))
        }
    }

    fun dropMarker() {
        if (state.selectedPublisher != null && state.pinnedTimeMs != null) {
            showMarkerDialog = true
        }
    }

    fun markerTargetDescription(): String {
        val pinned = state.pinnedTimeMs ?: return ""
        val sessionStart = state.actionStateHistory.firstOrNull()?.timestamp ?: pinned
        val into = (pinned - sessionStart).coerceAtLeast(0L).milliseconds
        return "At pinned time $into into the session"
    }

    fun confirmMarker(label: String, note: String) {
        val publisher = state.selectedPublisher ?: return
        val pinned = state.pinnedTimeMs ?: return
        val index = state.actionStateHistory.withIndex().minByOrNull { (_, event) ->
            val distance = event.timestamp - pinned
            if (distance < 0) -distance else distance
        }?.index ?: -1
        scope.launch {
            val logic = DevToolsUiModule.selectLogicTyped(store)
            logic.addMarkerOnPublisher(
                publisherClientId = publisher,
                label = label,
                note = note,
                timestampMs = pinned,
                afterActionIndex = index
            )
        }
        dispatch(DevToolsUiAction.SetPinnedTime(null))
    }

    fun exportSession() {
        val publisher = state.connectedClients.find { it.clientId == state.selectedPublisher }
        val sessionStart = state.publisherSessionStart
        if (publisher != null && sessionStart != null) {
            scope.launch {
                val logic = DevToolsUiModule.selectLogicTyped(store)
                val json = logic.exportSessionAsGhost(
                    clientInfo = publisher,
                    actionHistory = state.actionStateHistory,
                    logicEvents = state.logicMethodEvents,
                    sessionStartTime = sessionStart,
                    initialStateJson = state.initialStateJson,
                    crashEvent = state.crashEvent,
                    stateReads = state.stateReads,
                    markers = state.markers
                )
                downloadJson(json, "session_" + publisher.clientId + ".json")
            }
        }
    }

    fun toggleTimeTravel() {
        if (state.actionStateHistory.isNotEmpty()) {
            autoPlaying = false
            dispatch(DevToolsUiAction.ToggleTimeTravel)
        }
    }

    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
    LaunchedEffect(state.timeTravelEnabled) {
        if (!state.timeTravelEnabled) autoPlaying = false
    }

    LaunchedEffect(state.timeTravelEnabled, state.timeTravelPosition, state.selectedPublisher) {
        val publisher = state.selectedPublisher
        if (state.timeTravelEnabled && state.timeTravelPosition < state.actionStateHistory.size && publisher != null) {
            val logic = DevToolsUiModule.selectLogicTyped(store)
            logic.sendTimeTravelSync(
                actionHistory = state.actionStateHistory,
                initialStateJson = state.initialStateJson,
                position = state.timeTravelPosition,
                publisherClientId = publisher
            )
        }
    }

    LaunchedEffect(state.actionStateHistory.size, state.autoSelectLatest, state.excludedActionTypes, state.timeTravelEnabled) {
        if (state.autoSelectLatest && !state.timeTravelEnabled && state.actionStateHistory.isNotEmpty()) {
            // Find the latest non-excluded action
            val latestNonExcludedIndex = state.actionStateHistory.indexOfLast { event ->
                !state.excludedActionTypes.contains(event.actionType)
            }

            if (latestNonExcludedIndex >= 0 && state.selectedActionIndex != latestNonExcludedIndex) {
                dispatch(DevToolsUiAction.SelectAction(latestNonExcludedIndex))
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K) {
                        showPalette = !showPalette
                        return@onKeyEvent true
                    }
                    if (event.key == Key.Escape) {
                        return@onKeyEvent when {
                            showHelp -> {
                                showHelp = false
                                true
                            }
                            showPalette -> {
                                showPalette = false
                                true
                            }
                            state.devicePanelExpanded -> {
                                dispatch(DevToolsUiAction.ToggleDevicePanel)
                                true
                            }
                            else -> false
                        }
                    }
                    if (searchFocused || showPalette || showMarkerDialog || state.showImportGhostDialog) {
                        return@onKeyEvent false
                    }
                    when (event.key) {
                        Key.Slash -> {
                            if (event.isShiftPressed) showHelp = true else searchFocusRequester.requestFocus()
                            true
                        }
                        Key.T -> {
                            toggleTimeTravel()
                            true
                        }
                        Key.Spacebar -> {
                            if (state.timeTravelEnabled) autoPlaying = !autoPlaying
                            true
                        }
                        Key.J, Key.DirectionLeft -> {
                            seek((state.selectedActionIndex ?: state.actionStateHistory.size) - 1)
                            true
                        }
                        Key.K, Key.DirectionRight -> {
                            seek((state.selectedActionIndex ?: -1) + 1)
                            true
                        }
                        Key.One -> {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.STATE))
                            true
                        }
                        Key.Two -> {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.PERFORMANCE))
                            true
                        }
                        Key.Three -> {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.FINDINGS))
                            true
                        }
                        Key.Four -> {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.NETWORK))
                            true
                        }
                        Key.M -> {
                            dropMarker()
                            true
                        }
                        Key.G -> {
                            dispatch(DevToolsUiAction.ShowImportGhostDialog)
                            true
                        }
                        Key.E -> {
                            exportSession()
                            true
                        }
                        Key.D -> {
                            dispatch(DevToolsUiAction.ToggleDevicePanel)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            // Main content
            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionStatus(
                    connectionState = state.connectionState,
                    deviceCount = state.connectedClients.size,
                    isDevicePanelExpanded = state.devicePanelExpanded,
                    onToggleDevicePanel = { dispatch(DevToolsUiAction.ToggleDevicePanel) },
                    onReconnect = {
                        scope.launch {
                            DevToolsUiModule.selectLogicTyped(store)
                                .reconnect("devtools-ui", "DevTools UI", "WASM Browser")
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .onSizeChanged { contentWidthPx = it.width.toFloat() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(splitFraction)
                    ) {
                        if (state.actionStateHistory.isNotEmpty()) {
                            SessionTimeline(
                                dataRevision = state.dataRevision,
                                actions = state.actionStateHistory,
                                logicMethodEvents = state.logicMethodEvents,
                                markers = state.markers,
                                crash = state.crashEvent?.info,
                                networkEvents = state.networkEvents,
                                selectedActionIndex = state.selectedActionIndex,
                                selectedSpanCallId = state.selectedLogicMethodCallId,
                                selectedNetworkRequestId = state.selectedNetworkRequestId,
                                pinnedTimeMs = state.pinnedTimeMs,
                                onPinTime = { dispatch(DevToolsUiAction.SetPinnedTime(it)) },
                                onSeek = { index -> seek(index) },
                                onSelectSpan = { callId ->
                                    dispatch(DevToolsUiAction.SelectLogicMethodEvent(callId))
                                },
                                onSelectNetwork = { requestId ->
                                    dispatch(DevToolsUiAction.SelectNetworkRequest(requestId))
                                },
                                canDropMarker = state.selectedPublisher != null && state.pinnedTimeMs != null,
                                onDropMarker = { dropMarker() }
                            )
                            Divider(modifier = Modifier.fillMaxWidth().height(1.dp))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                        if (state.actionStateHistory.isEmpty() && state.logicMethodEvents.isEmpty()) {
                            OnboardingPanel(
                                serverUrl = serverUrl,
                                hasClients = state.connectedClients.any {
                                    !it.isGhost && it.clientId != "devtools-ui"
                                },
                                onImportGhost = { dispatch(DevToolsUiAction.ShowImportGhostDialog) }
                            )
                        } else {
                        ActionStream(
                            dataRevision = state.dataRevision,
                            actions = state.actionStateHistory,
                            logicMethodEvents = state.logicMethodEvents,
                            crashEvent = state.crashEvent,
                            markers = state.markers,
                            deviceLogs = state.deviceLogs,
                            networkEvents = state.networkEvents,
                            selectedIndex = state.selectedActionIndex,
                            selectedLogicMethodCallId = state.selectedLogicMethodCallId,
                            selectedNetworkRequestId = state.selectedNetworkRequestId,
                            crashSelected = state.crashSelected,
                            autoSelectLatest = state.autoSelectLatest,
                            excludedActionTypes = state.excludedActionTypes,
                            excludedLogicMethods = state.excludedLogicMethods,
                            callIdToMethodIdentifier = state.callIdToMethodIdentifier,
                            timeTravelEnabled = state.timeTravelEnabled,
                            showActions = state.showActions,
                            showLogicMethods = state.showLogicMethods,
                            showLogs = state.showLogs,
                            showNetwork = state.showNetwork,
                            searchQuery = state.searchQuery,
                            onSearchQueryChange = { dispatch(DevToolsUiAction.SetSearchQuery(it)) },
                            searchFocusRequester = searchFocusRequester,
                            onSearchFocusChanged = { searchFocused = it },
                            onSelectAction = { dispatch(DevToolsUiAction.SelectAction(it)) },
                            onSelectLogicMethod = { dispatch(DevToolsUiAction.SelectLogicMethodEvent(it)) },
                            onSelectNetworkRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) },
                            onSelectCrash = { dispatch(DevToolsUiAction.SelectCrash(it)) },
                            onMarkerClick = { marker ->
                                if (marker.afterActionIndex >= 0) seek(marker.afterActionIndex)
                            },
                            onToggleAutoSelect = { dispatch(DevToolsUiAction.ToggleAutoSelectLatest) },
                            onAddExclusion = { dispatch(DevToolsUiAction.AddActionExclusion(it)) },
                            onRemoveExclusion = { dispatch(DevToolsUiAction.RemoveActionExclusion(it)) },
                            onSetExclusions = { dispatch(DevToolsUiAction.SetActionExclusions(it)) },
                            onAddLogicMethodExclusion = { dispatch(DevToolsUiAction.AddLogicMethodExclusion(it)) },
                            onRemoveLogicMethodExclusion = { dispatch(DevToolsUiAction.RemoveLogicMethodExclusion(it)) },
                            onToggleTimeTravel = { toggleTimeTravel() },
                            onToggleShowActions = { dispatch(DevToolsUiAction.ToggleShowActions) },
                            onToggleShowLogicMethods = { dispatch(DevToolsUiAction.ToggleShowLogicMethods) },
                            onToggleShowLogs = { dispatch(DevToolsUiAction.ToggleShowLogs) },
                            onToggleShowNetwork = { dispatch(DevToolsUiAction.ToggleShowNetwork) },
                            onClear = { dispatch(DevToolsUiAction.ClearHistory) }
                        )

                        if (state.timeTravelEnabled && state.actionStateHistory.isNotEmpty()) {
                            TimeTravelBar(
                                currentPosition = state.timeTravelPosition,
                                totalEvents = state.actionStateHistory.size,
                                isGhostMode = state.activeGhostId != null,
                                autoPlaying = autoPlaying,
                                onAutoPlayingChange = { autoPlaying = it },
                                onPositionChange = { dispatch(DevToolsUiAction.SetTimeTravelPosition(it)) },
                                onClose = {
                                    autoPlaying = false
                                    dispatch(DevToolsUiAction.ToggleTimeTravel)
                                    if (state.activeGhostId != null) {
                                        dispatch(DevToolsUiAction.SetActiveGhostId(null))
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                        }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(6.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (contentWidthPx > 0f) {
                                        splitFraction = (splitFraction + dragAmount.x / contentWidthPx)
                                            .coerceIn(0.3f, 0.8f)
                                    }
                                }
                            }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f - splitFraction)
                    ) {
                        TabRow(selectedTabIndex = state.rightPanelTab.ordinal) {
                            Tab(
                                selected = state.rightPanelTab == RightPanelTab.STATE,
                                onClick = { dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.STATE)) },
                                text = { Text("State") }
                            )
                            Tab(
                                selected = state.rightPanelTab == RightPanelTab.PERFORMANCE,
                                onClick = { dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.PERFORMANCE)) },
                                text = { Text("Performance") }
                            )
                            Tab(
                                selected = state.rightPanelTab == RightPanelTab.FINDINGS,
                                onClick = { dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.FINDINGS)) },
                                text = { Text("Findings") }
                            )
                            Tab(
                                selected = state.rightPanelTab == RightPanelTab.NETWORK,
                                onClick = { dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.NETWORK)) },
                                text = { Text("Network") }
                            )
                        }
                        if (state.rightPanelTab == RightPanelTab.PERFORMANCE) {
                            PerformancePanel(
                                dataRevision = state.dataRevision,
                                logicMethodEvents = state.logicMethodEvents,
                                actionStateHistory = state.actionStateHistory,
                                initialStateJson = state.initialStateJson
                            )
                        } else if (state.rightPanelTab == RightPanelTab.NETWORK) {
                            NetworkDetailPanel(
                                networkEvents = state.networkEvents,
                                selectedRequestId = state.selectedNetworkRequestId,
                                bodies = state.networkBodies,
                                filter = state.networkFilter,
                                showStats = state.showNetworkStats,
                                onFilterChange = { dispatch(DevToolsUiAction.SetNetworkFilter(it)) },
                                onToggleStats = { dispatch(DevToolsUiAction.ToggleNetworkStats) },
                                onExportHar = {
                                    downloadJson(
                                        harJson.encodeToString(
                                            kotlinx.serialization.json.JsonObject.serializer(),
                                            state.networkEvents.toHar()
                                        ),
                                        "reaktiv-network.har"
                                    )
                                },
                                onSelectRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) },
                                onFetchBody = { requestId, part ->
                                    val owner = state.networkEvents
                                        .lastOrNull { it.event.id == requestId }
                                        ?.clientId
                                    if (owner != null) {
                                        scope.launch {
                                            val logic = DevToolsUiModule.selectLogicTyped(store)
                                            logic.fetchNetworkBody(owner, requestId, part)
                                        }
                                    }
                                }
                            )
                        } else if (state.rightPanelTab == RightPanelTab.FINDINGS) {
                            FindingsPanel(
                                dataRevision = state.dataRevision,
                                logicMethodEvents = state.logicMethodEvents,
                                actionStateHistory = state.actionStateHistory,
                                initialStateJson = state.initialStateJson,
                                stateReads = state.stateReads,
                                onSeekTimestamp = { ts ->
                                    val index = state.actionStateHistory.withIndex().minByOrNull { (_, event) ->
                                        val distance = event.timestamp - ts
                                        if (distance < 0) -distance else distance
                                    }?.index
                                    if (index != null) seek(index)
                                }
                            )
                        } else {
                            StateViewer(
                                dataRevision = state.dataRevision,
                                actionStateHistory = state.actionStateHistory,
                                selectedActionIndex = state.selectedActionIndex,
                                logicMethodEvents = state.logicMethodEvents,
                                selectedLogicMethodCallId = state.selectedLogicMethodCallId,
                                crashEvent = state.crashEvent,
                                crashSelected = state.crashSelected,
                                showAsDiff = state.showStateAsDiff,
                                excludedActionTypes = state.excludedActionTypes,
                                initialStateJson = state.initialStateJson,
                                stateReads = state.stateReads,
                                onToggleDiffMode = { dispatch(DevToolsUiAction.ToggleStateViewMode) },
                                onClear = { dispatch(DevToolsUiAction.ClearHistory) }
                            )
                        }
                    }
                }
            }

            // Device list overlay
            if (state.devicePanelExpanded) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    ClientList(
                        clients = state.connectedClients,
                        selectedPublisher = state.selectedPublisher,
                        selectedListener = state.selectedListener,
                        clientStatuses = state.clientStatuses,
                        canExportSession = state.canExportSession,
                        onPublisherSelected = { clientId ->
                            dispatch(DevToolsUiAction.SelectPublisher(clientId))
                            if (clientId != null && clientId == state.selectedListener) {
                                dispatch(DevToolsUiAction.SelectListener(null))
                            }
                            clientId?.let {
                                dispatch(DevToolsUiAction.SetPublisherSessionStart(
                                    currentTimeMillis()
                                ))
                                dispatch(DevToolsUiAction.SetCanExportSession(true))
                                scope.launch {
                                    val logic = DevToolsUiModule.selectLogicTyped(store)
                                    logic.assignRole("devtools-ui", ClientRole.ORCHESTRATOR, it)
                                    logic.assignRole(it, ClientRole.PUBLISHER)
                                }
                            } ?: run {
                                dispatch(DevToolsUiAction.SetPublisherSessionStart(null))
                                dispatch(DevToolsUiAction.SetCanExportSession(false))
                            }
                        },
                        onListenerSelected = { clientId ->
                            dispatch(DevToolsUiAction.SelectListener(clientId))
                            if (clientId != null && clientId == state.selectedPublisher) {
                                dispatch(DevToolsUiAction.SelectPublisher(null))
                                dispatch(DevToolsUiAction.SetPublisherSessionStart(null))
                                dispatch(DevToolsUiAction.SetCanExportSession(false))
                            }
                        },
                        onAssignRole = { listener, publisher ->
                            if (listener == publisher) return@ClientList
                            scope.launch {
                                val logic = DevToolsUiModule.selectLogicTyped(store)
                                logic.assignRole(publisher, ClientRole.PUBLISHER)
                                logic.assignRole(listener, ClientRole.LISTENER, publisher)
                            }
                        },
                        onRemoveGhost = { ghostId ->
                            scope.launch {
                                val logic = DevToolsUiModule.selectLogicTyped(store)
                                logic.removeGhostDevice(ghostId)
                            }
                        },
                        onImportGhost = { dispatch(DevToolsUiAction.ShowImportGhostDialog) },
                        onExportSession = { exportSession() }
                    )
                }
            }

            // Import ghost dialog
            if (state.showImportGhostDialog) {
                GhostImportDialog(
                    onImport = { json ->
                        scope.launch {
                            val logic = DevToolsUiModule.selectLogicTyped(store)
                            logic.importGhostSession(json)
                        }
                    },
                    onDismiss = { dispatch(DevToolsUiAction.HideImportGhostDialog) }
                )
            }

            if (showPalette) {
                CommandPalette(
                    commands = listOf(
                        PaletteCommand("Toggle time travel", "t", state.actionStateHistory.isNotEmpty()) {
                            toggleTimeTravel()
                        },
                        PaletteCommand("Play or pause playback", "space", state.timeTravelEnabled) {
                            autoPlaying = !autoPlaying
                        },
                        PaletteCommand("Jump to session start", null, state.timeTravelEnabled) { seek(0) },
                        PaletteCommand("Jump to session end", null, state.timeTravelEnabled) {
                            seek(state.actionStateHistory.size - 1)
                        },
                        PaletteCommand("Show state tab", "1") {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.STATE))
                        },
                        PaletteCommand("Show performance tab", "2") {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.PERFORMANCE))
                        },
                        PaletteCommand("Show findings tab", "3") {
                            dispatch(DevToolsUiAction.SetRightPanelTab(RightPanelTab.FINDINGS))
                        },
                        PaletteCommand(
                            "Drop marker at pinned time", "m",
                            state.selectedPublisher != null && state.pinnedTimeMs != null
                        ) { dropMarker() },
                        PaletteCommand(
                            "Export session", "e",
                            state.canExportSession && state.selectedPublisher != null
                        ) { exportSession() },
                        PaletteCommand("Import ghost session", "g") {
                            dispatch(DevToolsUiAction.ShowImportGhostDialog)
                        },
                        PaletteCommand("Toggle device panel", "d") {
                            dispatch(DevToolsUiAction.ToggleDevicePanel)
                        },
                        PaletteCommand(
                            "Clear history", null,
                            state.actionStateHistory.isNotEmpty() || state.logicMethodEvents.isNotEmpty()
                        ) { dispatch(DevToolsUiAction.ClearHistory) },
                        PaletteCommand("Keyboard shortcuts", "?") { showHelp = true }
                    ),
                    onDismiss = { showPalette = false }
                )
            }

            if (showHelp) {
                HelpOverlay { showHelp = false }
            }

            if (showMarkerDialog) {
                MarkerDialog(
                    targetDescription = markerTargetDescription(),
                    onConfirm = { label, note -> confirmMarker(label, note) },
                    onDismiss = { showMarkerDialog = false }
                )
            }
        }
    }
}

/**
 * Time travel playback bar with controls for scrubbing through state history.
 * Works for both regular time travel and ghost session playback.
 */
@Composable
private fun TimeTravelBar(
    currentPosition: Int,
    totalEvents: Int,
    isGhostMode: Boolean,
    autoPlaying: Boolean,
    onAutoPlayingChange: (Boolean) -> Unit,
    onPositionChange: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var playbackSpeed by remember { mutableStateOf(1f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    LaunchedEffect(autoPlaying, currentPosition, playbackSpeed) {
        if (autoPlaying && currentPosition < totalEvents - 1) {
            delay((1000 / playbackSpeed).toLong())
            onPositionChange(currentPosition + 1)
        } else if (currentPosition >= totalEvents - 1) {
            onAutoPlayingChange(false)
        }
    }

    val containerColor = if (isGhostMode) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    val onContainerColor = if (isGhostMode) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGhostMode) "Ghost Playback" else "Time Travel",
                        style = MaterialTheme.typography.titleMedium,
                        color = onContainerColor
                    )
                    Text(
                        text = "${currentPosition + 1} / $totalEvents",
                        style = MaterialTheme.typography.labelMedium,
                        color = onContainerColor.copy(alpha = 0.7f)
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = onContainerColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (totalEvents > 1) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { onPositionChange(it.toInt()) },
                    valueRange = 0f..(totalEvents - 1).toFloat(),
                    steps = if (totalEvents > 2) totalEvents - 2 else 0,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onPositionChange(0) },
                    enabled = currentPosition > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Go to start",
                        tint = onContainerColor
                    )
                }

                IconButton(
                    onClick = { onPositionChange((currentPosition - 10).coerceAtLeast(0)) },
                    enabled = currentPosition > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Rewind 10",
                        tint = onContainerColor
                    )
                }

                IconButton(onClick = { onAutoPlayingChange(!autoPlaying) }) {
                    Icon(
                        imageVector = if (autoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (autoPlaying) "Pause" else "Play",
                        tint = onContainerColor
                    )
                }

                IconButton(
                    onClick = { onPositionChange((currentPosition + 10).coerceAtMost(totalEvents - 1)) },
                    enabled = currentPosition < totalEvents - 1
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Forward 10",
                        tint = onContainerColor
                    )
                }

                IconButton(
                    onClick = { onPositionChange(totalEvents - 1) },
                    enabled = currentPosition < totalEvents - 1
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Go to end",
                        tint = onContainerColor
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                TextButton(onClick = { showSpeedMenu = true }) {
                    Text(
                        text = "${playbackSpeed}x",
                        color = onContainerColor
                    )

                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        listOf(0.5f, 1f, 2f, 5f, 10f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}x") },
                                onClick = {
                                    playbackSpeed = speed
                                    showSpeedMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Downloads a JSON string as a file in the browser.
 */
private fun downloadJson(json: String, filename: String) {
    js("""
        (function(content, name) {
            var blob = new Blob([content], { type: 'application/json' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = name;
            a.click();
            URL.revokeObjectURL(url);
        })(json, filename)
    """)
}

private val harJson: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { prettyPrint = true }
