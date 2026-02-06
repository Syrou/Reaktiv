package io.github.syrou.reaktiv.devtools.middleware

import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.IntrospectionConfig

/**
 * Configuration for DevTools middleware.
 *
 * Identity properties (clientId, clientName, platform) are read from the shared
 * [IntrospectionConfig], ensuring consistent identification across introspection
 * and DevTools.
 *
 * Example usage:
 * ```kotlin
 * val introspectionConfig = IntrospectionConfig(
 *     clientName = "MyApp",
 *     platform = "${Build.MANUFACTURER} ${Build.MODEL}"
 * )
 *
 * val config = DevToolsConfig(
 *     introspectionConfig = introspectionConfig,
 *     serverUrl = "ws://192.168.1.100:8080/ws",
 *     defaultRole = ClientRole.PUBLISHER
 * )
 * ```
 *
 * Example usage for ad-hoc connection (connect later via action):
 * ```kotlin
 * val config = DevToolsConfig(
 *     introspectionConfig = introspectionConfig,
 *     serverUrl = null // Don't auto-connect
 * )
 *
 * // Later, connect via dispatched action:
 * dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))
 * ```
 *
 * @param introspectionConfig Shared configuration providing clientId, clientName, and platform
 * @param serverUrl WebSocket server URL. If null, won't auto-connect (use DevToolsAction.Connect)
 * @param enabled Enable/disable DevTools functionality
 * @param allowActionCapture Allow sending dispatched actions to the DevTools server
 * @param allowStateCapture Allow sending state changes to the DevTools server
 * @param defaultRole The role to request automatically on connect. Null means no auto-request.
 */
data class DevToolsConfig(
    val introspectionConfig: IntrospectionConfig,
    val serverUrl: String? = null,
    val enabled: Boolean = true,
    val allowActionCapture: Boolean = true,
    val allowStateCapture: Boolean = true,
    val defaultRole: ClientRole? = null
) {
    val clientId: String get() = introspectionConfig.clientId
    val clientName: String get() = introspectionConfig.clientName
    val platform: String get() = introspectionConfig.platform
}
