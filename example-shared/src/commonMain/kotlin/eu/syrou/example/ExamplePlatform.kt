package eu.syrou.example

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.ktor.client.HttpClientConfig

class ExamplePlatform(
    val extraModules: List<Module<*, *>> = emptyList(),
    val twitchLogin: Screen? = null,
    val devTools: Screen? = null,
    val exportSession: (suspend (StoreAccessor) -> String?)? = null,
    val configureHttpClient: HttpClientConfig<*>.() -> Unit = {}
)

val LocalExamplePlatform = staticCompositionLocalOf { ExamplePlatform() }
