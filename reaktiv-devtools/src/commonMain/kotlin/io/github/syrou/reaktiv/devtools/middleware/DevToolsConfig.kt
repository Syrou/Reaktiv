package io.github.syrou.reaktiv.devtools.middleware

import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Defines the default role a device should request when connecting.
 */
enum class DefaultDeviceRole {
    /**
     * Request to become the publisher (source of state).
     */
    PUBLISHER,

    /**
     * Request to become a listener (subscriber to state changes).
     */
    LISTENER,

    /**
     * Do not request any role automatically.
     */
    NONE
}

/**
 * Configuration for DevTools middleware.
 *
 * Example usage for Android with auto-connect:
 * ```kotlin
 * import android.os.Build
 *
 * val config = DevToolsConfig(
 *     serverUrl = "ws://192.168.1.100:8080/ws",
 *     clientName = "QA Device 1",
 *     platform = "${Build.MANUFACTURER} ${Build.MODEL}"
 * )
 * ```
 *
 * Example usage for ad-hoc connection (connect later via action):
 * ```kotlin
 * val config = DevToolsConfig(
 *     serverUrl = null, // Don't auto-connect
 *     platform = "${Build.MANUFACTURER} ${Build.MODEL}"
 * )
 *
 * // Later, connect via dispatched action:
 * dispatch(DevToolsAction.Connect("ws://192.168.1.100:8080/ws"))
 * ```
 *
 * Example usage with session capture for crash reporting:
 * ```kotlin
 * val config = DevToolsConfig(
 *     serverUrl = "ws://192.168.1.100:8080/ws",
 *     platform = "${Build.MANUFACTURER} ${Build.MODEL}",
 *     enableSessionCapture = true,
 *     maxCapturedActions = 500,
 *     defaultRole = DefaultDeviceRole.PUBLISHER
 * )
 * ```
 *
 * @param serverUrl WebSocket server URL. If null, won't auto-connect (use DevToolsAction.Connect)
 * @param clientName Display name for this client in DevTools UI
 * @param clientId Unique identifier for this client (auto-generated UUID)
 * @param platform Platform/device description (e.g., "Samsung Galaxy S23", "Desktop - macOS")
 * @param enabled Enable/disable DevTools functionality
 * @param allowActionCapture Allow capturing dispatched actions
 * @param allowStateCapture Allow capturing state changes
 * @param defaultRole The role to request automatically on connect
 * @param enableSessionCapture Enable capturing session history for crash reports and manual export
 * @param maxCapturedActions Maximum number of actions to retain in session capture (FIFO)
 * @param maxCapturedLogicEvents Maximum number of logic events to retain in session capture (FIFO)
 */
@OptIn(ExperimentalUuidApi::class)
data class DevToolsConfig(
    val serverUrl: String? = null,
    val clientName: String = "Client-${generateId()}",
    val clientId: String = generateId(),
    val platform: String,
    val enabled: Boolean = true,
    val allowActionCapture: Boolean = true,
    val allowStateCapture: Boolean = true,
    val defaultRole: DefaultDeviceRole = DefaultDeviceRole.NONE,
    val enableSessionCapture: Boolean = true,
    val maxCapturedActions: Int = 1000,
    val maxCapturedLogicEvents: Int = 2000
)

@OptIn(ExperimentalUuidApi::class)
private fun generateId(): String {
    return Uuid.random().toString()
}
