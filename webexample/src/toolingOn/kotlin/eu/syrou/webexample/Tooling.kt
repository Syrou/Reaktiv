package eu.syrou.webexample

import eu.syrou.example.ExamplePlatform
import eu.syrou.example.tooling.exampleToolingPlatform
import io.github.syrou.reaktiv.devtools.client.devToolsServerUrlFromPage
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.browserIntrospectionConfig

fun webPlatform(): ExamplePlatform {
    val serverUrl = devToolsServerUrlFromPage()
    return exampleToolingPlatform(
        config = browserIntrospectionConfig(),
        platformContext = PlatformContext(),
        serverUrl = serverUrl,
        autoConnect = serverUrl != null
    )
}
