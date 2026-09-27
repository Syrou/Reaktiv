package eu.syrou.example.tooling

import io.github.syrou.reaktiv.network.ktor.ReaktivNetworkInspection
import io.github.syrou.reaktiv.navigation.definition.Screen
import eu.syrou.example.ExamplePlatform
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import io.github.syrou.reaktiv.navigation.tooling.NavigationLinks

fun exampleToolingModule(
    config: IntrospectionConfig,
    platformContext: PlatformContext,
    serverUrl: String?,
    autoConnect: Boolean
): Module<*, *> = createToolingModule(config, platformContext) {
    install(
        DevToolsService(
            DevToolsConfig(
                serverUrl = serverUrl,
                autoConnect = autoConnect,
                defaultRole = ClientRole.PUBLISHER
            )
        )
    )
    install(NavigationLinks())
}

suspend fun exportExampleSession(store: StoreAccessor): String =
    store.selectLogic<ToolingLogic>().exportSessionToDownloads()

fun exampleToolingPlatform(
    config: IntrospectionConfig,
    platformContext: PlatformContext,
    serverUrl: String?,
    autoConnect: Boolean,
    twitchLogin: Screen? = null
): ExamplePlatform = ExamplePlatform(
    extraModules = listOf(
        exampleToolingModule(config, platformContext, serverUrl, autoConnect),
        DevToolsFormModule(serverUrl)
    ),
    twitchLogin = twitchLogin,
    devTools = DevToolsScreen,
    exportSession = ::exportExampleSession,
    configureHttpClient = { install(ReaktivNetworkInspection) }
)
