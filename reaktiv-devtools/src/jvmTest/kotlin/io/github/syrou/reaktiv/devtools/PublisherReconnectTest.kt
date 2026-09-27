package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.client.DevToolsConnection
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.server.DevToolsServer
import io.github.syrou.reaktiv.devtools.server.RunningDevToolsServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PublisherReconnectTest {

    private lateinit var server: RunningDevToolsServer
    private var serverPort: Int = 0
    private val connections = mutableListOf<DevToolsConnection>()

    @BeforeTest
    fun startServer() = runBlocking {
        server = DevToolsServer.startEmbedded(port = 0)
        serverPort = server.port()
    }

    @AfterTest
    fun stopServer() {
        connections.forEach { runCatching { runBlocking { it.disconnect() } } }
        server.stop()
    }

    private suspend fun publishAs(clientId: String, clientName: String): ClientRole? {
        val assigned = CompletableDeferred<ClientRole>()
        val connection = DevToolsConnection("ws://127.0.0.1:$serverPort/ws").also(connections::add)
        connection.observeMessages { message ->
            if (message is DevToolsMessage.RoleAssignment && message.targetClientId == clientId) {
                assigned.complete(message.role)
            }
        }
        connection.connect(clientId, clientName, "Web")
        connection.send(DevToolsMessage.RoleAssignment(targetClientId = clientId, role = ClientRole.PUBLISHER))
        return withTimeoutOrNull(10_000) { assigned.await() }
    }

    @Test
    fun `a publisher that reconnects under its own id before the old socket closes keeps publishing`() = runBlocking {
        assertEquals(ClientRole.PUBLISHER, publishAs("tab-1", "before reload"))

        val afterReload = publishAs("tab-1", "after reload")

        assertEquals(ClientRole.PUBLISHER, afterReload)
        assertEquals("tab-1", server.clientManager.currentPublisher())
    }

    @Test
    fun `a different client still cannot take the publisher role on its own`() = runBlocking {
        assertEquals(ClientRole.PUBLISHER, publishAs("tab-1", "first"))

        assertEquals(ClientRole.UNASSIGNED, publishAs("tab-2", "second"))
        assertEquals("tab-1", server.clientManager.currentPublisher())
    }
}
