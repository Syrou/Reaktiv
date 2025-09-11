import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.ui.ContentLayerRender
import io.github.syrou.reaktiv.navigation.ui.GlobalOverlayLayerRender
import io.github.syrou.reaktiv.navigation.ui.NavigationBackgroundProvider
import io.github.syrou.reaktiv.navigation.ui.SystemLayerRender
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger

val LocalContentCache = staticCompositionLocalOf<MutableMap<String, @Composable () -> Unit>> {
    error("ContentCache not provided")
}

@Composable
fun NavigationRender(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    screenContent: @Composable (Navigatable, Params) -> Unit
) {
    val navigationState by composeState<NavigationState>()
    
    // Top-level content cache that survives ContentLayerRender recompositions
    val contentCache = remember { mutableStateMapOf<String, @Composable () -> Unit>() }

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState)
    }

    CompositionLocalProvider(LocalContentCache provides contentCache) {
        NavigationBackgroundProvider(backgroundColor = backgroundColor) {
            Box(modifier = modifier.fillMaxSize()) {
                ContentLayerRender(
                    entries = navigationState.contentLayerEntries,
                    navigationState = navigationState,
                    screenContent = screenContent
                )

                GlobalOverlayLayerRender(
                    entries = navigationState.globalOverlayEntries,
                    screenContent = screenContent
                )

                SystemLayerRender(
                    entries = navigationState.systemLayerEntries,
                    screenContent = screenContent
                )
            }
        }
    }
}