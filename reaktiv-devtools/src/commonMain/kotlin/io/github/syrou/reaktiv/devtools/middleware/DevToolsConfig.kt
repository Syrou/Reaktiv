package io.github.syrou.reaktiv.devtools.middleware

import io.github.syrou.reaktiv.devtools.protocol.ClientRole

/**
 * Configuration for the DevTools service.
 *
 * Client identity (clientId, clientName, platform) comes from the tooling module's
 * IntrospectionConfig, so it never needs to be repeated here.
 *
 * Example usage:
 * ```kotlin
 * val config = DevToolsConfig(
 *     serverUrl = "ws://192.168.1.100:8080/ws",
 *     defaultRole = ClientRole.PUBLISHER
 * )
 * ```
 *
 * Example usage for manual connection timing:
 * ```kotlin
 * val config = DevToolsConfig(
 *     serverUrl = "ws://192.168.1.100:8080/ws",
 *     autoConnect = false
 * )
 *
 * // Later, from a debug menu:
 * dispatch(ToolingAction.ServiceCommand("devtools", "connect", mapOf("role" to "PUBLISHER")))
 * ```
 *
 * @param serverUrl WebSocket server URL. Null means no connection until connect is called with a URL
 * @param enabled Enable/disable DevTools functionality
 * @param autoConnect Connect on service start when true; wait for an explicit connect when false
 * @param allowActionCapture Allow sending dispatched actions to the DevTools server
 * @param allowStateCapture Allow sending state changes to the DevTools server
 * @param defaultRole The role to request automatically on connect. Null means no auto-request.
 */
public data class DevToolsConfig(
    val serverUrl: String? = null,
    val enabled: Boolean = true,
    val autoConnect: Boolean = true,
    val allowActionCapture: Boolean = true,
    val allowStateCapture: Boolean = true,
    val defaultRole: ClientRole? = null
)
