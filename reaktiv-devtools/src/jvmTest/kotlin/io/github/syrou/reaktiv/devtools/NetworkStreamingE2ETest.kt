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
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import io.github.syrou.reaktiv.network.ktor.ReaktivNetworkInspection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalReaktivApi::class)
class NetworkStreamingE2ETest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val stores = mutableListOf<Store>()

    @BeforeTest
    fun startServer() = runBlocking {
        DevToolsServer.resetState()
        server = DevToolsServer.startEmbedded(port = 0)
        serverPort = server.port()
    }

    @AfterTest
    fun stopServer() {
        stores.forEach { runCatching { it.cleanup() } }
        stores.clear()
        server.stop()
    }

    private fun buildPublisher(clientId: String): Store {
        val store = createStore {
            module(
                createToolingModule(
                    IntrospectionConfig(
                        clientId = clientId,
                        clientName = clientId,
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
                                autoConnect = true,
                                autoReconnect = false,
                                defaultRole = ClientRole.PUBLISHER
                            )
                        )
                    )
                }
            )
        }
        stores.add(store)
        return store
    }

    private fun largeBodyClient(body: String): HttpClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(engine) {
            install(ReaktivNetworkInspection) {
                maxBodyBytes = 8 * 1024
            }
        }
    }

    @Test
    fun `an orchestrator can stream a truncated body back in chunks`() = runBlocking {
        val publisher = buildPublisher("body-publisher")
        awaitTooling(publisher, "publishing")

        val ui = DevToolsConnection("ws://127.0.0.1:$serverPort/ws")
        val captures = ConcurrentLinkedQueue<NetworkRequestCapture>()
        val chunks = ConcurrentLinkedQueue<DevToolsMessage.NetworkBodyChunk>()
        ui.observeMessages { message ->
            when (message) {
                is DevToolsMessage.NetworkBatch -> captures.addAll(message.events)
                is DevToolsMessage.NetworkBodyChunk -> chunks.add(message)
                else -> Unit
            }
        }
        ui.connect("body-ui", "body-ui", "JVM")
        ui.send(
            DevToolsMessage.RoleAssignment(
                targetClientId = "body-ui",
                role = ClientRole.ORCHESTRATOR,
                publisherClientId = null
            )
        )

        val fullBody = buildString {
            append("""{"items":[""")
            repeat(4000) { index ->
                if (index > 0) append(',')
                append("""{"index":$index,"label":"row-$index","note":"pärlor och räksmörgås"}""")
            }
            append("]}")
        }
        val client = largeBodyClient(fullBody)
        client.get("https://api.example.com/large")

        val captured = awaitCapture(captures, "the large response to reach the orchestrator") {
            it.url.endsWith("/large")
        }
        assertTrue(
            captured.responseBodyTruncated,
            "The inline capture must be truncated, otherwise this test proves nothing"
        )
        assertTrue(
            captured.responseBody!!.length < fullBody.length,
            "The inline preview must be shorter than the real body"
        )

        val assembled = StringBuilder()
        var offset = 0
        var guard = 0
        while (guard++ < 500) {
            ui.send(
                DevToolsMessage.FetchNetworkBody(
                    targetClientId = "body-publisher",
                    requestId = captured.id,
                    part = NetworkBodyPart.RESPONSE,
                    offset = offset,
                    maxBytes = 16 * 1024
                )
            )
            val chunk = awaitChunk(chunks, offset)
            assertTrue(chunk.available, "The device must still retain the body")
            assembled.append(chunk.content)
            if (chunk.isLast) break
            assertTrue(chunk.nextOffset > offset, "Every chunk must advance the offset")
            offset = chunk.nextOffset
        }

        assertEquals(fullBody, assembled.toString(), "The streamed body must reassemble exactly")
        assertTrue(
            runCatching { Json.parseToJsonElement(assembled.toString()) }.isSuccess,
            "The reassembled body must parse as JSON"
        )
        assertTrue(chunks.size > 1, "A body this size must arrive in more than one chunk")

        ui.disconnect()
        client.close()
    }

    @Test
    fun `an orchestrator can drop a marker on a publisher and see it come back`() = runBlocking {
        val publisher = buildPublisher("marker-publisher")
        awaitTooling(publisher, "publishing")

        val ui = DevToolsConnection("ws://127.0.0.1:$serverPort/ws")
        val inbound = ConcurrentLinkedQueue<DevToolsMessage>()
        ui.observeMessages { inbound.add(it) }
        ui.connect("marker-ui", "marker-ui", "JVM")
        ui.send(
            DevToolsMessage.RoleAssignment(
                targetClientId = "marker-ui",
                role = ClientRole.ORCHESTRATOR,
                publisherClientId = null
            )
        )
        kotlinx.coroutines.delay(1500)

        ui.send(
            DevToolsMessage.AddMarkerRequest(
                targetClientId = "marker-publisher",
                label = "before-the-bug",
                note = "dropped from the orchestrator"
            )
        )

        val relayed = awaitMessage(inbound, "the marker to come back") {
            it is DevToolsMessage.MarkerAdded && it.marker.label == "before-the-bug"
        } as DevToolsMessage.MarkerAdded

        assertEquals("marker-publisher", relayed.clientId)
        assertEquals("dropped from the orchestrator", relayed.marker.note)
        assertEquals("remote", relayed.marker.source)

        ui.disconnect()
    }

    private suspend fun awaitChunk(
        chunks: ConcurrentLinkedQueue<DevToolsMessage.NetworkBodyChunk>,
        offset: Int
    ): DevToolsMessage.NetworkBodyChunk {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            chunks.firstOrNull { it.offset == offset }?.let { return it }
            kotlinx.coroutines.delay(25)
        }
        throw AssertionError("Timed out waiting for a body chunk at offset $offset")
    }

    private suspend fun awaitMessage(
        inbound: ConcurrentLinkedQueue<DevToolsMessage>,
        description: String,
        predicate: (DevToolsMessage) -> Boolean
    ): DevToolsMessage {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            inbound.firstOrNull(predicate)?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("Timed out waiting for $description. Seen: $inbound")
    }

    private suspend fun awaitTooling(store: Store, detail: String) {
        val reached = withTimeoutOrNull(20_000) {
            store.selectState<ToolingState>().first {
                it.services["devtools"]?.detail?.contains(detail) == true
            }
        }
        assertNotNull(reached, "Timed out waiting for publisher to report $detail")
    }

    private suspend fun awaitCapture(
        captures: ConcurrentLinkedQueue<NetworkRequestCapture>,
        description: String,
        predicate: (NetworkRequestCapture) -> Boolean
    ): NetworkRequestCapture {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            captures.firstOrNull(predicate)?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError(
            "Timed out waiting for $description. Captures seen: " +
                captures.map { "${it.method} ${it.url}" }
        )
    }
}
