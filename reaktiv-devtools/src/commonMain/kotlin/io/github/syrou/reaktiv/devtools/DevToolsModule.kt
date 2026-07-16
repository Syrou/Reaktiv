package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.devtools.client.ConnectionState
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
public data class DevToolsState(
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
public sealed class DevToolsAction : ModuleAction(DevToolsModule::class) {
    /**
     * Connect to a DevTools server.
     *
     * @param serverUrl WebSocket URL (e.g., "ws://192.168.1.100:8080/ws")
     * @param clientName Optional new client name. If null, uses existing name from config.
     */
    @Serializable
    public data class Connect(
        val serverUrl: String,
        val clientName: String? = null
    ) : DevToolsAction()

    /**
     * Disconnect from the current DevTools server.
     */
    @Serializable
    public data object Disconnect : DevToolsAction()

    /**
     * Reconnect to the last known server.
     */
    @Serializable
    public data object Reconnect : DevToolsAction()

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
public class DevToolsLogic(
    private val storeAccessor: StoreAccessor,
    private val config: DevToolsConfig,
    private val sessionCapture: SessionCapture? = null
) : ModuleLogic() {

    private var connection: DevToolsConnection? = null
    private var currentServerUrl: String? = config.serverUrl
    private var currentClientName: String = config.clientName
    private var messageHandler: (suspend (DevToolsMessage) -> Unit)? = null

    /**
     * Gets the session capture instance for session history sync.
     *
     * @return SessionCapture instance, or null if not provided
     */
    public fun getSessionCapture(): SessionCapture? = sessionCapture

    /**
     * Connect to a DevTools server.
     *
     * @param serverUrl WebSocket URL to connect to
     * @param clientName Optional client name override
     */
    public suspend fun connect(serverUrl: String, clientName: String? = null) {
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
    public suspend fun disconnect() {
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
    public suspend fun reconnect() {
        val url = currentServerUrl
        if (url != null) {
            connect(url)
        }
    }

    /**
     * Send a message through the current connection.
     */
    public suspend fun send(message: DevToolsMessage) {
        connection?.send(message)
    }

    /**
     * Register a handler for incoming server messages.
     */
    public fun observeMessages(handler: suspend (DevToolsMessage) -> Unit) {
        messageHandler = handler
        connection?.observeMessages(handler)
    }

    /**
     * Check if currently connected.
     */
    public fun isConnected(): Boolean {
        return connection?.connectionState?.value == ConnectionState.CONNECTED
    }

    /**
     * Get the current connection for direct access if needed.
     */
    public fun getConnection(): DevToolsConnection? = connection

    /**
     * Cleans up resources and stops session capture.
     * Call this when the DevTools connection is no longer needed.
     */
    public suspend fun cleanup() {
        sessionCapture?.stop()
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
 * val introspectionConfig = IntrospectionConfig(platform = "Android")
 * val sessionCapture = SessionCapture()
 *
 * val store = createStore {
 *     module(IntrospectionModule(introspectionConfig, sessionCapture))
 *     module(DevToolsModule(
 *         config = DevToolsConfig(
 *             introspectionConfig = introspectionConfig,
 *             serverUrl = "ws://192.168.1.100:8080/ws"
 *         ),
 *         scope = lifecycleScope,
 *         sessionCapture = sessionCapture
 *     ))
 *     // ... other modules
 * }
 *
 * // Later, connect via action (if serverUrl was null):
 * store.dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))
 * ```
 */
public class DevToolsModule(
    private val config: DevToolsConfig,
    scope: CoroutineScope,
    private val sessionCapture: SessionCapture? = null
) : ModuleWithLogic<DevToolsState, DevToolsAction, DevToolsLogic> {

    override val initialState: DevToolsState = DevToolsState()

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
        DevToolsLogic(storeAccessor, config, sessionCapture)
    }

    override val createMiddleware: (() -> Middleware) = {
        DevToolsMiddleware(config, scope).middleware
    }
}
