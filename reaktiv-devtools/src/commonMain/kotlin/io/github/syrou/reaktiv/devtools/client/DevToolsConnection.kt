package io.github.syrou.reaktiv.devtools.client

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * WebSocket connection to DevTools server.
 *
 * The transport is shared across every platform. Only the underlying ktor engine
 * is platform specific, supplied by [devToolsHttpClientEngine].
 */
public class DevToolsConnection(private val serverUrl: String) {

    private val client = HttpClient(devToolsHttpClientEngine()) {
        install(WebSockets)
    }

    private val json = reaktivJson()

    private val scope = CoroutineScope(SupervisorJob())
    private var session: DefaultClientWebSocketSession? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    /**
     * Current connection state.
     */
    public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val messageHandler = MutableStateFlow<(suspend (DevToolsMessage) -> Unit)?>(null)

    /**
     * Connects to the DevTools server and sends registration.
     *
     * @param clientId Unique client identifier
     * @param clientName Display name for the client
     * @param platform Platform/device description
     */
    public suspend fun connect(clientId: String, clientName: String, platform: String) {
        try {
            _connectionState.value = ConnectionState.CONNECTING

            session = client.webSocketSession(serverUrl)

            _connectionState.value = ConnectionState.CONNECTED

            send(DevToolsMessage.ClientRegistration(clientName, clientId, platform))

            scope.launch {
                receiveMessages()
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            println("DevTools: Failed to connect to $serverUrl - ${e.message}")
        }
    }

    /**
     * Sends a message to the server.
     *
     * @param message Message to send
     */
    public suspend fun send(message: DevToolsMessage) {
        try {
            val jsonString = json.encodeToString(message)
            session?.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            println("DevTools: Failed to send message - ${e.message}")
        }
    }

    /**
     * Registers a handler for incoming messages from the server.
     *
     * @param handler Function to handle incoming messages
     */
    public fun observeMessages(handler: suspend (DevToolsMessage) -> Unit) {
        messageHandler.value = handler
    }

    /**
     * Disconnects from the server and closes the connection.
     */
    public suspend fun disconnect() {
        session?.close()
        client.close()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun receiveMessages() {
        val currentSession = session ?: return

        try {
            while (scope.isActive && !currentSession.incoming.isClosedForReceive) {
                val frame = currentSession.incoming.receive()

                if (frame is Frame.Text) {
                    val text = frame.readText()
                    try {
                        val message = json.decodeFromString<DevToolsMessage>(text)
                        messageHandler.value?.invoke(message)
                    } catch (e: Exception) {
                        println("DevTools: Failed to parse message - ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (_connectionState.value == ConnectionState.CONNECTED) {
                _connectionState.value = ConnectionState.ERROR
                println("DevTools: Connection error - ${e.message}")
            }
        }
        if (_connectionState.value == ConnectionState.CONNECTED) {
            _connectionState.value = ConnectionState.DISCONNECTED
            println("DevTools: Connection closed by server")
        }
    }
}

public enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
