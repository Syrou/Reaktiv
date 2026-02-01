package io.github.syrou.reaktiv.devtools.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.github.syrou.reaktiv.devtools.ui.components.StateViewer
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
            val logic = DevToolsModule.selectLogicTyped(store)
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
    val state by composeState<DevToolsState>()
    val dispatch = rememberDispatcher()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.timeTravelEnabled, state.timeTravelPosition, state.selectedPublisher) {
        val publisher = state.selectedPublisher
        if (state.timeTravelEnabled && state.timeTravelPosition < state.actionStateHistory.size && publisher != null) {
            val event = state.actionStateHistory[state.timeTravelPosition]
            val logic = DevToolsModule.selectLogicTyped(store)
            logic.sendTimeTravelSync(event, publisher)
        }
    }

    LaunchedEffect(state.actionStateHistory.size, state.autoSelectLatest, state.excludedActionTypes, state.timeTravelEnabled) {
        if (state.autoSelectLatest && !state.timeTravelEnabled && state.actionStateHistory.isNotEmpty()) {
            // Find the latest non-excluded action
            val latestNonExcludedIndex = state.actionStateHistory.indexOfLast { event ->
                !state.excludedActionTypes.contains(event.actionType)
            }

            if (latestNonExcludedIndex >= 0 && state.selectedActionIndex != latestNonExcludedIndex) {
                dispatch(DevToolsAction.SelectAction(latestNonExcludedIndex))
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConnectionStatus(
            connectionState = state.connectionState,
            deviceCount = state.connectedClients.size,
            isDevicePanelExpanded = state.devicePanelExpanded,
            onToggleDevicePanel = { dispatch(DevToolsAction.ToggleDevicePanel) }
        )

        if (state.devicePanelExpanded) {
            ClientList(
                clients = state.connectedClients,
                selectedPublisher = state.selectedPublisher,
                selectedListener = state.selectedListener,
                onPublisherSelected = { clientId ->
                    dispatch(DevToolsAction.SelectPublisher(clientId))
                    clientId?.let {
                        scope.launch {
                            val logic = DevToolsModule.selectLogicTyped(store)
                            logic.assignRole(it, ClientRole.PUBLISHER)
                            logic.assignRole("devtools-ui", ClientRole.ORCHESTRATOR, it)
                        }
                    }
                },
                onListenerSelected = { dispatch(DevToolsAction.SelectListener(it)) },
                onAssignRole = { listener, publisher ->
                    scope.launch {
                        val logic = DevToolsModule.selectLogicTyped(store)
                        logic.assignRole(publisher, ClientRole.PUBLISHER)
                        logic.assignRole(listener, ClientRole.SUBSCRIBER, publisher)
                    }
                }
            )

            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
            )
        }

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
                    selectedIndex = state.selectedActionIndex,
                    autoSelectLatest = state.autoSelectLatest,
                    excludedActionTypes = state.excludedActionTypes,
                    timeTravelEnabled = state.timeTravelEnabled,
                    onSelectAction = { dispatch(DevToolsAction.SelectAction(it)) },
                    onToggleAutoSelect = { dispatch(DevToolsAction.ToggleAutoSelectLatest) },
                    onAddExclusion = { dispatch(DevToolsAction.AddActionExclusion(it)) },
                    onRemoveExclusion = { dispatch(DevToolsAction.RemoveActionExclusion(it)) },
                    onSetExclusions = { dispatch(DevToolsAction.SetActionExclusions(it)) },
                    onToggleTimeTravel = { dispatch(DevToolsAction.ToggleTimeTravel) },
                    onClear = { dispatch(DevToolsAction.ClearHistory) }
                )

                if (state.timeTravelEnabled && state.actionStateHistory.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
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
                                Text(
                                    text = "Time Travel: ${state.timeTravelPosition + 1} / ${state.actionStateHistory.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                IconButton(onClick = { dispatch(DevToolsAction.ToggleTimeTravelExpanded) }) {
                                    Icon(
                                        imageVector = if (state.timeTravelExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = if (state.timeTravelExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            if (state.timeTravelExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))

                                Slider(
                                    value = state.timeTravelPosition.toFloat(),
                                    onValueChange = { dispatch(DevToolsAction.SetTimeTravelPosition(it.toInt())) },
                                    valueRange = 0f..(state.actionStateHistory.size - 1).toFloat(),
                                    steps = if (state.actionStateHistory.size > 2) state.actionStateHistory.size - 2 else 0
                                )

                                if (state.timeTravelPosition < state.actionStateHistory.size) {
                                    val currentEvent = state.actionStateHistory[state.timeTravelPosition]
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Action: ${currentEvent.actionType}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
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
                StateViewer(
                    actionStateHistory = state.actionStateHistory,
                    selectedActionIndex = state.selectedActionIndex,
                    showAsDiff = state.showStateAsDiff,
                    excludedActionTypes = state.excludedActionTypes,
                    onToggleDiffMode = { dispatch(DevToolsAction.ToggleStateViewMode) },
                    onClear = { dispatch(DevToolsAction.ClearHistory) }
                )
            }
        }
        }
    }
}
