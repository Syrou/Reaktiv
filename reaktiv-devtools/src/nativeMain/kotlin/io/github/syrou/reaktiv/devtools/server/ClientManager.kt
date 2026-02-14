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
 * Represents a ghost device imported from a recorded session.
 */
data class GhostDevice(
    val ghostClientId: String,
    val originalClientInfo: ClientInfo,
    val sessionStartTime: Long,
    val sessionEndTime: Long,
    val eventCount: Int = 0,
    val logicEventCount: Int = 0,
    val sessionExportJson: String? = null
)

/**
 * Manages connected clients and their WebSocket sessions.
 */
class ClientManager {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, ConnectedClient>()
    private val subscriptions = mutableMapOf<String, MutableSet<String>>()
    private val ghostDevices = mutableMapOf<String, GhostDevice>()
    private var currentPublisherId: String? = null

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

        // Send stored ghost session data to the newly connected client
        ghostDevices.values.forEach { ghost ->
            if (ghost.sessionExportJson != null) {
                val restoreMessage = DevToolsMessage.GhostSessionRestore(
                    ghostClientId = ghost.ghostClientId,
                    sessionExportJson = ghost.sessionExportJson
                )
                sendToClient(registration.clientId, restoreMessage)
                println("DevTools Server: Sent ghost session restore to ${registration.clientName} for ${ghost.ghostClientId}")
            }
        }
    }

    /**
     * Unregisters a client and removes all subscriptions.
     */
    suspend fun unregisterClient(clientId: String) = mutex.withLock {
        val client = clients.remove(clientId)

        if (client?.info?.role == ClientRole.LISTENER || client?.info?.role == ClientRole.ORCHESTRATOR) {
            subscriptions[client.info.publisherClientId]?.remove(clientId)
        }

        subscriptions.values.forEach { listeners ->
            listeners.remove(clientId)
        }

        // Clear publisher tracking if the disconnecting client was the publisher
        if (currentPublisherId == clientId) {
            val previousPublisher = currentPublisherId
            currentPublisherId = null
            println("DevTools Server: Publisher disconnected - $clientId")
            broadcastPublisherChanged(null, previousPublisher, "Publisher disconnected")
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

        if ((client.info.role == ClientRole.LISTENER || client.info.role == ClientRole.ORCHESTRATOR) && client.info.publisherClientId != null) {
            subscriptions[client.info.publisherClientId]?.remove(clientId)
        }

        client.info = client.info.copy(
            role = role,
            publisherClientId = publisherClientId
        )

        if ((role == ClientRole.LISTENER || role == ClientRole.ORCHESTRATOR) && publisherClientId != null) {
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
     * Includes both real clients and ghost devices.
     */
    suspend fun broadcastClientList() {
        val realClients = clients.values.map { it.info }
        val ghostClients = ghostDevices.values.map { ghost ->
            ClientInfo(
                clientId = ghost.ghostClientId,
                clientName = "[Ghost] ${ghost.originalClientInfo.clientName}",
                platform = "${ghost.originalClientInfo.platform} (Recorded)",
                role = if (currentPublisherId == ghost.ghostClientId) ClientRole.PUBLISHER else ClientRole.UNASSIGNED,
                publisherClientId = null,
                connectedAt = ghost.sessionStartTime,
                isGhost = true
            )
        }
        val clientList = realClients + ghostClients
        val message = DevToolsMessage.ClientListUpdate(clientList)

        clients.keys.forEach { clientId ->
            sendToClient(clientId, message)
        }
    }

    /**
     * Sends a message to the publisher client.
     */
    suspend fun sendToPublisher(publisherId: String, message: DevToolsMessage) = mutex.withLock {
        sendToClient(publisherId, message)
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
     * Gets all connected clients including ghost devices.
     */
    suspend fun getAllClients(): List<ClientInfo> = mutex.withLock {
        val realClients = clients.values.map { it.info }
        val ghostClients = ghostDevices.values.map { ghost ->
            ClientInfo(
                clientId = ghost.ghostClientId,
                clientName = "[Ghost] ${ghost.originalClientInfo.clientName}",
                platform = "${ghost.originalClientInfo.platform} (Recorded)",
                role = if (currentPublisherId == ghost.ghostClientId) ClientRole.PUBLISHER else ClientRole.UNASSIGNED,
                publisherClientId = null,
                connectedAt = ghost.sessionStartTime,
                isGhost = true
            )
        }
        realClients + ghostClients
    }

    /**
     * Registers a ghost device from an imported session.
     * Ghost devices can be played back and will broadcast events to listeners.
     */
    suspend fun registerGhostDevice(registration: DevToolsMessage.GhostDeviceRegistration): String = mutex.withLock {
        val ghostId = "ghost-${registration.sessionId}"

        ghostDevices[ghostId] = GhostDevice(
            ghostClientId = ghostId,
            originalClientInfo = registration.originalClientInfo,
            sessionStartTime = registration.sessionStartTime,
            sessionEndTime = registration.sessionEndTime,
            eventCount = registration.eventCount,
            logicEventCount = registration.logicEventCount,
            sessionExportJson = registration.sessionExportJson
        )

        println("DevTools Server: Ghost device registered - $ghostId (${registration.eventCount} events)")

        val previousPublisher = currentPublisherId
        currentPublisherId = ghostId

        broadcastPublisherChanged(ghostId, previousPublisher, "Ghost device imported")
        broadcastClientList()

        return@withLock ghostId
    }

    /**
     * Removes a ghost device.
     */
    suspend fun removeGhostDevice(ghostId: String) = mutex.withLock {
        val removed = ghostDevices.remove(ghostId)
        if (removed != null) {
            println("DevTools Server: Ghost device removed - $ghostId")

            subscriptions.remove(ghostId)

            if (currentPublisherId == ghostId) {
                val previousPublisher = currentPublisherId
                currentPublisherId = null
                broadcastPublisherChanged(null, previousPublisher, "Ghost device removed")
            }

            broadcastClientList()
        }
    }

    /**
     * Gets a ghost device by ID.
     */
    suspend fun getGhostDevice(ghostId: String): GhostDevice? = mutex.withLock {
        ghostDevices[ghostId]
    }

    /**
     * Checks if a client ID belongs to a ghost device.
     */
    suspend fun isGhostDevice(clientId: String): Boolean = mutex.withLock {
        ghostDevices.containsKey(clientId)
    }

    /**
     * Sets the current publisher. Only one publisher is allowed at a time.
     * If the new publisher is a real device and there's a ghost publisher, the ghost is removed.
     */
    suspend fun setPublisher(clientId: String, reason: String) = mutex.withLock {
        val previousPublisher = currentPublisherId

        if (previousPublisher != null && previousPublisher != clientId) {
            if (ghostDevices.containsKey(previousPublisher) && !ghostDevices.containsKey(clientId)) {
                ghostDevices.remove(previousPublisher)
                subscriptions.remove(previousPublisher)
                println("DevTools Server: Ghost device auto-removed due to real publisher - $previousPublisher")
            } else if (!ghostDevices.containsKey(previousPublisher)) {
                clients[previousPublisher]?.let {
                    it.info = it.info.copy(role = ClientRole.UNASSIGNED, publisherClientId = null)
                }
            }
        }

        currentPublisherId = clientId

        // Auto-assign unattached listeners/orchestrators to the new publisher
        clients.values.forEach { connectedClient ->
            val info = connectedClient.info
            if ((info.role == ClientRole.LISTENER || info.role == ClientRole.ORCHESTRATOR) && info.publisherClientId == null) {
                connectedClient.info = info.copy(publisherClientId = clientId)
                subscriptions.getOrPut(clientId) { mutableSetOf() }.add(info.clientId)

                val roleMsg = DevToolsMessage.RoleAssignment(
                    targetClientId = info.clientId,
                    role = info.role,
                    publisherClientId = clientId
                )
                sendToClient(info.clientId, roleMsg)
                println("DevTools Server: Auto-attached ${info.clientName} (${info.role}) to new publisher $clientId")
            }
        }

        broadcastPublisherChanged(clientId, previousPublisher, reason)
        broadcastClientList()
    }

    /**
     * Gets the current publisher ID.
     */
    suspend fun getCurrentPublisher(): String? = mutex.withLock {
        currentPublisherId
    }

    /**
     * Broadcasts a ghost device's events to all listeners.
     */
    suspend fun broadcastGhostEvent(ghostId: String, message: DevToolsMessage) = mutex.withLock {
        val listeners = subscriptions[ghostId] ?: emptySet()
        listeners.forEach { listenerId ->
            sendToClient(listenerId, message)
        }
    }

    /**
     * Broadcasts a publisher changed notification to all clients.
     */
    private suspend fun broadcastPublisherChanged(
        newPublisherId: String?,
        previousPublisherId: String?,
        reason: String
    ) {
        val message = DevToolsMessage.PublisherChanged(
            newPublisherId = newPublisherId,
            previousPublisherId = previousPublisherId,
            reason = reason
        )

        clients.keys.forEach { clientId ->
            sendToClient(clientId, message)
        }
    }
}

/**
 * Represents a connected client with their session and info.
 */
data class ConnectedClient(
    val session: WebSocketSession,
    var info: ClientInfo
)
