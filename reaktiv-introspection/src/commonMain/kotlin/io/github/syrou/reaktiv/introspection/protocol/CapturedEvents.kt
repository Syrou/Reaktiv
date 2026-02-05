package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.Serializable

/**
 * Represents a captured action dispatch event.
 */
@Serializable
data class CapturedAction(
    val clientId: String,
    val timestamp: Long,
    val actionType: String,
    val actionData: String,
    val resultingStateJson: String
)

/**
 * Represents a captured logic method start event.
 */
@Serializable
data class CapturedLogicStart(
    val clientId: String,
    val timestamp: Long,
    val callId: String,
    val logicClass: String,
    val methodName: String,
    val params: Map<String, String>,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val githubSourceUrl: String? = null
)

/**
 * Represents a captured logic method completion event.
 */
@Serializable
data class CapturedLogicComplete(
    val clientId: String,
    val timestamp: Long,
    val callId: String,
    val result: String?,
    val resultType: String,
    val durationMs: Long
)

/**
 * Represents a captured logic method failure event.
 */
@Serializable
data class CapturedLogicFailed(
    val clientId: String,
    val timestamp: Long,
    val callId: String,
    val exceptionType: String,
    val exceptionMessage: String?,
    val durationMs: Long
)
