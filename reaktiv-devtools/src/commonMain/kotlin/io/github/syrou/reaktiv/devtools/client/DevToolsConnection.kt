package io.github.syrou.reaktiv.devtools.client

import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * WebSocket connection to DevTools server.
 *
 * This is an expect/actual class with platform-specific implementations.
 */
expect class DevToolsConnection(serverUrl: String) {
    /**
     * Current connection state.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Connects to the DevTools server and sends registration.
     *
     * @param clientId Unique client identifier
     * @param clientName Display name for the client
     * @param platform Platform/device description
     */
    suspend fun connect(clientId: String, clientName: String, platform: String)

    /**
     * Sends a message to the server.
     *
     * @param message Message to send
     */
    suspend fun send(message: DevToolsMessage)

    /**
     * Registers a handler for incoming messages from the server.
     *
     * @param handler Function to handle incoming messages
     */
    fun observeMessages(handler: suspend (DevToolsMessage) -> Unit)

    /**
     * Disconnects from the server and closes the connection.
     */
    suspend fun disconnect()
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
