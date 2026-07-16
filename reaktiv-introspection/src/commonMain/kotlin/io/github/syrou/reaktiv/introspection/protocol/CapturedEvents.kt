package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.Serializable

/**
 * Represents a captured action dispatch event.
 */
@Serializable
public data class CapturedAction(
    val clientId: String,
    val timestamp: Long,
    val actionType: String,
    val actionData: String,
    val stateDeltaJson: String,
    val moduleName: String = ""
)
