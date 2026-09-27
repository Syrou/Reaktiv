package io.github.syrou.reaktiv.devtools

import kotlinx.coroutines.launch
import io.github.syrou.reaktiv.core.util.ReaktivDebug

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.devtools.ui.DevToolsApp
import io.github.syrou.reaktiv.devtools.ui.DevToolsUiModule
import io.github.syrou.reaktiv.devtools.ui.DevToolsTheme
import kotlinx.coroutines.Dispatchers

private val windowProtocol: String = js("window.location.protocol")
private val windowHost: String = js("window.location.host")

/**
 * Entry point for the Reaktiv DevTools WASM UI.
 *
 * This WASM application connects to the DevTools server and provides
 * a UI for managing client connections, viewing actions, and inspecting state.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ReaktivDebug.enable()
    val store = createStore {
        module(DevToolsUiModule)
        coroutineContext(Dispatchers.Default)
    }
    val protocol = if (windowProtocol == "https:") "wss:" else "ws:"
    val serverUrl = "$protocol//$windowHost/ws"
    store.launch { DevToolsUiModule.selectLogicTyped(store).connect(serverUrl) }

    try {
        ComposeViewport(viewportContainerId = "root") {
            DevToolsTheme {
                StoreProvider(store) {
                    DevToolsApp(store = store, serverUrl = serverUrl)
                }
            }
        }
    } catch (e: Exception) {
        ReaktivDebug.error("DevTools UI could not create its window", e)
    }
}
