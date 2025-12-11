package io.github.syrou.reaktiv.devtools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    LaunchedEffect(state.actionHistory.size, state.autoSelectLatest) {
        if (state.autoSelectLatest && state.actionHistory.isNotEmpty()) {
            val latestIndex = state.actionHistory.size - 1
            if (state.selectedActionIndex != latestIndex) {
                dispatch(DevToolsAction.SelectAction(latestIndex))
            }
        }
    }

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
                            logic.assignRole("devtools-ui", ClientRole.LISTENER, it)
                        }
                    }
                },
                onListenerSelected = { dispatch(DevToolsAction.SelectListener(it)) },
                onAssignRole = { listener, publisher ->
                    scope.launch {
                        val logic = DevToolsModule.selectLogicTyped(store)
                        logic.assignRole(publisher, ClientRole.PUBLISHER)
                        logic.assignRole(listener, ClientRole.LISTENER, publisher)
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
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.6f)
            ) {
                ActionStream(
                    actions = state.actionHistory,
                    selectedIndex = state.selectedActionIndex,
                    autoSelectLatest = state.autoSelectLatest,
                    onSelectAction = { dispatch(DevToolsAction.SelectAction(it)) },
                    onToggleAutoSelect = { dispatch(DevToolsAction.ToggleAutoSelectLatest) },
                    onClear = { dispatch(DevToolsAction.ClearHistory) }
                )
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
                    states = state.stateHistory,
                    actions = state.actionHistory,
                    selectedActionIndex = state.selectedActionIndex,
                    showAsDiff = state.showStateAsDiff,
                    onToggleDiffMode = { dispatch(DevToolsAction.ToggleStateViewMode) },
                    onClear = { dispatch(DevToolsAction.ClearHistory) }
                )
            }
        }
    }
}
