package io.github.syrou.reaktiv.devtools.client

import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * WebSocket connection to DevTools server.
 *
 * This is an expect/actual class with platform-specific implementations.
 */
public expect class DevToolsConnection(serverUrl: String) {
    /**
     * Current connection state.
     */
    public val connectionState: StateFlow<ConnectionState>

    /**
     * Connects to the DevTools server and sends registration.
     *
     * @param clientId Unique client identifier
     * @param clientName Display name for the client
     * @param platform Platform/device description
     */
    public suspend fun connect(clientId: String, clientName: String, platform: String)

    /**
     * Sends a message to the server.
     *
     * @param message Message to send
     */
    public suspend fun send(message: DevToolsMessage)

    /**
     * Registers a handler for incoming messages from the server.
     *
     * @param handler Function to handle incoming messages
     */
    public fun observeMessages(handler: suspend (DevToolsMessage) -> Unit)

    /**
     * Disconnects from the server and closes the connection.
     */
    public suspend fun disconnect()
}

public enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
