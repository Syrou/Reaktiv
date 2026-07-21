package io.github.syrou.reaktiv.devtools.server

import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * DevTools server for handling client connections and state synchronization.
 *
 * Usage:
 * ```kotlin
 * fun main() {
 *     DevToolsServer.start(port = 8080)
 * }
 * ```
 */
/**
 * Handle to a server started with [DevToolsServer.startEmbedded].
 *
 * Usage:
 * ```kotlin
 * val server = DevToolsServer.startEmbedded(port = 0)
 * val url = "ws://127.0.0.1:${server.port}/ws"
 * server.stop()
 * ```
 */
public class RunningDevToolsServer internal constructor(
    private val engine: EmbeddedServer<*, *>
) {
    /**
     * The port the engine actually bound to, resolved even when 0 was requested.
     */
    public val port: Int by lazy {
        runBlocking { engine.engine.resolvedConnectors().first().port }
    }

    public fun stop(gracePeriodMillis: Long = 0, timeoutMillis: Long = 2_000) {
        engine.stop(gracePeriodMillis, timeoutMillis)
    }
}

public object DevToolsServer {
    private val clientManager = ClientManager()

    private val json = reaktivJson()

    /**
     * Starts the DevTools server.
     *
     * @param port Port to listen on (default: 8080)
     * @param host Host address (default: 0.0.0.0)
     * @param uiPath Path to the WASM UI distribution directory (optional)
     */
    public fun start(port: Int = 8080, host: String = "0.0.0.0", uiPath: String? = null) {
        println("DevTools Server: Starting on http://$host:$port")
        println("DevTools Server: WebSocket endpoint at ws://$host:$port/ws")

        if (uiPath != null) {
            println("DevTools Server: UI will be available at http://$host:$port")
            println("DevTools Server: Serving UI from: $uiPath")
        } else {
            println("DevTools Server: No UI path provided, WebSocket only")
        }

        embeddedServer(CIO, port = port, host = host) {
            configureServer(uiPath)
        }.start(wait = true)
    }

    /**
     * Starts the DevTools server without blocking the calling thread.
     *
     * Intended for embedding the server in a host process and for tests that need to drive
     * real clients against it. Unlike [start], this returns as soon as the engine is up.
     *
     * Usage:
     * ```kotlin
     * val server = DevToolsServer.startEmbedded(port = 0)
     * try {
     *     // drive clients against server.port
     * } finally {
     *     server.stop()
     * }
     * ```
     *
     * @param port Port to listen on, or 0 to let the OS choose a free one
     * @param host Host address (default: 127.0.0.1)
     * @param uiPath Path to the WASM UI distribution directory (optional)
     * @return A handle exposing the resolved [RunningDevToolsServer.port] and [RunningDevToolsServer.stop]
     */
    public fun startEmbedded(
        port: Int = 8080,
        host: String = "127.0.0.1",
        uiPath: String? = null
    ): RunningDevToolsServer {
        val engine = embeddedServer(CIO, port = port, host = host) {
            configureServer(uiPath)
        }
        engine.start(wait = false)
        return RunningDevToolsServer(engine)
    }

    /**
     * Resets all client bookkeeping.
     *
     * The server is an object, so a host process that starts more than one embedded server
     * over its lifetime (notably a test suite) would otherwise inherit stale clients and
     * publisher assignments from the previous one.
     */
    public fun resetState() {
        clientManager.reset()
    }

    private fun Application.configureServer(uiPath: String?) {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        install(ContentNegotiation) {
            json(json)
        }

        routing {
            webSocket("/ws") {
                handleWebSocketConnection()
            }

            if (uiPath != null) {
                staticFiles(uiPath)
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleWebSocketConnection() {
        var clientId: String? = null
        val remoteAddress = try {
            call.request.local.remoteAddress
        } catch (e: Exception) {
            "unknown"
        }

        println("DevTools Server: New WebSocket connection from $remoteAddress")

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    println("DevTools Server: Received message: ${text.take(200)}...")

                    try {
                        val message = json.decodeFromString<DevToolsMessage>(text)
                        println("DevTools Server: Parsed message type: ${message::class.simpleName}")
                        handleMessage(this, message, clientId)

                        if (message is DevToolsMessage.ClientRegistration) {
                            clientId = message.clientId
                            println("DevTools Server: Client registered with ID: $clientId")
                        }
                    } catch (e: Exception) {
                        println("DevTools Server: Failed to parse message - ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            println("DevTools Server: Connection error - ${e.message}")
            e.printStackTrace()
        } finally {
            if (clientId != null) {
                println("DevTools Server: Client disconnected: $clientId")
                clientManager.unregisterClient(clientId)
            } else {
                println("DevTools Server: Unknown client disconnected")
            }
        }
    }

    private suspend fun handleMessage(
        session: WebSocketSession,
        message: DevToolsMessage,
        currentClientId: String?
    ) {
        when (message) {
            is DevToolsMessage.ClientRegistration -> {
                clientManager.registerClient(session, message)
            }

            is DevToolsMessage.ActionDispatched -> {
                println("DevTools Server: Action from ${message.clientId} - ${message.event.actionType}")
                clientManager.broadcastToListeners(message.clientId, message)

                if (message.event.deltaKind != DeltaKind.FULL) return
                val stateSync = DevToolsMessage.StateSync(
                    fromClientId = message.clientId,
                    timestamp = message.event.timestamp,
                    stateJson = message.event.stateDeltaJson,
                    moduleName = message.event.moduleName
                )
                clientManager.broadcastToListeners(message.clientId, stateSync)
            }

            is DevToolsMessage.StateSync -> {
                println("DevTools Server: StateSync from ${message.fromClientId}")
                clientManager.broadcastToListeners(message.fromClientId, message)
            }


            is DevToolsMessage.RoleAssignment -> {
                println("DevTools Server: Role assignment request - ${message.role} for ${message.targetClientId}")

                // currentPublisher covers both real devices and ghost devices
                val currentPublisher = clientManager.getCurrentPublisher()
                val senderRole = if (currentClientId != null) clientManager.getClient(currentClientId)?.role else null
                val isFromOrchestrator = senderRole == ClientRole.ORCHESTRATOR
                val effectiveRole: ClientRole
                val effectivePublisherId: String?

                when (message.role) {
                    ClientRole.PUBLISHER -> {
                        if (currentPublisher == null || isFromOrchestrator) {
                            // First publisher wins, or orchestrator can reassign
                            effectiveRole = ClientRole.PUBLISHER
                            effectivePublisherId = null
                            clientManager.setPublisher(message.targetClientId, "Role assignment request")
                        } else {
                            // A publisher already exists and sender is not orchestrator — demote to UNASSIGNED
                            effectiveRole = ClientRole.UNASSIGNED
                            effectivePublisherId = null
                            println("DevTools Server: Publisher already exists ($currentPublisher), ${message.targetClientId} remains UNASSIGNED")
                        }
                    }

                    ClientRole.LISTENER -> {
                        effectiveRole = ClientRole.LISTENER
                        effectivePublisherId = message.publisherClientId ?: currentPublisher
                    }

                    ClientRole.ORCHESTRATOR -> {
                        effectiveRole = ClientRole.ORCHESTRATOR
                        effectivePublisherId = message.publisherClientId ?: currentPublisher
                    }

                    ClientRole.UNASSIGNED -> {
                        effectiveRole = ClientRole.UNASSIGNED
                        effectivePublisherId = null
                    }
                }

                clientManager.assignRole(
                    clientId = message.targetClientId,
                    role = effectiveRole,
                    publisherClientId = effectivePublisherId
                )

                // Notify about a new observer so it can be given a baseline. Orchestrators need
                // this as much as listeners: without it the UI has no initial state to
                // reconstruct the full application state from, and can only show deltas.
                val isObserver =
                    effectiveRole == ClientRole.LISTENER || effectiveRole == ClientRole.ORCHESTRATOR
                if (isObserver && effectivePublisherId != null) {
                    val notification = DevToolsMessage.ListenerAttached(
                        listenerId = message.targetClientId,
                        role = effectiveRole
                    )
                    if (clientManager.isGhostDevice(effectivePublisherId)) {
                        // Ghost can't respond — notify orchestrator/subscribers so they can send state
                        clientManager.broadcastToListeners(effectivePublisherId, notification)
                        println("DevTools Server: Notified ghost subscribers of new listener ${message.targetClientId}")
                    } else {
                        clientManager.sendToPublisher(effectivePublisherId, notification)
                        println("DevTools Server: Notified publisher $effectivePublisherId of new listener ${message.targetClientId}")
                    }
                }

                // Link anyone who was waiting without a publisher and give each a baseline.
                // Runs after every assignment because the two can interleave: a listener may be
                // assigned while no publisher exists yet, and the publisher may run before the
                // listener's role is recorded. It also has to run after assignRole, since a
                // publisher ignores an attach notification until it knows it is the publisher.
                val nowAttached = clientManager.attachWaitingObservers()
                if (nowAttached.isNotEmpty()) {
                    val publisherId = clientManager.currentPublisher()
                    if (publisherId != null) {
                        val ghost = clientManager.isGhostDevice(publisherId)
                        nowAttached.forEach { (observerId, role) ->
                            val notification = DevToolsMessage.ListenerAttached(observerId, role)
                            if (ghost) {
                                clientManager.broadcastToListeners(publisherId, notification)
                            } else {
                                clientManager.sendToPublisher(publisherId, notification)
                            }
                            println("DevTools Server: Requested baseline for waiting $observerId")
                        }
                    }
                }
            }

            is DevToolsMessage.ClientStatus -> {
                println("DevTools Server: Status from ${message.clientId} - ${message.status}")
                clientManager.broadcastToOrchestrators(message)
            }

            is DevToolsMessage.RoleAcknowledgment -> {
                println("DevTools Server: Role acknowledged by ${message.clientId} - ${message.role}")
            }

            is DevToolsMessage.LogicMethodStarted -> {
                println("DevTools Server: LogicMethodStarted from ${message.clientId} - ${message.event.logicClass}.${message.event.methodName}")
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.LogicMethodCompleted -> {
                println("DevTools Server: LogicMethodCompleted from ${message.clientId} - ${message.event.callId}")
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.LogicMethodFailed -> {
                println("DevTools Server: LogicMethodFailed from ${message.clientId} - ${message.event.callId}")
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.StateReadReport -> {
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.GhostDeviceRegistration -> {
                println("DevTools Server: Ghost device registration for session ${message.sessionId}")
                val ghostId = clientManager.registerGhostDevice(message)
                println("DevTools Server: Ghost device registered with ID: $ghostId")
            }

            is DevToolsMessage.GhostDeviceRemoval -> {
                println("DevTools Server: Ghost device removal request for ${message.ghostClientId}")
                clientManager.removeGhostDevice(message.ghostClientId)
            }

            is DevToolsMessage.SessionHistorySync -> {
                println("DevTools Server: SessionHistorySync from ${message.clientId} (${message.history.actions.size} actions)")
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.SessionHistoryChunk -> {
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.PublisherChanged -> {
                println("DevTools Server: PublisherChanged - ${message.previousPublisherId} -> ${message.newPublisherId}")
            }

            is DevToolsMessage.CrashReport -> {
                println("DevTools Server: CrashReport from ${message.clientId} - ${message.crash.exception.exceptionType}")
                clientManager.broadcastToListeners(message.clientId, message)
            }

            is DevToolsMessage.ListenerAttached -> {
                // Server-generated, should not be received from clients
            }

            is DevToolsMessage.ClientListUpdate -> {
                println("DevTools Server: ClientListUpdate received (${message.clients.size} clients)")
            }

            is DevToolsMessage.GhostSessionRestore -> {
                // Server-generated, should not be received from clients
            }
        }
    }

    /**
     * Gets the client manager instance.
     */
    public fun getClientManager(): ClientManager = clientManager
}
