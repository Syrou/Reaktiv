package io.github.syrou.reaktiv.devtools.middleware

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Configuration for DevTools middleware.
 *
 * Example usage for Android:
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
 * @param serverUrl WebSocket server URL (default: ws://localhost:8080/ws)
 * @param clientName Display name for this client in DevTools UI
 * @param clientId Unique identifier for this client (auto-generated UUID)
 * @param platform Platform/device description (e.g., "Samsung Galaxy S23", "Desktop - macOS")
 * @param enabled Enable/disable DevTools functionality
 * @param allowActionCapture Allow capturing dispatched actions
 * @param allowStateCapture Allow capturing state changes
 */
@OptIn(ExperimentalUuidApi::class)
data class DevToolsConfig(
    val serverUrl: String = "ws://localhost:8080/ws",
    val clientName: String = "Client-${generateId()}",
    val clientId: String = generateId(),
    val platform: String,
    val enabled: Boolean = true,
    val allowActionCapture: Boolean = true,
    val allowStateCapture: Boolean = true
)

@OptIn(ExperimentalUuidApi::class)
private fun generateId(): String {
    return Uuid.random().toString()
}
