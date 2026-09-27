package io.github.syrou.reaktiv.devtools.service

import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.tooling.ToolingAction
import io.github.syrou.reaktiv.introspection.tooling.ToolingCommand

@Deprecated(
    "Dispatch the typed commands, for example DevToolsCommands.connect(url, role), instead of an enum plus string arguments.",
    level = DeprecationLevel.WARNING
)
public enum class DevToolsCommand : ToolingCommand {
    CONNECT,
    DISCONNECT,
    RECONNECT,
    FOLLOW,
    UNFOLLOW
}

public object DevToolsCommands {
    public const val SERVICE_NAME: String = "devtools"

    public sealed interface Command : ToolingCommand

    public data class Connect(val url: String? = null, val role: ClientRole? = null) : Command

    public data object Disconnect : Command

    public data object Reconnect : Command

    public data class Follow(val publisherClientId: String? = null) : Command

    public data object Unfollow : Command

    public fun connect(url: String? = null, role: ClientRole? = null): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, Connect(url, role))

    public fun disconnect(): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, Disconnect)

    public fun reconnect(): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, Reconnect)

    public fun follow(publisherClientId: String? = null): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, Follow(publisherClientId))

    public fun unfollow(): ToolingAction.ServiceCommand =
        ToolingAction.ServiceCommand(SERVICE_NAME, Unfollow)

    @Suppress("DEPRECATION")
    internal fun typed(command: ToolingCommand, args: Map<String, String>): Command? = when (command) {
        is Command -> command
        DevToolsCommand.CONNECT -> Connect(
            url = args["url"],
            role = args["role"]?.let { name -> ClientRole.entries.firstOrNull { it.name == name } }
        )
        DevToolsCommand.DISCONNECT -> Disconnect
        DevToolsCommand.RECONNECT -> Reconnect
        DevToolsCommand.FOLLOW -> Follow(args["publisher"])
        DevToolsCommand.UNFOLLOW -> Unfollow
        else -> null
    }
}
