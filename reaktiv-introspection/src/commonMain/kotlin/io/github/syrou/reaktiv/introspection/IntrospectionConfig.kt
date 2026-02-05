package io.github.syrou.reaktiv.introspection

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Configuration for introspection and crash capture functionality.
 *
 * Example usage:
 * ```kotlin
 * val config = IntrospectionConfig(
 *     clientName = "MyApp",
 *     platform = "Android ${Build.VERSION.RELEASE}",
 *     maxCapturedActions = 500,
 *     maxCapturedLogicEvents = 1000
 * )
 * ```
 *
 * @param clientId Unique identifier for this client (auto-generated if not provided)
 * @param clientName Display name for this client
 * @param platform Platform description (e.g., "Android 14", "iOS 17")
 * @param enabled Enable or disable session capture
 * @param maxCapturedActions Maximum number of actions to retain
 * @param maxCapturedLogicEvents Maximum number of logic events to retain
 */
data class IntrospectionConfig @OptIn(ExperimentalUuidApi::class) constructor(
    val clientId: String = Uuid.random().toString(),
    val clientName: String = "Client-${clientId.take(8)}",
    val platform: String,
    val enabled: Boolean = true,
    val maxCapturedActions: Int = 1000,
    val maxCapturedLogicEvents: Int = 2000
)
