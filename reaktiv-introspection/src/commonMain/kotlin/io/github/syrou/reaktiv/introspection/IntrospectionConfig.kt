package io.github.syrou.reaktiv.introspection

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class ClientMetadata(
    val appVersion: String? = null,
    val osVersion: String? = null,
    val reaktivVersion: String? = null,
    val locale: String? = null
)

public fun interface StateRedactor {
    public fun redact(moduleName: String, state: JsonElement): JsonElement
}

/**
 * Configuration for introspection identity and behavior.
 *
 * Example usage:
 * ```kotlin
 * val config = IntrospectionConfig(
 *     clientName = "MyApp",
 *     platform = "Android ${Build.VERSION.RELEASE}"
 * )
 * ```
 *
 * @param clientId Unique identifier for this client (auto-generated if not provided)
 * @param clientName Display name for this client
 * @param platform Platform description (e.g., "Android 14", "iOS 17")
 * @param enabled Enable or disable session capture
 */
public data class IntrospectionConfig @OptIn(ExperimentalUuidApi::class) constructor(
    val clientId: String = Uuid.random().toString(),
    val clientName: String = "Client-${clientId.take(8)}",
    val platform: String,
    val enabled: Boolean = true,
    val autoStart: Boolean = true,
    val installCrashHandler: Boolean = true,
    val installStallWatchdog: Boolean = true,
    val stallThresholdMs: Long = 300L,
    val clientMetadata: ClientMetadata? = null,
    val redactor: StateRedactor? = null,
    val maxActions: Int? = null,
    val maxLogicEvents: Int? = null,
    val redactSensitiveKeys: Boolean = true,
    val installLogicTracing: Boolean = true
)
