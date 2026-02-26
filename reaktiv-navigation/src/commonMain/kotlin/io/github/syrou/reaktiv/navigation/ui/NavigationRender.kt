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
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
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
        // Hide content/overlay layers while bootstrapping AND a LoadingModal is in the SYSTEM
        // layer — avoids flashing the placeholder screen before the real destination is known.
        val hasActiveLoadingModal = navigationState.systemLayerEntries.any {
            it.navigatable is LoadingModal
        }
        val showContentLayers = !navigationState.isBootstrapping || !hasActiveLoadingModal
        if (showContentLayers) {
            UnifiedLayerRenderer(
                layerType = LayerType.Content,
                entries = navigationState.contentLayerEntries,
                graphDefinitions = graphDefinitions
            )

            UnifiedLayerRenderer(
                layerType = LayerType.GlobalOverlay,
                entries = navigationState.globalOverlayEntries,
                graphDefinitions = graphDefinitions
            )
        }

        UnifiedLayerRenderer(
            layerType = LayerType.System,
            entries = navigationState.systemLayerEntries,
            graphDefinitions = graphDefinitions
        )
    }
}
