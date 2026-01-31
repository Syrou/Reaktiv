package io.github.syrou.reaktiv.devtools.client

import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual class DevToolsConnection actual constructor(private val serverUrl: String) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val scope = CoroutineScope(SupervisorJob())
    private var session: DefaultClientWebSocketSession? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    actual val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val messageHandler = MutableStateFlow<(suspend (DevToolsMessage) -> Unit)?>(null)

    actual suspend fun connect(clientId: String, clientName: String, platform: String) {
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
            println("DevTools: Failed to connect - ${e.message}")
        }
    }

    actual suspend fun send(message: DevToolsMessage) {
        try {
            val jsonString = json.encodeToString(message)
            session?.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            println("DevTools: Failed to send message - ${e.message}")
        }
    }

    actual fun observeMessages(handler: suspend (DevToolsMessage) -> Unit) {
        messageHandler.value = handler
    }

    actual suspend fun disconnect() {
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
    }
}
