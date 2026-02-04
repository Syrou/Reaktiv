package io.github.syrou.reaktiv.devtools.protocol

import kotlinx.serialization.Serializable

@Serializable
sealed class DevToolsMessage {

    @Serializable
    data class ClientRegistration(
        val clientName: String,
        val clientId: String,
        val platform: String
    ) : DevToolsMessage()

    @Serializable
    data class RoleAssignment(
        val targetClientId: String,
        val role: ClientRole,
        val publisherClientId: String? = null
    ) : DevToolsMessage()

    @Serializable
    data class ActionDispatched(
        val clientId: String,
        val timestamp: Long,
        val actionType: String,
        val actionData: String,
        val resultingStateJson: String
    ) : DevToolsMessage()

    @Serializable
    data class StateSync(
        val fromClientId: String,
        val timestamp: Long,
        val stateJson: String,
        val orchestrated: Boolean = false
    ) : DevToolsMessage()

    @Serializable
    data class ClientListUpdate(
        val clients: List<ClientInfo>
    ) : DevToolsMessage()

    @Serializable
    data class RoleAcknowledgment(
        val clientId: String,
        val role: ClientRole,
        val success: Boolean,
        val message: String? = null
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method starts execution.
     */
    @Serializable
    data class LogicMethodStarted(
        val clientId: String,
        val timestamp: Long,
        val callId: String,
        val logicClass: String,
        val methodName: String,
        val params: Map<String, String>,
        val sourceFile: String? = null,
        val lineNumber: Int? = null,
        val githubSourceUrl: String? = null
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method completes successfully.
     */
    @Serializable
    data class LogicMethodCompleted(
        val clientId: String,
        val timestamp: Long,
        val callId: String,
        val result: String?,
        val resultType: String,
        val durationMs: Long
    ) : DevToolsMessage()

    /**
     * Sent when a traced logic method fails with an exception.
     */
    @Serializable
    data class LogicMethodFailed(
        val clientId: String,
        val timestamp: Long,
        val callId: String,
        val exceptionType: String,
        val exceptionMessage: String?,
        val durationMs: Long
    ) : DevToolsMessage()
}

@Serializable
enum class ClientRole {
    UNASSIGNED,
    PUBLISHER,
    SUBSCRIBER,
    ORCHESTRATOR
}

@Serializable
data class ClientInfo(
    val clientId: String,
    val clientName: String,
    val platform: String,
    val role: ClientRole,
    val publisherClientId: String? = null,
    val connectedAt: Long
)
