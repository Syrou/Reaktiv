package io.github.syrou.reaktiv.devtools

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.devtools.ui.DevToolsApp
import io.github.syrou.reaktiv.devtools.ui.DevToolsModule
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
    println("Main: Starting DevTools WASM application")
    println("Main: windowProtocol=$windowProtocol, windowHost=$windowHost")
    println("DevToolsApp: Creating store")
    val store = createStore {
        module(DevToolsModule)
        coroutineContext(Dispatchers.Default)
    }
    val protocol = if (windowProtocol == "https:") "wss:" else "ws:"
    val serverUrl = "$protocol//$windowHost/ws"

    println("Main: Connecting to DevTools server at: $serverUrl")
    try {
        CanvasBasedWindow(canvasElementId = "ComposeTarget") {
            println("Main: Inside CanvasBasedWindow")
            MaterialTheme {
                StoreProvider(store) {
                    DevToolsApp(store = store, serverUrl = serverUrl)
                }
            }
        }
        println("Main: CanvasBasedWindow created")
    } catch (e: Exception) {
        println("Main: Error creating window - ${e.message}")
        e.printStackTrace()
    }
}
