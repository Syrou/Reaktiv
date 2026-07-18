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
import io.github.syrou.reaktiv.devtools.ui.components.GhostImportDialog
import io.github.syrou.reaktiv.devtools.ui.components.PerformancePanel
import io.github.syrou.reaktiv.devtools.ui.components.StateViewer
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
fun DevToolsApp(store: Store, serverUrl: String = "ws://localhost:8080/ws") {
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
        DevToolsContent(store)
    }
}

@Composable
private fun DevToolsContent(store: Store) {
    val state by composeState<DevToolsUiState>()
    val dispatch = rememberDispatcher()
    val scope = rememberCoroutineScope()

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
        Box(modifier = Modifier.fillMaxSize()) {
            // Main content
            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionStatus(
                    connectionState = state.connectionState,
                    deviceCount = state.connectedClients.size,
                    isDevicePanelExpanded = state.devicePanelExpanded,
                    onToggleDevicePanel = { dispatch(DevToolsUiAction.ToggleDevicePanel) }
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.6f)
                    ) {
                        ActionStream(
                            actions = state.actionStateHistory,
                            logicMethodEvents = state.logicMethodEvents,
                            crashEvent = state.crashEvent,
                            selectedIndex = state.selectedActionIndex,
                            selectedLogicMethodCallId = state.selectedLogicMethodCallId,
                            crashSelected = state.crashSelected,
                            autoSelectLatest = state.autoSelectLatest,
                            excludedActionTypes = state.excludedActionTypes,
                            excludedLogicMethods = state.excludedLogicMethods,
                            callIdToMethodIdentifier = state.callIdToMethodIdentifier,
                            timeTravelEnabled = state.timeTravelEnabled,
                            showActions = state.showActions,
                            showLogicMethods = state.showLogicMethods,
                            onSelectAction = { dispatch(DevToolsUiAction.SelectAction(it)) },
                            onSelectLogicMethod = { dispatch(DevToolsUiAction.SelectLogicMethodEvent(it)) },
                            onSelectCrash = { dispatch(DevToolsUiAction.SelectCrash(it)) },
                            onToggleAutoSelect = { dispatch(DevToolsUiAction.ToggleAutoSelectLatest) },
                            onAddExclusion = { dispatch(DevToolsUiAction.AddActionExclusion(it)) },
                            onRemoveExclusion = { dispatch(DevToolsUiAction.RemoveActionExclusion(it)) },
                            onSetExclusions = { dispatch(DevToolsUiAction.SetActionExclusions(it)) },
                            onAddLogicMethodExclusion = { dispatch(DevToolsUiAction.AddLogicMethodExclusion(it)) },
                            onRemoveLogicMethodExclusion = { dispatch(DevToolsUiAction.RemoveLogicMethodExclusion(it)) },
                            onToggleTimeTravel = { dispatch(DevToolsUiAction.ToggleTimeTravel) },
                            onToggleShowActions = { dispatch(DevToolsUiAction.ToggleShowActions) },
                            onToggleShowLogicMethods = { dispatch(DevToolsUiAction.ToggleShowLogicMethods) },
                            onClear = { dispatch(DevToolsUiAction.ClearHistory) }
                        )

                        if (state.timeTravelEnabled && state.actionStateHistory.isNotEmpty()) {
                            TimeTravelBar(
                                currentPosition = state.timeTravelPosition,
                                totalEvents = state.actionStateHistory.size,
                                isGhostMode = state.activeGhostId != null,
                                onPositionChange = { dispatch(DevToolsUiAction.SetTimeTravelPosition(it)) },
                                onClose = {
                                    dispatch(DevToolsUiAction.ToggleTimeTravel)
                                    if (state.activeGhostId != null) {
                                        dispatch(DevToolsUiAction.SetActiveGhostId(null))
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }

                    Divider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.4f)
                    ) {
                        TabRow(selectedTabIndex = if (state.showPerformancePanel) 1 else 0) {
                            Tab(
                                selected = !state.showPerformancePanel,
                                onClick = { dispatch(DevToolsUiAction.SetPerformancePanel(false)) },
                                text = { Text("State") }
                            )
                            Tab(
                                selected = state.showPerformancePanel,
                                onClick = { dispatch(DevToolsUiAction.SetPerformancePanel(true)) },
                                text = { Text("Performance") }
                            )
                        }
                        if (state.showPerformancePanel) {
                            PerformancePanel(
                                logicMethodEvents = state.logicMethodEvents,
                                actionStateHistory = state.actionStateHistory,
                                initialStateJson = state.initialStateJson,
                                warningFilter = state.performanceWarningFilter,
                                onWarningFilterChange = {
                                    dispatch(DevToolsUiAction.SetPerformanceWarningFilter(it))
                                }
                            )
                        } else {
                            StateViewer(
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
                        onExportSession = {
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
                                        stateReads = state.stateReads
                                    )
                                    downloadJson(json, "session_${publisher.clientId}.json")
                                }
                            }
                        }
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
    onPositionChange: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var playbackSpeed by remember { mutableStateOf(1f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var autoPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(autoPlaying, currentPosition, playbackSpeed) {
        if (autoPlaying && currentPosition < totalEvents - 1) {
            delay((1000 / playbackSpeed).toLong())
            onPositionChange(currentPosition + 1)
        } else if (currentPosition >= totalEvents - 1) {
            autoPlaying = false
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

                IconButton(onClick = { autoPlaying = !autoPlaying }) {
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
