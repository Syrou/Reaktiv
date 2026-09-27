package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class EchoService : ToolingService {
    override val name = "echo"

    override suspend fun start(context: ToolingServiceContext) = Unit

    override suspend fun stop() = Unit

    override suspend fun onRequest(request: String, payload: JsonElement): JsonElement? = when (request) {
        "echo" -> payload
        "fail" -> throw IllegalStateException("echo broke")
        else -> null
    }
}

@OptIn(ExperimentalReaktivApi::class)
class ServiceRequestE2ETest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val stores = mutableListOf<Store>()
    private val connections = mutableListOf<DevToolsConnection>()

    @BeforeTest
    fun startServer() = runBlocking {
        server = DevToolsServer.startEmbedded(port = 0)
        serverPort = server.port()
    }

    @AfterTest
    fun stopServer() {
        connections.forEach { runCatching { runBlocking { it.disconnect() } } }
        stores.forEach { runCatching { it.cleanup() } }
        server.stop()
    }

    private suspend fun publisher(allowRemoteRequests: Boolean = true): Store {
        val store = createStore {
            module(
                createToolingModule(
                    IntrospectionConfig(
                        clientId = "phone",
                        clientName = "phone",
                        platform = "JVM",
                        installLogicTracing = false,
                        installStallWatchdog = false,
                        installCrashHandler = false
                    ),
                    PlatformContext()
                ) {
                    install(
                        DevToolsService(
                            DevToolsConfig(
                                serverUrl = "ws://127.0.0.1:$serverPort/ws",
                                autoReconnect = false,
                                defaultRole = ClientRole.PUBLISHER,
                                allowRemoteRequests = allowRemoteRequests
                            )
                        )
                    )
                    install(EchoService())
                }
            )
            coroutineContext(Dispatchers.Default)
        }.also(stores::add)
        val publishing = withTimeoutOrNull(20_000) {
            store.selectState<ToolingState>().first { it.services["devtools"]?.detail?.contains("publishing") == true }
        }
        assertNotNull(publishing, "the publisher never started publishing")
        return store
    }

    private suspend fun orchestrator(): Pair<DevToolsConnection, Channel<DevToolsMessage.ServiceReply>> {
        val replies = Channel<DevToolsMessage.ServiceReply>(Channel.UNLIMITED)
        val attached = CompletableDeferred<Unit>()
        val connection = DevToolsConnection("ws://127.0.0.1:$serverPort/ws").also(connections::add)
        connection.observeMessages { message ->
            when {
                message is DevToolsMessage.ServiceReply -> replies.send(message)
                message is DevToolsMessage.RoleAssignment && message.targetClientId == "ui" &&
                    message.publisherClientId == "phone" -> attached.complete(Unit)
            }
        }
        connection.connect("ui", "ui", "JVM")
        connection.send(DevToolsMessage.RoleAssignment(targetClientId = "ui", role = ClientRole.ORCHESTRATOR))
        assertNotNull(withTimeoutOrNull(20_000) { attached.await() }, "the orchestrator was never attached to the publisher")
        return connection to replies
    }

    private suspend fun ask(
        connection: DevToolsConnection,
        replies: Channel<DevToolsMessage.ServiceReply>,
        service: String,
        request: String,
        payload: JsonElement = JsonPrimitive("hello")
    ): DevToolsMessage.ServiceReply {
        val requestId = "req-$request-$service"
        connection.send(DevToolsMessage.ServiceRequest("phone", requestId, service, request, payload))
        val reply = withTimeoutOrNull(20_000) {
            var next = replies.receive()
            while (next.requestId != requestId) next = replies.receive()
            next
        }
        return assertNotNull(reply, "no reply to $request")
    }

    @Test
    fun `an orchestrator request reaches the named service and its answer comes back`() = runBlocking {
        publisher()
        val (connection, replies) = orchestrator()

        val reply = ask(connection, replies, "echo", "echo", JsonPrimitive("route map"))

        assertEquals(JsonPrimitive("route map"), reply.result)
        assertNull(reply.error)
        assertEquals("phone", reply.clientId)
    }

    @Test
    fun `an unknown service or request is answered with an error`() = runBlocking {
        publisher()
        val (connection, replies) = orchestrator()

        val noService = ask(connection, replies, "missing", "echo")
        val noRequest = ask(connection, replies, "echo", "dance")

        assertEquals("No tooling service named 'missing' on this device", noService.error)
        assertEquals("'echo' does not handle 'dance'", noRequest.error)
    }

    @Test
    fun `a failing service reports the failure instead of going silent`() = runBlocking {
        publisher()
        val (connection, replies) = orchestrator()

        assertEquals("echo broke", ask(connection, replies, "echo", "fail").error)
    }

    @Test
    fun `a device that turned remote requests off refuses them`() = runBlocking {
        publisher(allowRemoteRequests = false)
        val (connection, replies) = orchestrator()

        val reply = ask(connection, replies, "echo", "echo")

        assertEquals("Remote requests are turned off on this device", reply.error)
        assertNull(reply.result)
    }
}
