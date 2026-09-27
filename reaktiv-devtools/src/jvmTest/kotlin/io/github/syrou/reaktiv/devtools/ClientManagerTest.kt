package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import io.github.syrou.reaktiv.devtools.server.ClientManager
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClientManagerTest {

    private class FakeSession : WebSocketSession {
        override val coroutineContext: CoroutineContext = Job()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun flush() = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() = Unit
    }

    private val registration = DevToolsMessage.ClientRegistration(
        clientName = "Chrome 140 on Windows",
        clientId = "tab-1",
        platform = "Web"
    )

    @Test
    fun `the old socket closing keeps a client that reconnected under the same id`() = runTest {
        val manager = ClientManager()
        val previous = FakeSession()
        val reloaded = FakeSession()
        manager.receive(null, previous, registration)
        manager.receive(null, reloaded, registration)

        manager.unregisterSession("tab-1", previous)

        assertEquals("Chrome 140 on Windows", assertNotNull(manager.getClient("tab-1")).clientName)
    }

    @Test
    fun `the socket that registered a client removes it when it closes`() = runTest {
        val manager = ClientManager()
        val session = FakeSession()
        manager.receive(null, session, registration)

        manager.unregisterSession("tab-1", session)

        assertNull(manager.getClient("tab-1"))
    }
}
