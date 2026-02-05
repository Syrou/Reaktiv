package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.introspection.IntrospectionLogic
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.devtools.client.ConnectionState
import kotlinx.coroutines.launch
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.middleware.DevToolsMiddleware
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/**
 * State representing the current DevTools connection status.
 *
 * This state can be observed to show connection status in the UI:
 * ```kotlin
 * @Composable
 * fun ConnectionIndicator() {
 *     val devToolsState by selectState<DevToolsState>().collectAsState()
 *
 *     when (devToolsState.connectionState) {
 *         ConnectionState.CONNECTED -> Icon(Icons.Check, "Connected")
 *         ConnectionState.CONNECTING -> CircularProgressIndicator()
 *         ConnectionState.DISCONNECTED -> Icon(Icons.Close, "Disconnected")
 *         ConnectionState.ERROR -> Icon(Icons.Error, "Error")
 *     }
 * }
 * ```
 */
@Serializable
data class DevToolsState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val currentServerUrl: String? = null,
    val errorMessage: String? = null
) : ModuleState

/**
 * Actions for controlling DevTools connection at runtime.
 *
 * Example usage:
 * ```kotlin
 * // Connect to a DevTools server
 * dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))
 *
 * // Disconnect
 * dispatch(DevToolsAction.Disconnect)
 *
 * // Reconnect after network change
 * dispatch(DevToolsAction.Reconnect)
 * ```
 */
@Serializable
sealed class DevToolsAction : ModuleAction(DevToolsModule::class) {
    /**
     * Connect to a DevTools server.
     *
     * @param serverUrl WebSocket URL (e.g., "ws://192.168.1.100:8080/ws")
     * @param clientName Optional new client name. If null, uses existing name from config.
     */
    @Serializable
    data class Connect(
        val serverUrl: String,
        val clientName: String? = null
    ) : DevToolsAction()

    /**
     * Disconnect from the current DevTools server.
     */
    @Serializable
    data object Disconnect : DevToolsAction()

    /**
     * Reconnect to the last known server.
     */
    @Serializable
    data object Reconnect : DevToolsAction()

    /**
     * Internal action to update connection state.
     */
    @Serializable
    internal data class UpdateConnectionState(
        val connectionState: ConnectionState,
        val serverUrl: String? = null,
        val errorMessage: String? = null
    ) : DevToolsAction()
}

/**
 * Logic class for managing DevTools connection lifecycle.
 *
 * The DevToolsMiddleware communicates with this logic to handle
 * connection operations triggered by DevToolsAction dispatches.
 */
class DevToolsLogic(
    private val storeAccessor: StoreAccessor,
    private val config: DevToolsConfig
) : ModuleLogic<DevToolsAction>() {

    private var connection: DevToolsConnection? = null
    private var currentServerUrl: String? = config.serverUrl
    private var currentClientName: String = config.clientName
    private var messageHandler: (suspend (DevToolsMessage) -> Unit)? = null

    private var sessionCapture: SessionCapture? = null

    init {
        // Get SessionCapture from IntrospectionModule
        storeAccessor.launch {
            try {
                val introspectionLogic = storeAccessor.selectLogic<IntrospectionLogic>()
                sessionCapture = introspectionLogic.getSessionCapture()
                println("DevTools: Using SessionCapture from IntrospectionModule")
            } catch (e: Exception) {
                println("DevTools: IntrospectionModule not found - session capture disabled. Register IntrospectionModule before DevToolsModule for crash capture support.")
            }
        }
    }

    /**
     * Gets the session capture instance for crash handling and session export.
     *
     * Note: This returns the SessionCapture from IntrospectionModule.
     * Make sure IntrospectionModule is registered before DevToolsModule.
     *
     * @return SessionCapture instance, or null if IntrospectionModule is not registered
     */
    fun getSessionCapture(): SessionCapture? = sessionCapture

    /**
     * Connect to a DevTools server.
     *
     * @param serverUrl WebSocket URL to connect to
     * @param clientName Optional client name override
     */
    suspend fun connect(serverUrl: String, clientName: String? = null) {
        connection?.disconnect()

        currentServerUrl = serverUrl
        if (clientName != null) {
            currentClientName = clientName
        }

        connection = DevToolsConnection(serverUrl)

        try {
            connection!!.connect(config.clientId, currentClientName, config.platform)
            storeAccessor.dispatch(
                DevToolsAction.UpdateConnectionState(
                    connectionState = ConnectionState.CONNECTED,
                    serverUrl = serverUrl
                )
            )

            messageHandler?.let { handler ->
                connection!!.observeMessages(handler)
            }
        } catch (e: Exception) {
            storeAccessor.dispatch(
                DevToolsAction.UpdateConnectionState(
                    connectionState = ConnectionState.ERROR,
                    serverUrl = serverUrl,
                    errorMessage = e.message
                )
            )
            println("DevTools: Failed to connect - ${e.message}")
        }
    }

    /**
     * Disconnect from the current DevTools server.
     */
    suspend fun disconnect() {
        connection?.disconnect()
        connection = null
        storeAccessor.dispatch(
            DevToolsAction.UpdateConnectionState(
                connectionState = ConnectionState.DISCONNECTED
            )
        )
    }

    /**
     * Reconnect to the last known server.
     */
    suspend fun reconnect() {
        val url = currentServerUrl
        if (url != null) {
            connect(url)
        }
    }

    /**
     * Send a message through the current connection.
     */
    suspend fun send(message: DevToolsMessage) {
        connection?.send(message)
    }

    /**
     * Register a handler for incoming server messages.
     */
    fun observeMessages(handler: suspend (DevToolsMessage) -> Unit) {
        messageHandler = handler
        connection?.observeMessages(handler)
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean {
        return connection?.connectionState?.value == ConnectionState.CONNECTED
    }

    /**
     * Get the current connection for direct access if needed.
     */
    fun getConnection(): DevToolsConnection? = connection

    /**
     * Exports the current session as a JSON string for manual sharing.
     * This can be imported later as a ghost device in the DevTools UI.
     *
     * @return JSON string representing the session, or null if session capture is disabled
     */
    fun exportSessionJson(): String? {
        return sessionCapture?.exportSession()
    }

    /**
     * Exports the current session with crash information.
     * Typically called by crash handlers.
     *
     * @param throwable The exception that caused the crash
     * @return JSON string representing the session with crash info, or null if session capture is disabled
     */
    fun exportCrashSessionJson(throwable: Throwable): String? {
        return sessionCapture?.exportCrashSession(throwable)
    }

    /**
     * Cleans up resources and stops session capture.
     * Call this when the DevTools connection is no longer needed.
     */
    fun cleanup() {
        sessionCapture?.stop()
        println("DevTools: Logic cleanup complete")
    }
}

/**
 * Module for DevTools connection management.
 *
 * This module provides state/logic for connection management AND
 * the middleware for intercepting actions. Simply register the module
 * and everything is wired up automatically.
 *
 * Example usage:
 * ```kotlin
 * val store = createStore {
 *     module(DevToolsModule(
 *         config = DevToolsConfig(
 *             serverUrl = "ws://192.168.1.100:8080/ws", // Auto-connect
 *             platform = "Android"
 *         ),
 *         scope = lifecycleScope
 *     ))
 *     // ... other modules
 * }
 *
 * // Or with ad-hoc connection:
 * val store = createStore {
 *     module(DevToolsModule(
 *         config = DevToolsConfig(
 *             serverUrl = null, // Don't auto-connect
 *             platform = "Android"
 *         ),
 *         scope = lifecycleScope
 *     ))
 * }
 *
 * // Later, connect via action:
 * store.dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))
 * ```
 */
class DevToolsModule(
    private val config: DevToolsConfig,
    scope: CoroutineScope
) : ModuleWithLogic<DevToolsState, DevToolsAction, DevToolsLogic> {

    override val initialState = DevToolsState()

    override val reducer: (DevToolsState, DevToolsAction) -> DevToolsState = { state, action ->
        when (action) {
            is DevToolsAction.UpdateConnectionState -> state.copy(
                connectionState = action.connectionState,
                currentServerUrl = action.serverUrl ?: state.currentServerUrl,
                errorMessage = action.errorMessage
            )
            is DevToolsAction.Connect -> state.copy(
                connectionState = ConnectionState.CONNECTING,
                currentServerUrl = action.serverUrl,
                errorMessage = null
            )
            is DevToolsAction.Disconnect -> state.copy(
                connectionState = ConnectionState.DISCONNECTED,
                errorMessage = null
            )
            is DevToolsAction.Reconnect -> state.copy(
                connectionState = ConnectionState.CONNECTING,
                errorMessage = null
            )
        }
    }

    override val createLogic: (StoreAccessor) -> DevToolsLogic = { storeAccessor ->
        DevToolsLogic(storeAccessor, config)
    }

    override val createMiddleware: (() -> Middleware) = {
        DevToolsMiddleware(config, scope).middleware
    }
}
