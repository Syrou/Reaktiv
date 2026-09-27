package io.github.syrou.reaktiv.devtools.server

import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.protocol.DevToolsProtocol
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.reaktivJson
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
    private val engine: EmbeddedServer<*, *>,
    public val clientManager: ClientManager
) {
    public suspend fun port(): Int = engine.engine.resolvedConnectors().first().port

    public fun stop(gracePeriodMillis: Long = 0, timeoutMillis: Long = 2_000) {
        engine.stop(gracePeriodMillis, timeoutMillis)
    }
}

public object DevToolsServer {
    private var latestClientManager = ClientManager()

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

        val clientManager = ClientManager().also { latestClientManager = it }
        try {
            embeddedServer(CIO, port = port, host = host) {
                configureServer(clientManager, uiPath)
            }.start(wait = true)
        } catch (e: Throwable) {
            val addressInUse = generateSequence(e) { it.cause }.any {
                it::class.simpleName == "BindException" ||
                    it.message?.contains("Address already in use", ignoreCase = true) == true
            }
            if (addressInUse) {
                println()
                println("DevTools Server: port $port is already in use.")
                println("DevTools Server: another server is probably still running.")
                println("DevTools Server: stop it, or start this one on a different port with")
                println("DevTools Server:   ./gradlew :reaktiv-devtools:runDevToolsServer -Pport=8081")
                println("DevTools Server: and point the client's serverUrl at that port.")
                return
            }
            throw e
        }
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
        val clientManager = ClientManager().also { latestClientManager = it }
        val engine = embeddedServer(CIO, port = port, host = host) {
            configureServer(clientManager, uiPath)
        }
        engine.start(wait = false)
        return RunningDevToolsServer(engine, clientManager)
    }

    @Deprecated(
        "Every started server owns its own client bookkeeping, so there is nothing shared to reset.",
        level = DeprecationLevel.WARNING
    )
    public suspend fun resetState() {
        @Suppress("DEPRECATION")
        latestClientManager.reset()
    }

    private fun Application.configureServer(clientManager: ClientManager, uiPath: String?) {
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
                handleWebSocketConnection(clientManager)
            }

            if (uiPath != null) {
                staticFiles(uiPath)
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.refuse(registration: DevToolsMessage.ClientRegistration) {
        val reason = "${registration.clientName} speaks DevTools protocol ${registration.protocolVersion} and this " +
            "server speaks ${DevToolsProtocol.VERSION}. Use the same Reaktiv version on the device and the server."
        ReaktivDebug.warn("DevTools Server: Refused ${registration.clientId} - $reason")
        send(Frame.Text(json.encodeToString<DevToolsMessage>(DevToolsMessage.RegistrationRefused(reason))))
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "protocol version"))
    }

    private suspend fun DefaultWebSocketServerSession.handleWebSocketConnection(clientManager: ClientManager) {
        var clientId: String? = null
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = try {
                    json.decodeFromString<DevToolsMessage>(frame.readText())
                } catch (e: Exception) {
                    ReaktivDebug.warn("DevTools Server: Failed to parse message - ${e.message}")
                    continue
                }
                if (message is DevToolsMessage.ClientRegistration) {
                    if (message.protocolVersion != DevToolsProtocol.VERSION) {
                        refuse(message)
                        return
                    }
                    clientId = message.clientId
                }
                clientManager.receive(clientId, this, message)
            }
        } catch (e: Exception) {
            ReaktivDebug.warn("DevTools Server: Connection error - ${e.message}")
        } finally {
            clientId?.let { clientManager.unregisterSession(it, this) }
        }
    }

    @Deprecated(
        "Every started server owns its own client bookkeeping. Use the clientManager of the RunningDevToolsServer you started.",
        level = DeprecationLevel.WARNING
    )
    public fun getClientManager(): ClientManager = latestClientManager
}
