package io.github.syrou.reaktiv.devtools.server

import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Manages connected clients and their WebSocket sessions.
 */
class ClientManager {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, ConnectedClient>()
    private val subscriptions = mutableMapOf<String, MutableSet<String>>()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Registers a new client connection.
     */
    suspend fun registerClient(
        session: WebSocketSession,
        registration: DevToolsMessage.ClientRegistration
    ) = mutex.withLock {
        val clientInfo = ClientInfo(
            clientId = registration.clientId,
            clientName = registration.clientName,
            platform = registration.platform,
            role = ClientRole.UNASSIGNED,
            publisherClientId = null,
            connectedAt = Clock.System.now().toEpochMilliseconds()
        )

        clients[registration.clientId] = ConnectedClient(
            session = session,
            info = clientInfo
        )

        println("DevTools Server: Client registered - ${registration.clientName} (${registration.platform})")

        broadcastClientList()
    }

    /**
     * Unregisters a client and removes all subscriptions.
     */
    suspend fun unregisterClient(clientId: String) = mutex.withLock {
        val client = clients.remove(clientId)

        if (client?.info?.role == ClientRole.SUBSCRIBER || client?.info?.role == ClientRole.ORCHESTRATOR) {
            subscriptions[client.info.publisherClientId]?.remove(clientId)
        }

        subscriptions.values.forEach { listeners ->
            listeners.remove(clientId)
        }

        if (client != null) {
            println("DevTools Server: Client disconnected - ${client.info.clientName}")
            broadcastClientList()
        }
    }

    /**
     * Assigns a role to a client.
     */
    suspend fun assignRole(
        clientId: String,
        role: ClientRole,
        publisherClientId: String?
    ) = mutex.withLock {
        val client = clients[clientId] ?: return@withLock

        if ((client.info.role == ClientRole.SUBSCRIBER || client.info.role == ClientRole.ORCHESTRATOR) && client.info.publisherClientId != null) {
            subscriptions[client.info.publisherClientId]?.remove(clientId)
        }

        client.info = client.info.copy(
            role = role,
            publisherClientId = publisherClientId
        )

        if ((role == ClientRole.SUBSCRIBER || role == ClientRole.ORCHESTRATOR) && publisherClientId != null) {
            subscriptions.getOrPut(publisherClientId) { mutableSetOf() }.add(clientId)
        }

        val message = DevToolsMessage.RoleAssignment(
            targetClientId = clientId,
            role = role,
            publisherClientId = publisherClientId
        )

        sendToClient(clientId, message)

        println("DevTools Server: Assigned role $role to ${client.info.clientName}")

        broadcastClientList()
    }

    /**
     * Broadcasts a message to all listeners of a publisher.
     */
    suspend fun broadcastToListeners(publisherId: String, message: DevToolsMessage) = mutex.withLock {
        val listeners = subscriptions[publisherId] ?: emptySet()

        listeners.forEach { listenerId ->
            sendToClient(listenerId, message)
        }
    }

    /**
     * Broadcasts the current client list to all connected clients.
     */
    suspend fun broadcastClientList() {
        val clientList = clients.values.map { it.info }
        val message = DevToolsMessage.ClientListUpdate(clientList)

        clients.keys.forEach { clientId ->
            sendToClient(clientId, message)
        }
    }

    /**
     * Sends a message to a specific client.
     */
    private suspend fun sendToClient(clientId: String, message: DevToolsMessage) {
        val client = clients[clientId] ?: return

        try {
            val jsonString = json.encodeToString(message)
            client.session.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            println("DevTools Server: Failed to send message to $clientId - ${e.message}")
        }
    }

    /**
     * Gets information about a specific client.
     */
    suspend fun getClient(clientId: String): ClientInfo? = mutex.withLock {
        clients[clientId]?.info
    }

    /**
     * Gets all connected clients.
     */
    suspend fun getAllClients(): List<ClientInfo> = mutex.withLock {
        clients.values.map { it.info }
    }
}

/**
 * Represents a connected client with their session and info.
 */
data class ConnectedClient(
    val session: WebSocketSession,
    var info: ClientInfo
)
