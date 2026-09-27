package eu.syrou.androidexample.tooling

import android.content.Context
import android.os.Build
import eu.syrou.androidexample.ui.screen.TwitchAuthWebViewScreen
import eu.syrou.example.ExamplePlatform
import eu.syrou.example.tooling.exampleToolingPlatform
import io.github.syrou.reaktiv.introspection.ClientMetadata
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.PlatformContext

private const val DEVTOOLS_SERVER_URL = "ws://100.125.101.2:8080/ws"

fun examplePlatform(context: Context): ExamplePlatform = exampleToolingPlatform(
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
    platformContext = PlatformContext(context),
    serverUrl = DEVTOOLS_SERVER_URL,
    autoConnect = false,
    twitchLogin = TwitchAuthWebViewScreen
)
