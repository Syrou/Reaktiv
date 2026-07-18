package io.github.syrou.reaktiv.devtools.service

import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.tooling.ToolingAction
import io.github.syrou.reaktiv.introspection.tooling.ToolingCommand

public enum class DevToolsCommand : ToolingCommand {
    CONNECT,
    DISCONNECT,
    RECONNECT,
    FOLLOW,
    UNFOLLOW
}

public object DevToolsCommands {
    public const val SERVICE_NAME: String = "devtools"

    public fun connect(url: String? = null, role: ClientRole? = null): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(
            service = SERVICE_NAME,
            command = DevToolsCommand.CONNECT,
            args = buildMap {
                url?.let { put("url", it) }
                role?.let { put("role", it.name) }
            }
        )

    public fun disconnect(): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, DevToolsCommand.DISCONNECT)

    public fun reconnect(): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, DevToolsCommand.RECONNECT)

    public fun follow(publisherClientId: String? = null): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(
            service = SERVICE_NAME,
            command = DevToolsCommand.FOLLOW,
            args = buildMap { publisherClientId?.let { put("publisher", it) } }
        )

    public fun unfollow(): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, DevToolsCommand.UNFOLLOW)
}
