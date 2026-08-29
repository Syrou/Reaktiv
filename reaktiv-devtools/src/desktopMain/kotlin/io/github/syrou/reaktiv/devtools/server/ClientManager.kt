package io.github.syrou.reaktiv.devtools.server

import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.json.Json

/**
 * Represents a ghost device imported from a recorded session.
 */
public data class GhostDevice(
    val ghostClientId: String,
    val originalClientInfo: ClientInfo,
    val sessionStartTime: Long,
    val sessionEndTime: Long,
    val eventCount: Int = 0,
    val logicEventCount: Int = 0,
    val sessionExportJson: String? = null
)

public class ClientManager {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, ConnectedClient>()
    private val outbound = mutableMapOf<String, Outbound>()
    private val subscriptions = mutableMapOf<String, MutableSet<String>>()
    private val ghostDevices = mutableMapOf<String, GhostDevice>()
    private var currentPublisherId: String? = null

    private val json = reaktivJson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Outbound(val queue: Channel<DevToolsMessage>, val writer: Job)

    /**
     * Drops all clients, subscriptions, ghosts and the current publisher assignment.
     *
     * Only useful for a host process that runs more than one server over its lifetime, since
     * [DevToolsServer] is an object and would otherwise carry state between them.
     */
    public suspend fun reset(): Unit = mutex.withLock {
        outbound.keys.toList().forEach(::closeOutbound)
        clients.clear()
        subscriptions.clear()
        ghostDevices.clear()
        currentPublisherId = null
    }

    /**
     * Registers a new client connection.
     */
    public suspend fun registerClient(
        session: WebSocketSession,
        registration: DevToolsMessage.ClientRegistration
    ): Unit = mutex.withLock {
        closeOutbound(registration.clientId)
        clients[registration.clientId] = ConnectedClient(
            session = session,
            info = ClientInfo(
                clientId = registration.clientId,
                clientName = registration.clientName,
                platform = registration.platform,
                role = ClientRole.UNASSIGNED,
                publisherClientId = null,
                connectedAt = currentTimeMillis()
            )
        )
        openOutbound(registration.clientId, session)

        println("DevTools Server: Client registered - ${registration.clientName} (${registration.platform})")

        broadcastClientList()
    }

    /**
     * Sends a ghost's session export to a single client, in answer to a request.
     */
    public suspend fun sendGhostSession(requesterId: String, ghostId: String): Unit = mutex.withLock {
        val payload = ghostDevices[ghostId]?.sessionExportJson ?: return@withLock
        enqueue(
            requesterId,
            DevToolsMessage.GhostSessionRestore(
                ghostClientId = ghostId,
                sessionExportJson = payload
            )
        )
        println("DevTools Server: Sent ghost session for $ghostId to $requesterId")
    }

    /**
     * Unregisters a client and removes all subscriptions.
     */
    public suspend fun unregisterClient(clientId: String): Unit = mutex.withLock {
        val client = clients.remove(clientId) ?: return@withLock
        closeOutbound(clientId)

        if (client.info.role == ClientRole.LISTENER || client.info.role == ClientRole.ORCHESTRATOR) {
            subscriptions[client.info.publisherClientId]?.remove(clientId)
        }
        subscriptions.values.forEach { listeners -> listeners.remove(clientId) }

        if (currentPublisherId == clientId) {
            currentPublisherId = null
            println("DevTools Server: Publisher disconnected - $clientId")
            broadcastPublisherChanged(null, clientId, "Publisher disconnected")
        }

        println("DevTools Server: Client disconnected - ${client.info.clientName}")
        broadcastClientList()
    }

    /**
     * Assigns a role to a client.
     */
    public suspend fun assignRole(
        clientId: String,
        role: ClientRole,
        publisherClientId: String?
    ): Unit = mutex.withLock {
        val client = clients[clientId] ?: return@withLock

        if ((client.info.role == ClientRole.LISTENER || client.info.role == ClientRole.ORCHESTRATOR) &&
            client.info.publisherClientId != null
        ) {
            subscriptions[client.info.publisherClientId]?.remove(clientId)
        }

        clients[clientId] = client.copy(
            info = client.info.copy(role = role, publisherClientId = publisherClientId)
        )

        if ((role == ClientRole.LISTENER || role == ClientRole.ORCHESTRATOR) && publisherClientId != null) {
            subscriptions.getOrPut(publisherClientId) { mutableSetOf() }.add(clientId)
        }

        enqueue(
            clientId,
            DevToolsMessage.RoleAssignment(
                targetClientId = clientId,
                role = role,
                publisherClientId = publisherClientId
            )
        )

        println("DevTools Server: Assigned role $role to ${client.info.clientName}")

        broadcastClientList()
    }

    /**
     * Links every observer that has no publisher to the current one and returns those newly
     * linked, so each can be sent a baseline.
     *
     * Role assignments arrive concurrently, so a listener and a publisher registering at the
     * same time can interleave such that neither sees the other: the listener is assigned while
     * there is still no publisher, and the publisher runs its auto-attach before the listener's
     * role has been recorded. Running this after every assignment makes the linkage
     * self-healing, since whichever assignment lands last completes it.
     *
     * @return the observers linked by this call, empty when there was nothing to do
     */
    public suspend fun attachWaitingObservers(): List<Pair<String, ClientRole>> = mutex.withLock {
        val publisherId = currentPublisherId ?: return@withLock emptyList()
        val attached = mutableListOf<Pair<String, ClientRole>>()

        clients.values.toList().forEach { connectedClient ->
            val info = connectedClient.info
            val isObserver = info.role == ClientRole.LISTENER || info.role == ClientRole.ORCHESTRATOR
            if (isObserver && info.publisherClientId == null && info.clientId != publisherId) {
                clients[info.clientId] = connectedClient.copy(
                    info = info.copy(publisherClientId = publisherId)
                )
                subscriptions.getOrPut(publisherId) { mutableSetOf() }.add(info.clientId)
                enqueue(
                    info.clientId,
                    DevToolsMessage.RoleAssignment(
                        targetClientId = info.clientId,
                        role = info.role,
                        publisherClientId = publisherId
                    )
                )
                attached.add(info.clientId to info.role)
                println("DevTools Server: Attached waiting ${info.role} ${info.clientId} to $publisherId")
            }
        }
        attached
    }

    /**
     * The current publisher, or null when none is assigned.
     */
    public suspend fun currentPublisher(): String? = mutex.withLock { currentPublisherId }

    /**
     * Broadcasts a message to every connected orchestrator.
     *
     * Client status is not tied to a publisher subscription: a follower reporting that it cannot
     * replicate needs to reach the UI regardless of which publisher, if any, it is following.
     */
    public suspend fun broadcastToOrchestrators(message: DevToolsMessage): Unit = mutex.withLock {
        clients.values
            .filter { it.info.role == ClientRole.ORCHESTRATOR }
            .forEach { enqueue(it.info.clientId, message) }
    }

    /**
     * Broadcasts a message to all listeners of a publisher.
     */
    public suspend fun broadcastToListeners(publisherId: String, message: DevToolsMessage): Unit = mutex.withLock {
        (subscriptions[publisherId] ?: emptySet()).forEach { enqueue(it, message) }
    }

    /**
     * Broadcasts to the orchestrators subscribed to a publisher, skipping its listeners.
     *
     * Observability payloads such as network and log batches are only rendered by the UI. A
     * listener replicates state and discards them, so delivering them there costs bandwidth on
     * every attached device and, for a large batch, can exceed a platform websocket message limit.
     */
    public suspend fun broadcastToObservers(publisherId: String, message: DevToolsMessage): Unit = mutex.withLock {
        (subscriptions[publisherId] ?: emptySet()).forEach { clientId ->
            if (clients[clientId]?.info?.role == ClientRole.ORCHESTRATOR) {
                enqueue(clientId, message)
            }
        }
    }

    /**
     * Sends a message to the publisher client.
     */
    public suspend fun sendToPublisher(publisherId: String, message: DevToolsMessage): Unit = mutex.withLock {
        enqueue(publisherId, message)
    }

    /**
     * Gets information about a specific client.
     */
    public suspend fun getClient(clientId: String): ClientInfo? = mutex.withLock {
        clients[clientId]?.info
    }

    /**
     * Gets all connected clients including ghost devices.
     */
    public suspend fun getAllClients(): List<ClientInfo> = mutex.withLock { allClientInfos() }

    /**
     * Registers a ghost device from an imported session.
     * Ghost devices can be played back and will broadcast events to listeners.
     */
    public suspend fun registerGhostDevice(
        registration: DevToolsMessage.GhostDeviceRegistration
    ): String = mutex.withLock {
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
    public suspend fun removeGhostDevice(ghostId: String): Unit = mutex.withLock {
        ghostDevices.remove(ghostId) ?: return@withLock
        println("DevTools Server: Ghost device removed - $ghostId")

        subscriptions.remove(ghostId)

        if (currentPublisherId == ghostId) {
            currentPublisherId = null
            broadcastPublisherChanged(null, ghostId, "Ghost device removed")
        }

        broadcastClientList()
    }

    /**
     * Gets a ghost device by ID.
     */
    public suspend fun getGhostDevice(ghostId: String): GhostDevice? = mutex.withLock {
        ghostDevices[ghostId]
    }

    /**
     * Checks if a client ID belongs to a ghost device.
     */
    public suspend fun isGhostDevice(clientId: String): Boolean = mutex.withLock {
        ghostDevices.containsKey(clientId)
    }

    /**
     * Sets the current publisher. Only one publisher is allowed at a time.
     * If the new publisher is a real device and there's a ghost publisher, the ghost is removed.
     */
    public suspend fun setPublisher(clientId: String, reason: String): Unit = mutex.withLock {
        val previousPublisher = currentPublisherId

        if (previousPublisher != null && previousPublisher != clientId) {
            if (ghostDevices.containsKey(previousPublisher) && !ghostDevices.containsKey(clientId)) {
                ghostDevices.remove(previousPublisher)
                subscriptions.remove(previousPublisher)
                println("DevTools Server: Ghost device auto-removed due to real publisher - $previousPublisher")
            } else if (!ghostDevices.containsKey(previousPublisher)) {
                clients[previousPublisher]?.let {
                    clients[previousPublisher] = it.copy(
                        info = it.info.copy(role = ClientRole.UNASSIGNED, publisherClientId = null)
                    )
                }
            }
        }

        currentPublisherId = clientId

        // Linking waiting observers is deliberately not done here. It lives in
        // attachWaitingObservers, which the server calls after every role assignment and whose
        // return value drives the baseline request. Doing it in both places meant whichever ran
        // first linked the observer silently, leaving the other with nothing to report and the
        // observer subscribed but never seeded.

        broadcastPublisherChanged(clientId, previousPublisher, reason)
        broadcastClientList()
    }

    @Deprecated(
        "Duplicate of currentPublisher().",
        ReplaceWith("currentPublisher()"),
        DeprecationLevel.WARNING
    )
    public suspend fun getCurrentPublisher(): String? = currentPublisher()

    private fun allClientInfos(): List<ClientInfo> {
        val ghosts = ghostDevices.values.map { ghost ->
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
        return clients.values.map { it.info } + ghosts
    }

    private fun broadcastClientList() {
        broadcast(DevToolsMessage.ClientListUpdate(allClientInfos()))
    }

    private fun broadcastPublisherChanged(
        newPublisherId: String?,
        previousPublisherId: String?,
        reason: String
    ) {
        broadcast(
            DevToolsMessage.PublisherChanged(
                newPublisherId = newPublisherId,
                previousPublisherId = previousPublisherId,
                reason = reason
            )
        )
    }

    private fun broadcast(message: DevToolsMessage) {
        clients.keys.forEach { enqueue(it, message) }
    }

    private fun enqueue(clientId: String, message: DevToolsMessage) {
        outbound[clientId]?.queue?.trySend(message)
    }

    private fun openOutbound(clientId: String, session: WebSocketSession) {
        val queue = Channel<DevToolsMessage>(Channel.UNLIMITED)
        val writer = scope.launch {
            for (message in queue) {
                try {
                    session.send(Frame.Text(json.encodeToString(message)))
                } catch (e: Exception) {
                    println("DevTools Server: Failed to send message to $clientId - ${e.message}")
                }
            }
        }
        outbound[clientId] = Outbound(queue, writer)
    }

    private fun closeOutbound(clientId: String) {
        outbound.remove(clientId)?.let {
            it.queue.close()
            it.writer.cancel()
        }
    }
}

/**
 * Represents a connected client with their session and info.
 */
public data class ConnectedClient(
    val session: WebSocketSession,
    val info: ClientInfo
)
