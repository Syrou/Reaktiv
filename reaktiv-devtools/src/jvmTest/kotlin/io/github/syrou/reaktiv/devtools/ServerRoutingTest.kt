package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.client.devToolsHttpClientEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.github.syrou.reaktiv.devtools.protocol.ClientInfo
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerRoutingTest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val peers = mutableListOf<Peer>()

    @BeforeTest
    fun startServer() = runBlocking<Unit> {
        server = DevToolsServer.startEmbedded(port = 0)
        serverPort = server.port()
    }

    @AfterTest
    fun stopServer() {
        peers.forEach { runCatching { runBlocking { it.connection.disconnect() } } }
        peers.clear()
        server.stop()
    }

    private inner class Peer(val id: String) {
        val connection = DevToolsConnection("ws://127.0.0.1:$serverPort/ws")
        val inbox = Channel<DevToolsMessage>(Channel.UNLIMITED)

        suspend fun open(): Peer {
            peers += this
            connection.observeMessages { inbox.send(it) }
            connection.connect(id, id, "JVM")
            val registered = withTimeoutOrNull(10_000) {
                while (server.clientManager.getClient(id) == null) delay(10)
                true
            }
            assertNotNull(registered, "$id never registered")
            return this
        }

        suspend fun request(role: ClientRole, publisher: String? = null, target: String = id) {
            connection.send(DevToolsMessage.RoleAssignment(targetClientId = target, role = role, publisherClientId = publisher))
        }

        suspend fun <T : Any> await(description: String, match: (DevToolsMessage) -> T?): T {
            val found = withTimeoutOrNull(10_000) {
                var hit: T? = null
                while (hit == null) hit = match(inbox.receive())
                hit
            }
            return assertNotNull(found, "$id never received $description")
        }

        suspend fun awaitRole(role: ClientRole): DevToolsMessage.RoleAssignment =
            await("the $role role") { message ->
                (message as? DevToolsMessage.RoleAssignment)?.takeIf { it.targetClientId == id && it.role == role }
            }

        suspend fun awaitAttachOf(observerId: String): DevToolsMessage.ListenerAttached =
            await("an attach for $observerId") { message ->
                (message as? DevToolsMessage.ListenerAttached)?.takeIf { it.listenerId == observerId }
            }

        suspend fun close() {
            connection.disconnect()
        }
    }

    private suspend fun open(id: String): Peer = Peer(id).open()

    private suspend fun publisher(id: String): Peer = open(id).also {
        it.request(ClientRole.PUBLISHER)
        it.awaitRole(ClientRole.PUBLISHER)
    }

    private suspend fun follower(id: String): Peer = open(id).also {
        it.request(ClientRole.LISTENER)
        it.awaitRole(ClientRole.LISTENER)
    }

    private suspend fun awaitCurrentPublisher(expected: String?) {
        val reached = withTimeoutOrNull(10_000) {
            while (server.clientManager.currentPublisher() != expected) delay(20)
            true
        }
        assertNotNull(reached, "the current publisher never became $expected")
    }

    private fun delta(from: String, type: String, kind: DeltaKind) = CapturedAction(
        clientId = from,
        timestamp = 1L,
        actionType = type,
        actionData = "{}",
        stateDeltaJson = "{\"count\":1}",
        moduleName = "Counter",
        deltaKind = kind
    )

    @Test
    fun `a follower of a publisher that disconnected follows the next publisher`() = runBlocking<Unit> {
        val first = publisher("first")
        val listener = follower("listener")
        first.close()
        awaitCurrentPublisher(null)

        val second = publisher("second")

        second.awaitAttachOf("listener")
        second.connection.send(DevToolsMessage.ActionDispatched(delta("second", "Increment", DeltaKind.FULL)))
        val received = listener.await("the second publisher's delta") { message ->
            (message as? DevToolsMessage.ActionDispatched)?.takeIf { it.clientId == "second" }
        }
        assertEquals("Increment", received.event.actionType)
    }

    @Test
    fun `a follower of a removed ghost follows the next publisher`() = runBlocking<Unit> {
        val ui = open("ui")
        ui.request(ClientRole.ORCHESTRATOR)
        ui.awaitRole(ClientRole.ORCHESTRATOR)
        ui.connection.send(
            DevToolsMessage.GhostDeviceRegistration(
                sessionId = "s1",
                originalClientInfo = ClientInfo(
                    clientId = "recorded",
                    clientName = "Recorded",
                    platform = "JVM",
                    role = ClientRole.UNASSIGNED,
                    publisherClientId = null,
                    connectedAt = 0L,
                    isGhost = true
                ),
                sessionStartTime = 0L,
                sessionEndTime = 1L
            )
        )
        awaitCurrentPublisher("ghost-s1")
        follower("listener")
        ui.connection.send(DevToolsMessage.GhostDeviceRemoval("ghost-s1"))
        awaitCurrentPublisher(null)

        val device = publisher("device")

        device.awaitAttachOf("listener")
    }

    @Test
    fun `moving the publisher role tells the previous publisher and moves its followers`() = runBlocking<Unit> {
        val first = publisher("first")
        follower("listener")
        val ui = open("ui")
        ui.request(ClientRole.ORCHESTRATOR)
        ui.awaitRole(ClientRole.ORCHESTRATOR)
        val second = open("second")

        ui.request(ClientRole.PUBLISHER, target = "second")

        second.awaitRole(ClientRole.PUBLISHER)
        first.awaitRole(ClientRole.UNASSIGNED)
        second.awaitAttachOf("listener")
    }

    @Test
    fun `a publisher that asks to follow gives up the publisher role instead of following itself`() = runBlocking<Unit> {
        val first = publisher("first")

        first.request(ClientRole.LISTENER)
        val assignment = first.awaitRole(ClientRole.LISTENER)

        assertEquals(null, assignment.publisherClientId)
        awaitCurrentPublisher(null)
        val second = publisher("second")
        second.awaitAttachOf("first")
    }

    @Test
    fun `a follower gets each full delta once and no synthesized state sync`() = runBlocking<Unit> {
        val source = publisher("source")
        val listener = follower("listener")
        source.awaitAttachOf("listener")

        source.connection.send(DevToolsMessage.ActionDispatched(delta("source", "Reset", DeltaKind.FULL)))
        source.connection.send(DevToolsMessage.ActionDispatched(delta("source", "Increment", DeltaKind.FIELDS)))

        val received = mutableListOf<DevToolsMessage>()
        listener.await("the fields delta") { message ->
            received += message
            (message as? DevToolsMessage.ActionDispatched)?.takeIf { it.event.actionType == "Increment" }
        }
        val syncs = received.filterIsInstance<DevToolsMessage.StateSync>()
        assertTrue(syncs.isEmpty(), "the follower was sent $syncs next to the delta itself")
    }

    @Test
    fun `a client on another protocol version is refused with the reason`() = runBlocking<Unit> {
        val json = reaktivJson()
        val client = HttpClient(devToolsHttpClientEngine()) { install(WebSockets) }
        var reply: DevToolsMessage? = null
        withTimeoutOrNull(10_000) {
            client.webSocket("ws://127.0.0.1:$serverPort/ws") {
                val legacy = DevToolsMessage.ClientRegistration(clientName = "Old", clientId = "old-device", platform = "JVM")
                send(Frame.Text(json.encodeToString<DevToolsMessage>(legacy)))
                val frame = incoming.receive() as Frame.Text
                reply = json.decodeFromString<DevToolsMessage>(frame.readText())
            }
        }
        client.close()

        val refused = assertIs<DevToolsMessage.RegistrationRefused>(reply)
        assertTrue(refused.reason.contains("protocol"), refused.reason)
        assertEquals(null, server.clientManager.getClient("old-device"))
    }

    @Test
    fun `a client cannot speak for another client`() = runBlocking<Unit> {
        val source = publisher("source")
        val listener = follower("listener")
        source.awaitAttachOf("listener")
        val intruder = open("intruder")

        intruder.connection.send(DevToolsMessage.ActionDispatched(delta("source", "Forged", DeltaKind.FULL)))
        intruder.request(ClientRole.UNASSIGNED)
        intruder.awaitRole(ClientRole.UNASSIGNED)
        source.connection.send(DevToolsMessage.ActionDispatched(delta("source", "Real", DeltaKind.FULL)))

        val received = mutableListOf<String>()
        listener.await("the real delta") { message ->
            (message as? DevToolsMessage.ActionDispatched)?.event?.actionType?.also { received += it }
                ?.takeIf { it == "Real" }
        }
        assertEquals(listOf("Real"), received)
    }

    @Test
    fun `an orchestrator can send state on behalf of the publisher it observes`() = runBlocking<Unit> {
        val source = publisher("source")
        val listener = follower("listener")
        source.awaitAttachOf("listener")
        val ui = open("ui")
        ui.request(ClientRole.ORCHESTRATOR)
        ui.awaitRole(ClientRole.ORCHESTRATOR)
        source.awaitAttachOf("ui")

        ui.connection.send(DevToolsMessage.StateSync(fromClientId = "source", timestamp = 2L, stateJson = "{}"))

        val sync = listener.await("the time travel state") { message ->
            (message as? DevToolsMessage.StateSync)?.takeIf { it.timestamp == 2L }
        }
        assertEquals("source", sync.fromClientId)
    }

    @Test
    fun `a follower receives actions and state but not what only the ui renders`() = runBlocking<Unit> {
        val source = publisher("source")
        val listener = follower("listener")
        source.awaitAttachOf("listener")
        val ui = open("ui")
        ui.request(ClientRole.ORCHESTRATOR)
        ui.awaitRole(ClientRole.ORCHESTRATOR)
        source.awaitAttachOf("ui")
        val marker = DevToolsMessage.MarkerAdded(
            clientId = "source",
            marker = SessionMarker(id = "m1", label = "checkpoint", timestampMs = 1L, afterActionIndex = 0)
        )

        source.connection.send(marker)
        source.connection.send(DevToolsMessage.ActionDispatched(delta("source", "After", DeltaKind.FULL)))

        ui.await("the marker") { message -> (message as? DevToolsMessage.MarkerAdded)?.takeIf { it.marker.id == "m1" } }
        val received = mutableListOf<DevToolsMessage>()
        listener.await("the delta") { message ->
            received += message
            (message as? DevToolsMessage.ActionDispatched)?.takeIf { it.event.actionType == "After" }
        }
        assertTrue(received.none { it is DevToolsMessage.MarkerAdded }, "the follower was sent the marker")
    }
}
