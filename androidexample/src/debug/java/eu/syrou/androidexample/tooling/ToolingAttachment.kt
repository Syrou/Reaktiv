package eu.syrou.androidexample.tooling

import android.content.Context
import android.os.Build
import eu.syrou.androidexample.ui.screen.DevToolsScreen
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.devtools.service.DevToolsService
import io.github.syrou.reaktiv.introspection.ClientMetadata
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import io.github.syrou.reaktiv.navigation.definition.Screen

fun toolingModule(context: Context): Module<*, *>? = createToolingModule(
    config = IntrospectionConfig(
        clientName = "${Build.MANUFACTURER} ${Build.MODEL}",
        platform = "Android ${Build.VERSION.RELEASE}",
        clientMetadata = ClientMetadata(
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            osVersion = Build.VERSION.RELEASE
        )
    ),
    platformContext = PlatformContext(context)
) {
    install(
        DevToolsService(
            DevToolsConfig(
                serverUrl = "ws://100.125.101.2:8080/ws",
                autoConnect = false,
                defaultRole = ClientRole.PUBLISHER
            )
        )
    )
}

fun toolingScreens(): List<Screen> = listOf(DevToolsScreen)

suspend fun exportCapturedSession(store: StoreAccessor): String? =
    store.selectLogic<ToolingLogic>().exportSessionToDownloads()
