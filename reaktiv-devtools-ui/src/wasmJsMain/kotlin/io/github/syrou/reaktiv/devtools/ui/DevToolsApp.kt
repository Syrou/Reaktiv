package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.devtools.ui.components.PrettyJson
import io.github.syrou.reaktiv.devtools.ui.components.downloadFile
import io.github.syrou.reaktiv.devtools.ui.components.Shortcut
import kotlinx.serialization.json.JsonObject
import io.github.syrou.reaktiv.devtools.ui.seekTo
import io.github.syrou.reaktiv.devtools.ui.nearestIndexTo
import kotlinx.coroutines.CancellationException
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
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.SessionFileExport
import io.github.syrou.reaktiv.introspection.gzipCompress
import io.github.syrou.reaktiv.devtools.ui.components.AppLinksDialog
import io.github.syrou.reaktiv.devtools.ui.components.GraphDetail
import io.github.syrou.reaktiv.devtools.ui.components.RouteDetail
import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_EXTENSION
import io.github.syrou.reaktiv.devtools.ui.navmap.graph
import io.github.syrou.reaktiv.devtools.ui.navmap.parseLinkMap
import io.github.syrou.reaktiv.devtools.ui.navmap.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
        state.seekTo(index)?.let(dispatch)
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
        scope.launch { DevToolsUiModule.selectLogicTyped(store).addMarkerAtPinnedTime(label, note) }
    }

    fun exportSession() {
        val publisher = state.selectedPublisher ?: return
        scope.launch {
            try {
                val json = DevToolsUiModule.selectLogicTyped(store).exportSession()
                downloadFile(gzipCompress(json.encodeToByteArray()), "session_$publisher.json.gz")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                ReaktivDebug.error("DevTools UI could not export the session", e)
            }
        }
    }

    fun toggleTimeTravel() = dispatch(DevToolsUiAction.ToggleTimeTravel)

    fun exportNavigationMap() {
        val map = state.extensions[NAVIGATION_LINKS_EXTENSION] ?: return
        val name = state.connectedClients.find { it.clientId == state.selectedPublisher }?.clientName ?: "session"
        downloadFile(
            PrettyJson.encodeToString(JsonElement.serializer(), map).encodeToByteArray(),
            "navigation-map_" + name.replace(Regex("[^A-Za-z0-9._-]"), "-") + ".json"
        )
    }

    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }

    val liveDevices = state.connectedClients.liveDevices()
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
                    when (Shortcut.of(event.key, event.isShiftPressed)) {
                        Shortcut.SEARCH -> searchFocusRequester.requestFocus()
                        Shortcut.HELP -> showOverlay(Overlay.Help)
                        Shortcut.TIME_TRAVEL -> toggleTimeTravel()
                        Shortcut.PLAYBACK -> dispatch(DevToolsUiAction.SetAutoPlaying(!state.autoPlaying))
                        Shortcut.PREVIOUS -> seek((state.selectedActionIndex ?: state.actionStateHistory.size) - 1)
                        Shortcut.NEXT -> seek((state.selectedActionIndex ?: -1) + 1)
                        Shortcut.MARKER -> dropMarker()
                        Shortcut.IMPORT_GHOST -> showOverlay(Overlay.ImportGhost)
                        Shortcut.EXPORT_SESSION -> exportSession()
                        Shortcut.DEVICES -> showDestination(DevToolsDestination.DEVICES)
                        null -> return@onKeyEvent false
                    }
                    true
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
                        view = state.view,
                        onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
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
                    if (state.destination.showsCapturedData && !state.hasContentFor(state.destination)) {
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
                                    onExportMap = { exportNavigationMap() },
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
                Overlay.AppLinks -> AppLinksDialog(
                    form = state.appLinksForm,
                    tab = state.appLinksTab,
                    call = state.appLinksCall,
                    defaultBasePath = parseLinkMap(state.extensions[NAVIGATION_LINKS_EXTENSION])?.webPrefix
                        ?.takeIf { it.startsWith("/") } ?: "/",
                    blockedReason = openBlockedReason(state),
                    onFormChange = { dispatch(DevToolsUiAction.SetAppLinksForm(it)) },
                    onTabChange = { dispatch(DevToolsUiAction.SetAppLinksTab(it)) },
                    onGenerate = { form ->
                        scope.launch { DevToolsUiModule.selectLogicTyped(store).requestAppLinks(form) }
                    },
                    onDownload = { name, content -> downloadFile(content.encodeToByteArray(), name) },
                    onDismiss = { closeOverlay() }
                )
                Overlay.ImportGhost -> GhostImportDialog(
                    importError = state.ghostImportError,
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
                        onExportMap = { exportNavigationMap() },
                        onShowMap = {
                            dispatch(DevToolsUiAction.SetNavigationView(NavigationView.MAP))
                            showDestination(DevToolsDestination.NAVIGATION)
                        },
                        onAppLinks = { showOverlay(Overlay.AppLinks) },
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
    DevToolsDestination.NAVIGATION -> "Search routes"
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
    onExportMap: () -> Unit,
    onShowMap: () -> Unit,
    onAppLinks: () -> Unit,
    onImportGhost: () -> Unit,
    onClearHistory: () -> Unit,
    onHelp: () -> Unit
): List<PaletteCommand> {
    val hasHistory = state.actionStateHistory.isNotEmpty()
    val destinations = DevToolsDestination.entries.mapIndexed { index, destination ->
        PaletteCommand("Show ${destination.label.lowercase()}", "${index + 1}") { onDestination(destination) }
    }
    return listOf(
        PaletteCommand("Toggle time travel", Shortcut.TIME_TRAVEL.label, hasHistory) { onToggleTimeTravel() },
        PaletteCommand("Play or pause playback", Shortcut.PLAYBACK.label, hasHistory) { onTogglePlayback() },
        PaletteCommand("Jump to session start", null, state.timeTravelEnabled) { onSeek(0) },
        PaletteCommand("Jump to session end", null, state.timeTravelEnabled) {
            onSeek(state.actionStateHistory.size - 1)
        }
    ) + destinations + listOf(
        PaletteCommand(
            "Drop marker at pinned time", Shortcut.MARKER.label,
            state.selectedPublisher != null && state.pinnedTimeMs != null
        ) { onDropMarker() },
        PaletteCommand(
            "Export session", Shortcut.EXPORT_SESSION.label,
            state.canExportSession
        ) { onExportSession() },
        PaletteCommand("Show navigation map", null, state.extensions.containsKey(NAVIGATION_LINKS_EXTENSION)) { onShowMap() },
        PaletteCommand("Export navigation map", null, state.extensions.containsKey(NAVIGATION_LINKS_EXTENSION)) { onExportMap() },
        PaletteCommand("Generate app links files", null, state.extensions.containsKey(NAVIGATION_LINKS_EXTENSION)) { onAppLinks() },
        PaletteCommand("Import ghost session", Shortcut.IMPORT_GHOST.label) { onImportGhost() },
        PaletteCommand(
            "Clear history", null,
            hasHistory || state.logicMethodEvents.isNotEmpty()
        ) { onClearHistory() },
        PaletteCommand("Keyboard shortcuts", Shortcut.HELP.label) { onHelp() }
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
    onExportMap: () -> Unit,
    searchField: @Composable () -> Unit
) {
    when (state.destination) {
        DevToolsDestination.STREAM -> ActionStream(
            view = state.view,
            onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
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
            view = state.view,
            onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
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
            state = state,
            dispatch = dispatch,
            searchField = searchField,
            onExportMap = onExportMap
        )
        DevToolsDestination.PERFORMANCE -> PerformancePanel(
            view = state.view,
            onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
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
                downloadFile(
                    PrettyJson.encodeToString(JsonObject.serializer(), state.networkEvents.toHar()).encodeToByteArray(),
                    "reaktiv-network.har"
                )
            },
            onSelectRequest = { dispatch(DevToolsUiAction.SelectNetworkRequest(it)) }
        )
        DevToolsDestination.FINDINGS -> FindingsPanel(
            view = state.view,
            onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
            findings = findings,
            onSeekTimestamp = { ts -> state.actionStateHistory.nearestIndexTo(ts)?.let(onSeek) }
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
                if (clientId != null && clientId == state.selectedListener) {
                    dispatch(DevToolsUiAction.SelectListener(null))
                }
                clientId?.let {
                    scope.launch {
                        val logic = DevToolsUiModule.selectLogicTyped(store)
                        logic.attachToPublisher(it)
                        logic.assignRole(it, ClientRole.PUBLISHER)
                    }
                } ?: run {
                    dispatch(DevToolsUiAction.SelectPublisher(null))
                }
            },
            onListenerSelected = { clientId ->
                dispatch(DevToolsUiAction.SelectListener(clientId))
                if (clientId != null && clientId == state.selectedPublisher) {
                    dispatch(DevToolsUiAction.SelectPublisher(null))
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
                    is Selection.Route -> "Route"
                    is Selection.Graph -> "Graph"
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
        val linkModel = remember(state.extensions) { parseLinkMap(state.extensions[NAVIGATION_LINKS_EXTENSION]) }
        Box(modifier = Modifier.weight(1f)) {
            when {
                selection is Selection.Route -> {
                    val route = linkModel?.route(selection.path)
                    if (linkModel == null || route == null) {
                        EmptyState(title = "Route not in the map", detail = "The app's navigation map has no route ${selection.path}.")
                    } else {
                        RouteDetail(
                            model = linkModel,
                            route = route,
                            draft = state.linkDrafts[route.path] ?: LinkDraft(),
                            attempts = state.linkAttempts,
                            openBlockedReason = openBlockedReason(state),
                            onDraftChange = { dispatch(DevToolsUiAction.SetLinkDraft(route.path, it)) },
                            onOpen = { link, params ->
                                scope.launch {
                                    DevToolsUiModule.selectLogicTyped(store).openLinkOnPublisher(route.path, link, params)
                                }
                            },
                            onSelectGraph = { dispatch(DevToolsUiAction.SelectGraph(it)) },
                            onSelectRoute = { dispatch(DevToolsUiAction.SelectRoute(it)) }
                        )
                    }
                }
                selection is Selection.Graph -> {
                    val graph = linkModel?.graph(selection.id)
                    if (linkModel == null || graph == null) {
                        EmptyState(title = "Graph not in the map", detail = "The app's navigation map has no graph ${selection.id}.")
                    } else {
                        GraphDetail(
                            model = linkModel,
                            graph = graph,
                            collapsed = graph.id in state.collapsedGraphs,
                            onSelectRoute = { dispatch(DevToolsUiAction.SelectRoute(it)) },
                            onSelectGraph = { dispatch(DevToolsUiAction.SelectGraph(it)) },
                            onToggle = { dispatch(DevToolsUiAction.ToggleGraphCollapsed(graph.id)) }
                        )
                    }
                }
                selection is Selection.None -> EmptyState(
                    title = "Nothing selected",
                    detail = "Pick an action, a logic call, a request or the crash in the list or " +
                        "the timeline. j and k step through actions."
                )
                selectedNetworkRow != null -> NetworkRequestDetail(
                    view = state.view,
                    onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
                    row = selectedNetworkRow,
                    bodies = state.networkBodies,
                    publisherPlatform = state.connectedClients.find { it.clientId == state.selectedPublisher }?.platform,
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
                    view = state.view,
                    onViewChange = { dispatch(DevToolsUiAction.SetViewChoices(it)) },
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


