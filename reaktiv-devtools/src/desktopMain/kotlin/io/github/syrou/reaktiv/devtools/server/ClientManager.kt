package io.github.syrou.reaktiv.devtools.server

import io.github.syrou.reaktiv.devtools.DevToolsInternalApi

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.util.reaktivJson
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

/**
 * Represents a ghost device imported from a recorded session.
 */
@DevToolsInternalApi
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
    private val ghostDevices = mutableMapOf<String, GhostDevice>()
    private var currentPublisherId: String? = null

    private val json = reaktivJson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Outbound(val queue: Channel<DevToolsMessage>, val writer: Job)

    internal suspend fun receive(
        senderId: String?,
        session: WebSocketSession,
        message: DevToolsMessage
    ): Unit = mutex.withLock {
        when (message) {
            is DevToolsMessage.ClientRegistration -> register(session, message)
            is DevToolsMessage.RoleAssignment -> decideRole(senderId, message)
            is DevToolsMessage.GhostDeviceRegistration -> registerGhost(message)
            is DevToolsMessage.GhostDeviceRemoval -> {
                removeGhost(message.ghostClientId)
                linkWaitingObservers()
            }
            is DevToolsMessage.GhostSessionRequest -> senderId?.let { restoreGhostTo(it, message.ghostClientId) }
            is DevToolsMessage.ClientStatus -> orchestrators().forEach { enqueue(it, message) }
            is DevToolsMessage.Targeted -> enqueue(message.targetClientId, message)
            is DevToolsMessage.ActionDispatched,
            is DevToolsMessage.StateSync,
            is DevToolsMessage.FromClient -> senderId?.let { relay(it, message) }
            else -> ReaktivDebug.general("DevTools Server: Ignored ${message::class.simpleName} from $senderId")
        }
    }

    internal suspend fun unregisterSession(clientId: String, session: WebSocketSession): Unit = mutex.withLock {
        if (clients[clientId]?.session === session) removeClient(clientId)
    }

    /**
     * The current publisher, or null when none is assigned.
     */
    public suspend fun currentPublisher(): String? = mutex.withLock { currentPublisherId }

    /**
     * Gets information about a specific client.
     */
    public suspend fun getClient(clientId: String): ClientInfo? = mutex.withLock {
        clients[clientId]?.info
    }

    private fun register(session: WebSocketSession, registration: DevToolsMessage.ClientRegistration) {
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
        ReaktivDebug.general("DevTools Server: Client registered - ${registration.clientName} (${registration.platform})")
        broadcastClientList()
    }

    private fun removeClient(clientId: String) {
        val client = clients.remove(clientId) ?: return
        closeOutbound(clientId)
        unlinkObserversOf(clientId)

        if (currentPublisherId == clientId) {
            currentPublisherId = null
            ReaktivDebug.general("DevTools Server: Publisher disconnected - $clientId")
            broadcastPublisherChanged(null, clientId, "Publisher disconnected")
        }

        ReaktivDebug.general("DevTools Server: Client disconnected - ${client.info.clientName}")
        broadcastClientList()
    }

    private fun decideRole(senderId: String?, request: DevToolsMessage.RoleAssignment) {
        val target = request.targetClientId
        val publisher = currentPublisherId
        val senderIsOrchestrator = senderId?.let { clients[it]?.info?.role } == ClientRole.ORCHESTRATOR
        val role: ClientRole
        val link: String?
        when (request.role) {
            ClientRole.PUBLISHER -> {
                if (publisher == null || publisher == target || senderIsOrchestrator) {
                    movePublisherTo(target, "Role assignment request")
                    role = ClientRole.PUBLISHER
                } else {
                    ReaktivDebug.general("DevTools Server: Publisher already exists ($publisher), $target remains UNASSIGNED")
                    role = ClientRole.UNASSIGNED
                }
                link = null
            }
            ClientRole.LISTENER, ClientRole.ORCHESTRATOR -> {
                role = request.role
                link = request.publisherClientId ?: publisher
            }
            ClientRole.UNASSIGNED -> {
                role = ClientRole.UNASSIGNED
                link = null
            }
        }
        applyRole(target, role, link)?.let { requestBaseline(it, target, role) }
        linkWaitingObservers()
    }

    private fun applyRole(clientId: String, role: ClientRole, publisherClientId: String?): String? {
        val client = clients[clientId] ?: return null
        if (currentPublisherId == clientId && role != ClientRole.PUBLISHER) {
            currentPublisherId = null
            unlinkObserversOf(clientId)
            broadcastPublisherChanged(null, clientId, "Publisher took the $role role")
        }
        val link = publisherClientId?.takeIf { role.isObserver && it != clientId }
        val previous = client.info
        clients[clientId] = client.copy(info = previous.copy(role = role, publisherClientId = link))

        enqueue(
            clientId,
            DevToolsMessage.RoleAssignment(
                targetClientId = clientId,
                role = role,
                publisherClientId = link
            )
        )

        ReaktivDebug.general("DevTools Server: Assigned role $role to ${previous.clientName}")

        broadcastClientList()
        return link?.takeIf { previous.role != role || previous.publisherClientId != link }
    }

    private fun movePublisherTo(clientId: String, reason: String) {
        val previousPublisher = currentPublisherId

        if (previousPublisher != null && previousPublisher != clientId &&
            ghostDevices.containsKey(previousPublisher) && !ghostDevices.containsKey(clientId)
        ) {
            ghostDevices.remove(previousPublisher)
            unlinkObserversOf(previousPublisher)
            ReaktivDebug.general("DevTools Server: Ghost device auto-removed due to real publisher - $previousPublisher")
        }
        clients.values
            .filter { it.info.role == ClientRole.PUBLISHER && it.info.clientId != clientId }
            .forEach { demoted ->
                val demotedId = demoted.info.clientId
                clients[demotedId] = demoted.copy(
                    info = demoted.info.copy(role = ClientRole.UNASSIGNED, publisherClientId = null)
                )
                unlinkObserversOf(demotedId)
                enqueue(demotedId, DevToolsMessage.RoleAssignment(targetClientId = demotedId, role = ClientRole.UNASSIGNED))
            }

        currentPublisherId = clientId

        broadcastPublisherChanged(clientId, previousPublisher, reason)
        broadcastClientList()
    }

    private fun linkWaitingObservers(): List<Pair<String, ClientRole>> {
        val publisherId = currentPublisherId ?: return emptyList()
        val linked = clients.values.toList().mapNotNull { client ->
            val info = client.info
            if (!info.role.isObserver || info.publisherClientId != null || info.clientId == publisherId) {
                return@mapNotNull null
            }
            clients[info.clientId] = client.copy(info = info.copy(publisherClientId = publisherId))
            enqueue(
                info.clientId,
                DevToolsMessage.RoleAssignment(
                    targetClientId = info.clientId,
                    role = info.role,
                    publisherClientId = publisherId
                )
            )
            ReaktivDebug.general("DevTools Server: Attached waiting ${info.role} ${info.clientId} to $publisherId")
            info.clientId to info.role
        }
        linked.forEach { (observerId, role) -> requestBaseline(publisherId, observerId, role) }
        return linked
    }

    private fun requestBaseline(publisherId: String, observerId: String, role: ClientRole) {
        val notification = DevToolsMessage.ListenerAttached(listenerId = observerId, role = role)
        if (ghostDevices.containsKey(publisherId)) {
            observersOf(publisherId)
                .filter { it.info.role == ClientRole.ORCHESTRATOR }
                .forEach { enqueue(it.info.clientId, notification) }
        } else {
            enqueue(publisherId, notification)
        }
    }

    private fun relay(senderId: String, message: DevToolsMessage) {
        val origin = message.origin ?: return
        if (origin != senderId && !orchestrates(senderId, origin)) {
            ReaktivDebug.warn("DevTools Server: Dropped ${message::class.simpleName} from $senderId sent as $origin")
            return
        }
        val reachesFollowers = message is DevToolsMessage.ActionDispatched || message is DevToolsMessage.StateSync
        observersOf(origin)
            .filter { reachesFollowers || it.info.role == ClientRole.ORCHESTRATOR }
            .forEach { enqueue(it.info.clientId, message) }
    }

    private fun orchestrates(clientId: String, publisherId: String): Boolean =
        clients[clientId]?.info?.let { it.role == ClientRole.ORCHESTRATOR && it.publisherClientId == publisherId } == true

    private fun registerGhost(registration: DevToolsMessage.GhostDeviceRegistration): String {
        val ghostId = "ghost-${registration.sessionId}"

        ghostDevices[ghostId] = GhostDevice(
            ghostClientId = ghostId,
            originalClientInfo = registration.originalClientInfo,
            sessionStartTime = registration.sessionStartTime,
            sessionEndTime = registration.sessionEndTime,
            sessionExportJson = registration.sessionExportJson
        )

        ReaktivDebug.general("DevTools Server: Ghost device registered - $ghostId")

        val previousPublisher = currentPublisherId
        currentPublisherId = ghostId

        broadcastPublisherChanged(ghostId, previousPublisher, "Ghost device imported")
        broadcastClientList()
        return ghostId
    }

    private fun removeGhost(ghostId: String) {
        ghostDevices.remove(ghostId) ?: return
        ReaktivDebug.general("DevTools Server: Ghost device removed - $ghostId")

        unlinkObserversOf(ghostId)

        if (currentPublisherId == ghostId) {
            val displaced = clients.values.firstOrNull { it.info.role == ClientRole.PUBLISHER }?.info?.clientId
            currentPublisherId = displaced
            broadcastPublisherChanged(displaced, ghostId, "Ghost device removed")
        }

        broadcastClientList()
    }

    private fun restoreGhostTo(requesterId: String, ghostId: String) {
        val payload = ghostDevices[ghostId]?.sessionExportJson ?: return
        enqueue(
            requesterId,
            DevToolsMessage.GhostSessionRestore(
                ghostClientId = ghostId,
                sessionExportJson = payload
            )
        )
        ReaktivDebug.general("DevTools Server: Sent ghost session for $ghostId to $requesterId")
    }

    private fun resetLocked() {
        outbound.keys.toList().forEach(::closeOutbound)
        clients.clear()
        ghostDevices.clear()
        currentPublisherId = null
    }

    private fun unlinkObserversOf(publisherId: String) {
        observersOf(publisherId).forEach { observer ->
            clients[observer.info.clientId] = observer.copy(info = observer.info.copy(publisherClientId = null))
        }
    }

    private fun observersOf(publisherId: String): List<ConnectedClient> =
        clients.values.filter { it.info.role.isObserver && it.info.publisherClientId == publisherId }

    private fun orchestrators(): List<String> =
        clients.values.filter { it.info.role == ClientRole.ORCHESTRATOR }.map { it.info.clientId }

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
                    ReaktivDebug.warn("DevTools Server: Failed to send message to $clientId - ${e.message}")
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

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun reset(): Unit = mutex.withLock { resetLocked() }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun registerClient(
        session: WebSocketSession,
        registration: DevToolsMessage.ClientRegistration
    ): Unit = mutex.withLock { register(session, registration) }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun sendGhostSession(requesterId: String, ghostId: String): Unit =
        mutex.withLock { restoreGhostTo(requesterId, ghostId) }

    @Deprecated("Unused by the server. It will become internal.", level = DeprecationLevel.WARNING)
    public suspend fun unregisterClient(clientId: String): Unit = mutex.withLock {
        removeClient(clientId)
    }

    @Deprecated("Unused by the server. It will become internal.", level = DeprecationLevel.WARNING)
    public suspend fun assignRole(
        clientId: String,
        role: ClientRole,
        publisherClientId: String?
    ): Unit = mutex.withLock {
        applyRole(clientId, role, publisherClientId)
    }

    @Deprecated("Unused by the server. It will become internal.", level = DeprecationLevel.WARNING)
    public suspend fun attachWaitingObservers(): List<Pair<String, ClientRole>> =
        mutex.withLock { linkWaitingObservers() }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun broadcastToOrchestrators(message: DevToolsMessage): Unit = mutex.withLock {
        orchestrators().forEach { enqueue(it, message) }
    }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun broadcastToListeners(publisherId: String, message: DevToolsMessage): Unit = mutex.withLock {
        observersOf(publisherId).forEach { enqueue(it.info.clientId, message) }
    }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun broadcastToObservers(publisherId: String, message: DevToolsMessage): Unit = mutex.withLock {
        observersOf(publisherId)
            .filter { it.info.role == ClientRole.ORCHESTRATOR }
            .forEach { enqueue(it.info.clientId, message) }
    }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun sendToPublisher(publisherId: String, message: DevToolsMessage): Unit = mutex.withLock {
        enqueue(publisherId, message)
    }

    @Deprecated("Unused by the server. It will become internal.", level = DeprecationLevel.WARNING)
    public suspend fun getAllClients(): List<ClientInfo> = mutex.withLock { allClientInfos() }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun registerGhostDevice(
        registration: DevToolsMessage.GhostDeviceRegistration
    ): String = mutex.withLock { registerGhost(registration) }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun removeGhostDevice(ghostId: String): Unit = mutex.withLock { removeGhost(ghostId) }

    @Deprecated("Unused by the server. It will become internal.", level = DeprecationLevel.WARNING)
    public suspend fun getGhostDevice(ghostId: String): GhostDevice? = mutex.withLock {
        ghostDevices[ghostId]
    }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun isGhostDevice(clientId: String): Boolean = mutex.withLock {
        ghostDevices.containsKey(clientId)
    }

    @Deprecated(SERVER_INTERNAL, level = DeprecationLevel.WARNING)
    public suspend fun setPublisher(clientId: String, reason: String): Unit =
        mutex.withLock { movePublisherTo(clientId, reason) }

    @Deprecated(
        "Duplicate of currentPublisher().",
        ReplaceWith("currentPublisher()"),
        DeprecationLevel.WARNING
    )
    public suspend fun getCurrentPublisher(): String? = currentPublisher()
}

private const val SERVER_INTERNAL: String =
    "Server bookkeeping. The server routes every message through one decision, so this becomes internal in the next release."

private val ClientRole.isObserver: Boolean
    get() = this == ClientRole.LISTENER || this == ClientRole.ORCHESTRATOR

private val DevToolsMessage.origin: String?
    get() = when (this) {
        is DevToolsMessage.ActionDispatched -> clientId
        is DevToolsMessage.StateSync -> fromClientId
        is DevToolsMessage.FromClient -> clientId
        else -> null
    }

/**
 * Represents a connected client with their session and info.
 */
@DevToolsInternalApi
public data class ConnectedClient(
    val session: WebSocketSession,
    val info: ClientInfo
)
