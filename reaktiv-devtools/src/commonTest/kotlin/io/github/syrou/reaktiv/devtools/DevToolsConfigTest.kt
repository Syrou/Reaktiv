package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DevToolsConfigTest {

    private fun introspectionConfig(platform: String = "Test") = IntrospectionConfig(
        platform = platform
    )

    @Test
    fun `defaultRole is null by default`() {
        val config = DevToolsConfig(introspectionConfig = introspectionConfig())
        assertNull(config.defaultRole)
    }

    @Test
    fun `defaultRole can be set to PUBLISHER`() {
        val config = DevToolsConfig(
            introspectionConfig = introspectionConfig(),
            defaultRole = ClientRole.PUBLISHER
        )
        assertEquals(ClientRole.PUBLISHER, config.defaultRole)
    }

    @Test
    fun `defaultRole can be set to LISTENER`() {
        val config = DevToolsConfig(
            introspectionConfig = introspectionConfig(),
            defaultRole = ClientRole.LISTENER
        )
        assertEquals(ClientRole.LISTENER, config.defaultRole)
    }

    @Test
    fun `identity properties delegate to IntrospectionConfig`() {
        val ic = IntrospectionConfig(
            clientId = "shared-id",
            clientName = "SharedApp",
            platform = "TestPlatform"
        )
        val config = DevToolsConfig(introspectionConfig = ic)

        assertEquals("shared-id", config.clientId)
        assertEquals("SharedApp", config.clientName)
        assertEquals("TestPlatform", config.platform)
    }

    @Test
    fun `two configs with same IntrospectionConfig share identity`() {
        val ic = introspectionConfig()
        val config1 = DevToolsConfig(introspectionConfig = ic)
        val config2 = DevToolsConfig(introspectionConfig = ic)

        assertEquals(config1.clientId, config2.clientId)
        assertEquals(config1.clientName, config2.clientName)
        assertEquals(config1.platform, config2.platform)
    }

    @Test
    fun `config defaults are correct`() {
        val config = DevToolsConfig(introspectionConfig = introspectionConfig())
        assertEquals(true, config.enabled)
        assertEquals(true, config.allowActionCapture)
        assertEquals(true, config.allowStateCapture)
        assertNull(config.serverUrl)
    }
}
