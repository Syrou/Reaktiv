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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberDispatcher
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.devtools.ui.components.ActionStream
import io.github.syrou.reaktiv.devtools.ui.components.ClientList
import io.github.syrou.reaktiv.devtools.ui.components.ConnectionStatus
import io.github.syrou.reaktiv.devtools.ui.components.FindingsBadge
import io.github.syrou.reaktiv.devtools.ui.components.FindingsPanel
import io.github.syrou.reaktiv.devtools.ui.components.rememberFindings
import io.github.syrou.reaktiv.devtools.protocol.Finding
import io.github.syrou.reaktiv.devtools.protocol.FindingSeverity
import io.github.syrou.reaktiv.devtools.ui.components.GhostImportDialog
import io.github.syrou.reaktiv.devtools.ui.components.CommandPalette
import io.github.syrou.reaktiv.devtools.ui.components.HelpOverlay
import io.github.syrou.reaktiv.devtools.ui.components.MarkerDialog
import io.github.syrou.reaktiv.devtools.ui.components.NavigationPanel
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import io.github.syrou.reaktiv.devtools.ui.components.NetworkRequestDetail
import io.github.syrou.reaktiv.devtools.ui.components.NetworkOverviewList
import io.github.syrou.reaktiv.devtools.ui.components.EmptyState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke

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
        if(!storePrepared) return@remember null
        ReaktivDebug.general("DevTools UI connecting to $serverUrl")
        DevToolsConnection(serverUrl)
    }

    LaunchedEffect(storePrepared) {
        if(!storePrepared) return@LaunchedEffect
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
    val searchFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }
    var splitFraction by remember { mutableStateOf(0.6f) }
    var contentWidthPx by remember { mutableStateOf(0f) }

    fun seek(index: Int) {
        if (state.actionStateHistory.isEmpty()) return
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
                        Key.One -> {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.DEBUG))
                            true
                        }
                        Key.Two -> {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.PERFORMANCE))
                            true
                        }
                        Key.Three -> {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.NETWORK))
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
            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionStatus(
                    connectionState = state.connectionState,
                    deviceCount = state.connectedClients.size,
                    isDevicePanelExpanded = state.devicePanelExpanded,
                    onToggleDevicePanel = { dispatch(DevToolsUiAction.ToggleDevicePanel) },
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
                        compact = state.mode != DevToolsMode.PERFORMANCE
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

                ModeBar(
                    mode = state.mode,
                    performanceView = state.performanceView,
                    findings = findings,
                    searchQuery = state.searchQuery,
                    searchFocusRequester = searchFocusRequester,
                    onSearchFocusChanged = { searchFocused = it },
                    onSearchQueryChange = { dispatch(DevToolsUiAction.SetSearchQuery(it)) },
                    onModeChange = { dispatch(DevToolsUiAction.SetMode(it)) },
                    onPerformanceViewChange = { dispatch(DevToolsUiAction.SetPerformanceView(it)) }
                )
                Divider(modifier = Modifier.fillMaxWidth().height(1.dp))

                if (state.actionStateHistory.isEmpty() && state.logicMethodEvents.isEmpty()) {
                    OnboardingPanel(
                        serverUrl = serverUrl,
                        hasClients = state.connectedClients.any {
                            !it.isGhost && it.clientId != DEVTOOLS_UI_CLIENT_ID
                        },
                        onImportGhost = { dispatch(DevToolsUiAction.ShowImportGhostDialog) }
                    )
                    return@Column
                }

                val inspecting = state.selection !is Selection.None

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .onSizeChanged { contentWidthPx = it.width.toFloat() }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(if (inspecting) splitFraction else 1f)
                    ) {
                        ModeContent(
                            state = state,
                            findings = findings,
                            dispatch = dispatch,
                            onSeek = { seek(it) }
                        )
                    }

                    if (inspecting) {
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

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f - splitFraction)
                        ) {
                            Inspector(
                                store = store,
                                state = state,
                                dispatch = dispatch,
                                scope = scope,
                                onClose = { dispatch(DevToolsUiAction.ClearSelection) }
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
                        PaletteCommand("Play or pause playback", "space", state.actionStateHistory.isNotEmpty()) {
                            dispatch(DevToolsUiAction.SetAutoPlaying(!state.autoPlaying))
                        },
                        PaletteCommand("Jump to session start", null, state.timeTravelEnabled) { seek(0) },
                        PaletteCommand("Jump to session end", null, state.timeTravelEnabled) {
                            seek(state.actionStateHistory.size - 1)
                        },
                        PaletteCommand("Show debug mode", "1") {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.DEBUG))
                        },
                        PaletteCommand("Show performance mode", "2") {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.PERFORMANCE))
                        },
                        PaletteCommand("Show network mode", "3") {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.NETWORK))
                        },
                        PaletteCommand("Show navigation for the selection", null) {
                            dispatch(DevToolsUiAction.SetInspectorTab(InspectorTab.NAVIGATION))
                        },
                        PaletteCommand("Show findings", null) {
                            dispatch(DevToolsUiAction.SetMode(DevToolsMode.PERFORMANCE))
                            dispatch(DevToolsUiAction.SetPerformanceView(PerformanceView.FINDINGS))
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

@Composable
private fun ModeBar(
    mode: DevToolsMode,
    performanceView: PerformanceView,
    findings: List<Finding>,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onModeChange: (DevToolsMode) -> Unit,
    onPerformanceViewChange: (PerformanceView) -> Unit
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DevToolsMode.entries.forEach { entry ->
            val selected = entry == mode
            TextButton(onClick = { onModeChange(entry) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (entry == DevToolsMode.PERFORMANCE && findings.isNotEmpty()) {
                        FindingsBadge(
                            count = findings.size,
                            hasCritical = findings.any { it.severity == FindingSeverity.CRITICAL }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        ModeSearchField(
            value = searchQuery,
            placeholder = when (mode) {
                DevToolsMode.DEBUG -> "Search events"
                DevToolsMode.PERFORMANCE -> "Search methods"
                DevToolsMode.NETWORK -> "Search requests"
            },
            focusRequester = searchFocusRequester,
            onFocusChanged = onSearchFocusChanged,
            onValueChange = onSearchQueryChange,
            onEscape = {
                onSearchQueryChange("")
                focusManager.clearFocus()
            }
        )

        if (mode == DevToolsMode.PERFORMANCE) {
            Spacer(modifier = Modifier.width(8.dp))
            PerformanceView.entries.forEach { view ->
                FilterChip(
                    selected = view == performanceView,
                    onClick = { onPerformanceViewChange(view) },
                    label = { Text(view.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ModeSearchField(
    value: String,
    placeholder: String,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onEscape: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = 1.dp,
            color = if (focused) colors.primary else colors.outlineVariant
        ),
        modifier = Modifier.width(240.dp).height(28.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = "$placeholder  ( / )",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocusChanged(it.isFocused)
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                onEscape()
                                true
                            } else {
                                false
                            }
                        }
                )
            }
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear the search",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier
                        .size(13.dp)
                        .clickable { onValueChange("") }
                )
            }
        }
    }
}

@Composable
private fun ModeContent(
    state: DevToolsUiState,
    findings: List<Finding>,
    dispatch: (DevToolsUiAction) -> Unit,
    onSeek: (Int) -> Unit
) {
    when (state.mode) {
        DevToolsMode.DEBUG -> ActionStream(
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

        DevToolsMode.PERFORMANCE -> when (state.performanceView) {
            PerformanceView.METHODS -> PerformancePanel(
                dataRevision = state.dataRevision,
                logicMethodEvents = state.logicMethodEvents,
                findings = findings,
                searchQuery = state.searchQuery,
                actionStateHistory = state.actionStateHistory,
                initialStateJson = state.initialStateJson
            )
            PerformanceView.FINDINGS -> FindingsPanel(
                findings = findings,
                onSeekTimestamp = { ts ->
                    val index = state.actionStateHistory.withIndex().minByOrNull { (_, event) ->
                        val distance = event.timestamp - ts
                        if (distance < 0) -distance else distance
                    }?.index
                    if (index != null) onSeek(index)
                }
            )
        }

        DevToolsMode.NETWORK -> NetworkOverviewList(
            networkEvents = state.networkEvents,
            filter = state.networkFilter,
            searchQuery = state.searchQuery,
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
            onSelectRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) }
        )
    }
}

@Composable
private fun Inspector(
    store: Store,
    state: DevToolsUiState,
    dispatch: (DevToolsUiAction) -> Unit,
    scope: CoroutineScope,
    onClose: () -> Unit
) {
    val selectedNetworkRow = (state.selection as? Selection.NetworkRequest)?.let { selected ->
        state.networkEvents.lastOrNull { it.event.id == selected.requestId }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (val selection = state.selection) {
                    is Selection.Action -> "Action ${selection.index + 1}"
                    is Selection.LogicCall -> "Logic call"
                    is Selection.NetworkRequest -> "Request"
                    Selection.Crash -> "Crash"
                    Selection.None -> "Inspector"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close the inspector",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            InspectorTab.entries.forEach { tab ->
                FilterChip(
                    selected = tab == state.inspectorTab,
                    onClick = { dispatch(DevToolsUiAction.SetInspectorTab(tab)) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

    Box(modifier = Modifier.weight(1f)) {
    when {
        state.inspectorTab == InspectorTab.NAVIGATION -> NavigationPanel(
            dataRevision = state.dataRevision,
            actionStateHistory = state.actionStateHistory,
            selectedActionIndex = state.selectedActionIndex,
            initialStateJson = state.initialStateJson,
            logicMethodEvents = state.logicMethodEvents
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

        state.selection is Selection.None -> EmptyState(
            title = "Nothing selected",
            detail = "Pick an action, a logic call or a request, here or in the timeline, and " +
                "everything known about it appears in this pane."
        )

        else -> StateViewer(
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
