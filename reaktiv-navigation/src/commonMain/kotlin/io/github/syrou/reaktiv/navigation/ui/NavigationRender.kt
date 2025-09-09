import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ui.LayerType
import io.github.syrou.reaktiv.navigation.ui.UnifiedLayerRenderer
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger


@Composable
fun NavigationRender(
    modifier: Modifier = Modifier
) {
    val navigationState by composeState<NavigationState>()

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState)
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Render all layers using unified layer renderer
        UnifiedLayerRenderer(
            layerType = LayerType.Content,
            entries = navigationState.contentLayerEntries,
            navigationState = navigationState
        )

        UnifiedLayerRenderer(
            layerType = LayerType.GlobalOverlay,
            entries = navigationState.globalOverlayEntries,
            navigationState = navigationState
        )

        UnifiedLayerRenderer(
            layerType = LayerType.System,
            entries = navigationState.systemLayerEntries,
            navigationState = navigationState
        )
    }
}