package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevToolsConfigTest {

    @Test
    fun `defaultRole is null by default`() {
        val config = DevToolsConfig()
        assertNull(config.defaultRole)
    }

    @Test
    fun `defaultRole can be set to PUBLISHER`() {
        val config = DevToolsConfig(defaultRole = ClientRole.PUBLISHER)
        assertEquals(ClientRole.PUBLISHER, config.defaultRole)
    }

    @Test
    fun `defaultRole can be set to LISTENER`() {
        val config = DevToolsConfig(defaultRole = ClientRole.LISTENER)
        assertEquals(ClientRole.LISTENER, config.defaultRole)
    }

    @Test
    fun `config defaults are correct`() {
        val config = DevToolsConfig()
        assertNull(config.serverUrl)
        assertEquals(true, config.enabled)
        assertEquals(true, config.autoConnect)
        assertEquals(true, config.autoReconnect)
        assertEquals(true, config.allowActionCapture)
        assertEquals(true, config.allowStateCapture)
    }

    @Test
    fun `autoConnect can be disabled for manual connection timing`() {
        val config = DevToolsConfig(
            serverUrl = "ws://localhost:8080/ws",
            autoConnect = false
        )
        assertEquals(false, config.autoConnect)
        assertEquals("ws://localhost:8080/ws", config.serverUrl)
    }
}
