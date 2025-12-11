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
        val actionData: String
    ) : DevToolsMessage()

    @Serializable
    data class StateUpdate(
        val clientId: String,
        val timestamp: Long,
        val triggeringAction: String,
        val stateJson: String
    ) : DevToolsMessage()

    @Serializable
    data class StateSync(
        val fromClientId: String,
        val timestamp: Long,
        val stateJson: String
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
}

@Serializable
enum class ClientRole {
    UNASSIGNED,
    PUBLISHER,
    LISTENER,
    OBSERVER
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
