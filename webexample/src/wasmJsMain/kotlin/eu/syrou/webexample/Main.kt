package eu.syrou.webexample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.syrou.example.ExampleApp
import eu.syrou.example.ExampleApplication
import eu.syrou.example.ui.theme.ReaktivTheme
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.util.ReaktivDebug

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ReaktivDebug.enable()
    val platform = webPlatform()
    val store = ExampleApplication(platform).store
    ComposeViewport(viewportContainerId = "root") {
        ReaktivTheme {
            StoreProvider(store) {
                ExampleApp(platform)
            }
        }
    }
}
