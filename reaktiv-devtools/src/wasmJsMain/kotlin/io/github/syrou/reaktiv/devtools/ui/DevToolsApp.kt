package io.github.syrou.reaktiv.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberDispatcher
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.Finding
import io.github.syrou.reaktiv.devtools.protocol.FindingSeverity
import io.github.syrou.reaktiv.devtools.ui.components.ActionStream
import io.github.syrou.reaktiv.devtools.ui.components.ClientList
import io.github.syrou.reaktiv.devtools.ui.components.CommandPalette
import io.github.syrou.reaktiv.devtools.ui.components.ConnectionStatus
import io.github.syrou.reaktiv.devtools.ui.components.DestinationRail
import io.github.syrou.reaktiv.devtools.ui.components.EmptyState
import io.github.syrou.reaktiv.devtools.ui.components.FindingsPanel
import io.github.syrou.reaktiv.devtools.ui.components.GhostImportDialog
import io.github.syrou.reaktiv.devtools.ui.components.HelpOverlay
import io.github.syrou.reaktiv.devtools.ui.components.LogsPanel
import io.github.syrou.reaktiv.devtools.ui.components.MarkerDialog
import io.github.syrou.reaktiv.devtools.ui.components.NavigationPanel
import io.github.syrou.reaktiv.devtools.ui.components.NetworkOverviewList
import io.github.syrou.reaktiv.devtools.ui.components.NetworkRequestDetail
import io.github.syrou.reaktiv.devtools.ui.components.OnboardingPanel
import io.github.syrou.reaktiv.devtools.ui.components.PaletteCommand
import io.github.syrou.reaktiv.devtools.ui.components.PerformancePanel
import io.github.syrou.reaktiv.devtools.ui.components.SearchField
import io.github.syrou.reaktiv.devtools.ui.components.SessionTimeline
import io.github.syrou.reaktiv.devtools.ui.components.StateViewer
import io.github.syrou.reaktiv.devtools.ui.components.StateViewerContent
import io.github.syrou.reaktiv.devtools.ui.components.rememberFindings
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val INSPECTOR_MIN_WIDTH = 380.dp

private val DESTINATION_KEYS: List<Key> = listOf(
    Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine
)

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
    val storePrepared by store.initialized.collectAsState()
    val connection = remember(storePrepared) {
        if (!storePrepared) return@remember null
        ReaktivDebug.general("DevTools UI connecting to $serverUrl")
        DevToolsConnection(serverUrl)
    }

    LaunchedEffect(storePrepared) {
        if (!storePrepared) return@LaunchedEffect
        try {
            val logic = DevToolsUiModule.selectLogicTyped(store)
            connection?.let {
                logic.setConnection(it)
            }
            connection?.connect(
                clientId = DEVTOOLS_UI_CLIENT_ID,
                clientName = "DevTools UI",
                platform = "WASM Browser"
            )
            ReaktivDebug.general("DevTools UI connected to $serverUrl")
        } catch (e: Exception) {
            ReaktivDebug.error("DevTools UI failed to connect to $serverUrl", e)
        }
    }

    if (storePrepared) {
        DevToolsContent(store, serverUrl)
    }
}

@Composable
private fun DevToolsContent(store: Store, serverUrl: String) {
    val state by composeState<DevToolsUiState>()
    val dispatch = rememberDispatcher()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var searchFocused by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }
    var workbenchWidthPx by remember { mutableStateOf(0f) }

    fun seek(index: Int) {
        if (state.actionStateHistory.isEmpty()) return
        val clamped = index.coerceIn(0, state.actionStateHistory.size - 1)
        if (state.timeTravelEnabled) {
            dispatch(DevToolsUiAction.SetTimeTravelPosition(clamped))
        } else {
            dispatch(DevToolsUiAction.SelectAction(clamped))
        }
    }

    fun showOverlay(overlay: Overlay) = dispatch(DevToolsUiAction.SetOverlay(overlay))

    fun closeOverlay() = dispatch(DevToolsUiAction.SetOverlay(Overlay.None))

    fun showDestination(destination: DevToolsDestination) =
        dispatch(DevToolsUiAction.SetDestination(destination))

    fun dropMarker() {
        if (state.selectedPublisher != null && state.pinnedTimeMs != null) {
            showOverlay(Overlay.Marker)
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
        closeOverlay()
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
                    markers = state.markers,
                    network = state.networkEvents
                        .filter { it.clientId == publisher.clientId }
                        .map { it.event }
                )
                downloadSession(
                    json = json,
                    gzName = "session_" + publisher.clientId + ".json.gz",
                    plainName = "session_" + publisher.clientId + ".json"
                )
            }
        }
    }

    fun toggleTimeTravel() {
        if (state.actionStateHistory.isNotEmpty()) {
            dispatch(DevToolsUiAction.ToggleTimeTravel)
        }
    }

    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }

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

    val hasCapturedData = state.actionStateHistory.isNotEmpty() || state.logicMethodEvents.isNotEmpty()
    val liveDevices = state.connectedClients.filter { !it.isGhost && it.clientId != DEVTOOLS_UI_CLIENT_ID }
    val publisherName = state.connectedClients.find { it.clientId == state.selectedPublisher }?.clientName

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K) {
                        if (state.overlay == Overlay.Palette) closeOverlay() else showOverlay(Overlay.Palette)
                        return@onKeyEvent true
                    }
                    if (event.key == Key.Escape) {
                        return@onKeyEvent if (state.overlay != Overlay.None) {
                            closeOverlay()
                            true
                        } else {
                            false
                        }
                    }
                    if (searchFocused || state.overlay != Overlay.None) {
                        return@onKeyEvent false
                    }
                    val destinationIndex = DESTINATION_KEYS.indexOf(event.key)
                    if (destinationIndex in DevToolsDestination.entries.indices) {
                        showDestination(DevToolsDestination.entries[destinationIndex])
                        return@onKeyEvent true
                    }
                    when (event.key) {
                        Key.Slash -> {
                            if (event.isShiftPressed) showOverlay(Overlay.Help) else searchFocusRequester.requestFocus()
                            true
                        }
                        Key.T -> {
                            toggleTimeTravel()
                            true
                        }
                        Key.Spacebar -> {
                            dispatch(DevToolsUiAction.SetAutoPlaying(!state.autoPlaying))
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
                        Key.M -> {
                            dropMarker()
                            true
                        }
                        Key.G -> {
                            showOverlay(Overlay.ImportGhost)
                            true
                        }
                        Key.E -> {
                            exportSession()
                            true
                        }
                        Key.D -> {
                            showDestination(DevToolsDestination.DEVICES)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionStatus(
                    connectionState = state.connectionState,
                    publisherName = publisherName,
                    onReconnect = {
                        scope.launch {
                            DevToolsUiModule.selectLogicTyped(store)
                                .reconnect(DEVTOOLS_UI_CLIENT_ID, "DevTools UI", "WASM Browser")
                        }
                    }
                )
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
                        onDropMarker = { dropMarker() },
                        timeTravelEnabled = state.timeTravelEnabled,
                        autoPlaying = state.autoPlaying,
                        playbackSpeed = state.playbackSpeed,
                        onAutoPlayingChange = { dispatch(DevToolsUiAction.SetAutoPlaying(it)) },
                        onPlaybackSpeedChange = { dispatch(DevToolsUiAction.SetPlaybackSpeed(it)) },
                        compact = state.destination != DevToolsDestination.PERFORMANCE
                    )
                    Divider(modifier = Modifier.fillMaxWidth().height(1.dp))
                }
                val findings = rememberFindings(
                    dataRevision = state.dataRevision,
                    logicMethodEvents = state.logicMethodEvents,
                    actionStateHistory = state.actionStateHistory,
                    initialStateJson = state.initialStateJson,
                    stateReads = state.stateReads,
                    networkEvents = state.networkEvents.map { it.event }
                )
                Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                    DestinationRail(
                        current = state.destination,
                        findingsCount = findings.size,
                        hasCriticalFinding = findings.any { it.severity == FindingSeverity.CRITICAL },
                        deviceCount = liveDevices.size,
                        onSelect = { showDestination(it) }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    if (state.destination.showsCapturedData && !hasCapturedData) {
                        Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                            OnboardingPanel(
                                serverUrl = serverUrl,
                                hasClients = liveDevices.isNotEmpty(),
                                onImportGhost = { showOverlay(Overlay.ImportGhost) }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .onSizeChanged { workbenchWidthPx = it.width.toFloat() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(state.splitFraction)
                            ) {
                                ListPane(
                                    store = store,
                                    state = state,
                                    findings = findings,
                                    dispatch = dispatch,
                                    scope = scope,
                                    onSeek = { seek(it) },
                                    onExportSession = { exportSession() },
                                    searchField = {
                                        SearchField(
                                            value = state.searchQuery,
                                            placeholder = searchPlaceholder(state.destination),
                                            focusRequester = searchFocusRequester,
                                            onFocusChanged = { searchFocused = it },
                                            onValueChange = { dispatch(DevToolsUiAction.SetSearchQuery(it)) },
                                            onEscape = {
                                                dispatch(DevToolsUiAction.SetSearchQuery(""))
                                                focusManager.clearFocus()
                                            }
                                        )
                                    }
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(6.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            if (workbenchWidthPx > 0f) {
                                                dispatch(
                                                    DevToolsUiAction.SetSplitFraction(
                                                        state.splitFraction + dragAmount.x / workbenchWidthPx
                                                    )
                                                )
                                            }
                                        }
                                    }
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f - state.splitFraction)
                                    .widthIn(min = INSPECTOR_MIN_WIDTH)
                            ) {
                                Inspector(
                                    store = store,
                                    state = state,
                                    dispatch = dispatch,
                                    scope = scope
                                )
                            }
                        }
                    }
                }
            }
            when (state.overlay) {
                Overlay.None -> Unit
                Overlay.ImportGhost -> GhostImportDialog(
                    onImport = { json ->
                        scope.launch {
                            val logic = DevToolsUiModule.selectLogicTyped(store)
                            logic.importGhostSession(json)
                        }
                    },
                    onDismiss = { closeOverlay() }
                )
                Overlay.Palette -> CommandPalette(
                    commands = paletteCommands(
                        state = state,
                        onDestination = { showDestination(it) },
                        onToggleTimeTravel = { toggleTimeTravel() },
                        onTogglePlayback = { dispatch(DevToolsUiAction.SetAutoPlaying(!state.autoPlaying)) },
                        onSeek = { seek(it) },
                        onDropMarker = { dropMarker() },
                        onExportSession = { exportSession() },
                        onImportGhost = { showOverlay(Overlay.ImportGhost) },
                        onClearHistory = { dispatch(DevToolsUiAction.ClearHistory) },
                        onHelp = { showOverlay(Overlay.Help) }
                    ),
                    onDismiss = { closeOverlay() }
                )
                Overlay.Help -> HelpOverlay { closeOverlay() }
                Overlay.Marker -> MarkerDialog(
                    targetDescription = markerTargetDescription(),
                    onConfirm = { label, note -> confirmMarker(label, note) },
                    onDismiss = { closeOverlay() }
                )
            }
        }
    }
}

private fun searchPlaceholder(destination: DevToolsDestination): String = when (destination) {
    DevToolsDestination.STREAM -> "Search events"
    DevToolsDestination.PERFORMANCE -> "Search methods"
    DevToolsDestination.NETWORK -> "Search requests"
    DevToolsDestination.LOGS -> "Search logs"
    else -> "Search"
}

private fun paletteCommands(
    state: DevToolsUiState,
    onDestination: (DevToolsDestination) -> Unit,
    onToggleTimeTravel: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Int) -> Unit,
    onDropMarker: () -> Unit,
    onExportSession: () -> Unit,
    onImportGhost: () -> Unit,
    onClearHistory: () -> Unit,
    onHelp: () -> Unit
): List<PaletteCommand> {
    val hasHistory = state.actionStateHistory.isNotEmpty()
    val destinations = DevToolsDestination.entries.mapIndexed { index, destination ->
        PaletteCommand("Show ${destination.label.lowercase()}", "${index + 1}") { onDestination(destination) }
    }
    return listOf(
        PaletteCommand("Toggle time travel", "t", hasHistory) { onToggleTimeTravel() },
        PaletteCommand("Play or pause playback", "space", hasHistory) { onTogglePlayback() },
        PaletteCommand("Jump to session start", null, state.timeTravelEnabled) { onSeek(0) },
        PaletteCommand("Jump to session end", null, state.timeTravelEnabled) {
            onSeek(state.actionStateHistory.size - 1)
        }
    ) + destinations + listOf(
        PaletteCommand(
            "Drop marker at pinned time", "m",
            state.selectedPublisher != null && state.pinnedTimeMs != null
        ) { onDropMarker() },
        PaletteCommand(
            "Export session", "e",
            state.canExportSession && state.selectedPublisher != null
        ) { onExportSession() },
        PaletteCommand("Import ghost session", "g") { onImportGhost() },
        PaletteCommand(
            "Clear history", null,
            hasHistory || state.logicMethodEvents.isNotEmpty()
        ) { onClearHistory() },
        PaletteCommand("Keyboard shortcuts", "?") { onHelp() }
    )
}

@Composable
private fun ListPane(
    store: Store,
    state: DevToolsUiState,
    findings: List<Finding>,
    dispatch: (DevToolsUiAction) -> Unit,
    scope: CoroutineScope,
    onSeek: (Int) -> Unit,
    onExportSession: () -> Unit,
    searchField: @Composable () -> Unit
) {
    when (state.destination) {
        DevToolsDestination.STREAM -> ActionStream(
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
            followLatest = state.followLatest,
            newEventsWhilePaused = state.newEventsWhilePaused,
            excludedActionTypes = state.excludedActionTypes,
            excludedLogicMethods = state.excludedLogicMethods,
            callIdToMethodIdentifier = state.callIdToMethodIdentifier,
            showActions = state.showActions,
            showLogicMethods = state.showLogicMethods,
            showLogs = state.showLogs,
            showNetwork = state.showNetwork,
            searchQuery = state.searchQuery,
            onClearSearch = { dispatch(DevToolsUiAction.SetSearchQuery("")) },
            searchField = searchField,
            onSelectAction = { dispatch(DevToolsUiAction.SelectAction(it)) },
            onSelectLogicMethod = { dispatch(DevToolsUiAction.SelectLogicMethodEvent(it)) },
            onSelectNetworkRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) },
            onSelectCrash = { dispatch(DevToolsUiAction.SelectCrash(it)) },
            onMarkerClick = { marker ->
                if (marker.afterActionIndex >= 0) onSeek(marker.afterActionIndex)
            },
            onFollowLatest = { dispatch(DevToolsUiAction.SelectAction(state.latestSelectableIndex)) },
            onAddExclusion = { dispatch(DevToolsUiAction.AddActionExclusion(it)) },
            onRemoveExclusion = { dispatch(DevToolsUiAction.RemoveActionExclusion(it)) },
            onSetExclusions = { dispatch(DevToolsUiAction.SetActionExclusions(it)) },
            onAddLogicMethodExclusion = { dispatch(DevToolsUiAction.AddLogicMethodExclusion(it)) },
            onRemoveLogicMethodExclusion = { dispatch(DevToolsUiAction.RemoveLogicMethodExclusion(it)) },
            onToggleShowActions = { dispatch(DevToolsUiAction.ToggleShowActions) },
            onToggleShowLogicMethods = { dispatch(DevToolsUiAction.ToggleShowLogicMethods) },
            onToggleShowLogs = { dispatch(DevToolsUiAction.ToggleShowLogs) },
            onToggleShowNetwork = { dispatch(DevToolsUiAction.ToggleShowNetwork) },
            onClear = { dispatch(DevToolsUiAction.ClearHistory) }
        )
        DevToolsDestination.STATE -> StateViewer(
            dataRevision = state.dataRevision,
            actionStateHistory = state.actionStateHistory,
            selectedActionIndex = state.selectedActionIndex
                ?: state.latestSelectableIndex.takeIf { it >= 0 },
            content = StateViewerContent.FULL_STATE,
            showAsDiff = state.showStateAsDiff,
            excludedActionTypes = state.excludedActionTypes,
            initialStateJson = state.initialStateJson,
            stateReads = state.stateReads,
            onToggleDiffMode = { dispatch(DevToolsUiAction.ToggleStateViewMode) },
            onClear = { dispatch(DevToolsUiAction.ClearHistory) }
        )
        DevToolsDestination.NAVIGATION -> NavigationPanel(
            dataRevision = state.dataRevision,
            actionStateHistory = state.actionStateHistory,
            selectedActionIndex = state.selectedActionIndex
                ?: state.latestSelectableIndex.takeIf { it >= 0 },
            initialStateJson = state.initialStateJson,
            logicMethodEvents = state.logicMethodEvents
        )
        DevToolsDestination.PERFORMANCE -> PerformancePanel(
            dataRevision = state.dataRevision,
            logicMethodEvents = state.logicMethodEvents,
            findings = findings,
            searchQuery = state.searchQuery,
            searchField = searchField,
            actionStateHistory = state.actionStateHistory,
            initialStateJson = state.initialStateJson
        )
        DevToolsDestination.NETWORK -> NetworkOverviewList(
            networkEvents = state.networkEvents,
            filter = state.networkFilter,
            searchQuery = state.searchQuery,
            showStats = state.showNetworkStats,
            searchField = searchField,
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
            onSelectRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) }
        )
        DevToolsDestination.FINDINGS -> FindingsPanel(
            findings = findings,
            onSeekTimestamp = { ts ->
                val index = state.actionStateHistory.withIndex().minByOrNull { (_, event) ->
                    val distance = event.timestamp - ts
                    if (distance < 0) -distance else distance
                }?.index
                if (index != null) onSeek(index)
            }
        )
        DevToolsDestination.LOGS -> LogsPanel(
            logs = state.deviceLogs,
            hiddenLevels = state.hiddenLogLevels,
            searchQuery = state.searchQuery,
            searchField = searchField,
            onToggleLevel = { dispatch(DevToolsUiAction.ToggleLogLevel(it)) },
            onClearSearch = { dispatch(DevToolsUiAction.SetSearchQuery("")) }
        )
        DevToolsDestination.DEVICES, DevToolsDestination.SESSIONS -> ClientList(
            clients = state.connectedClients,
            selectedPublisher = state.selectedPublisher,
            selectedListener = state.selectedListener,
            clientStatuses = state.clientStatuses,
            canExportSession = state.canExportSession,
            showDevices = state.destination == DevToolsDestination.DEVICES,
            showSessions = state.destination == DevToolsDestination.SESSIONS,
            onPublisherSelected = { clientId ->
                dispatch(DevToolsUiAction.SelectPublisher(clientId))
                if (clientId != null && clientId == state.selectedListener) {
                    dispatch(DevToolsUiAction.SelectListener(null))
                }
                clientId?.let {
                    dispatch(DevToolsUiAction.SetPublisherSessionStart(currentTimeMillis()))
                    dispatch(DevToolsUiAction.SetCanExportSession(true))
                    scope.launch {
                        val logic = DevToolsUiModule.selectLogicTyped(store)
                        logic.assignRole(DEVTOOLS_UI_CLIENT_ID, ClientRole.ORCHESTRATOR, it)
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
            onImportGhost = { dispatch(DevToolsUiAction.SetOverlay(Overlay.ImportGhost)) },
            onExportSession = onExportSession
        )
    }
}

@Composable
private fun Inspector(
    store: Store,
    state: DevToolsUiState,
    dispatch: (DevToolsUiAction) -> Unit,
    scope: CoroutineScope
) {
    val selection = state.selection
    val selectedNetworkRow = (selection as? Selection.NetworkRequest)?.let { selected ->
        state.networkEvents.lastOrNull { it.event.id == selected.requestId }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = when (selection) {
                    is Selection.Action -> "Action ${selection.index + 1}"
                    is Selection.LogicCall -> "Logic call"
                    is Selection.NetworkRequest -> "Request"
                    Selection.Crash -> "Crash"
                    Selection.None -> "Inspector"
                },
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selection is Selection.Action) {
                InspectorView.entries.forEach { view ->
                    FilterChip(
                        selected = view == state.inspectorView,
                        onClick = { dispatch(DevToolsUiAction.SetInspectorView(view)) },
                        label = { Text(view.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            if (selection !is Selection.None) {
                IconButton(
                    onClick = { dispatch(DevToolsUiAction.ClearSelection) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear the selection",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                selection is Selection.None -> EmptyState(
                    title = "Nothing selected",
                    detail = "Pick an action, a logic call, a request or the crash in the list or " +
                        "the timeline. j and k step through actions."
                )
                selectedNetworkRow != null -> NetworkRequestDetail(
                    row = selectedNetworkRow,
                    bodies = state.networkBodies,
                    onSelectRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) },
                    onFetchBody = { requestId, part ->
                        val owner = state.networkEvents.lastOrNull { it.event.id == requestId }?.clientId
                        if (owner != null) {
                            scope.launch {
                                DevToolsUiModule.selectLogicTyped(store).fetchNetworkBody(owner, requestId, part)
                            }
                        }
                    }
                )
                else -> StateViewer(
                    dataRevision = state.dataRevision,
                    actionStateHistory = state.actionStateHistory,
                    selectedActionIndex = state.selectedActionIndex,
                    logicMethodEvents = state.logicMethodEvents,
                    selectedLogicMethodCallId = state.selectedLogicMethodCallId,
                    crashEvent = state.crashEvent,
                    crashSelected = state.crashSelected,
                    content = when (state.inspectorView) {
                        InspectorView.EVENT -> StateViewerContent.EVENT
                        InspectorView.DELTA -> StateViewerContent.DELTA
                    },
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

/**
 * Downloads a session as a gzipped file, matching what a device writes.
 *
 * Session exports are gzipped everywhere else, and the import picker sniffs the magic number, so
 * compressing here keeps a UI-exported session interchangeable with a device-exported one. Falls
 * back to writing plain JSON where `CompressionStream` is unavailable, which the picker also reads.
 */
private fun downloadSession(json: String, gzName: String, plainName: String) {
    js("""
        (function(content, gzName, plainName) {
            function save(blob, fileName) {
                var url = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = url;
                a.download = fileName;
                a.click();
                URL.revokeObjectURL(url);
            }
            function savePlain() {
                save(new Blob([content], { type: 'application/json' }), plainName);
            }
            if (typeof CompressionStream === 'undefined') {
                savePlain();
                return;
            }
            var stream = new Blob([content]).stream()
                .pipeThrough(new CompressionStream('gzip'));
            new Response(stream).blob().then(function(blob) {
                save(new Blob([blob], { type: 'application/gzip' }), gzName);
            }).catch(savePlain);
        })(json, gzName, plainName)
    """)
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
