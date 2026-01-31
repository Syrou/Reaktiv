import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ui.LayerType
import io.github.syrou.reaktiv.navigation.ui.UnifiedLayerRenderer
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger
import io.github.syrou.reaktiv.navigation.util.getNavigationModule


@Composable
fun NavigationRender(
    modifier: Modifier = Modifier
) {
    val store = rememberStore()
    val navigationState by composeState<NavigationState>()
    val graphDefinitions = remember { store.getNavigationModule().getGraphDefinitions() }

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState, store)
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Render all layers using unified layer renderer
        UnifiedLayerRenderer(
            layerType = LayerType.Content,
            entries = navigationState.contentLayerEntries,
            navigationState = navigationState,
            graphDefinitions = graphDefinitions
        )

        UnifiedLayerRenderer(
            layerType = LayerType.GlobalOverlay,
            entries = navigationState.globalOverlayEntries,
            navigationState = navigationState,
            graphDefinitions = graphDefinitions
        )

        UnifiedLayerRenderer(
            layerType = LayerType.System,
            entries = navigationState.systemLayerEntries,
            navigationState = navigationState,
            graphDefinitions = graphDefinitions
        )
    }
}