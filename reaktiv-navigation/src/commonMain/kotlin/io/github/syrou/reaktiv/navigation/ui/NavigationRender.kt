package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger
import io.github.syrou.reaktiv.navigation.util.getNavigationModule

val LocalNavigationModule = compositionLocalOf<NavigationModule> {
    error("NavigationModule not provided. Wrap your content with NavigationRender.")
}

@Composable
fun NavigationRender(
    modifier: Modifier = Modifier
) {
    val store = rememberStore()
    val navigationState by composeState<NavigationState>()
    val navModule = remember { store.getNavigationModule() }
    val graphDefinitions = remember { navModule.getGraphDefinitions() }

    val currentEntryKey = navigationState.currentEntry.stableKey
    val resolvedTitle = navModule.resolveNavigatable(navigationState.currentEntry)?.titleResource?.invoke()
    LaunchedEffect(currentEntryKey) {
        store.dispatch(NavigationAction.SetCurrentTitle(resolvedTitle))
    }

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState, store)
    }

    CompositionLocalProvider(LocalNavigationModule provides navModule) {
        Box(
            modifier = modifier.fillMaxSize()
        ) {
            val hasActiveLoadingModal = navigationState.systemLayerEntries.any {
                navModule.resolveNavigatable(it) is LoadingModal
            }
            val showContentLayers = !navigationState.isBootstrapping || !hasActiveLoadingModal
            if (showContentLayers) {
                UnifiedLayerRenderer(
                    layerType = RenderLayer.CONTENT,
                    entries = navigationState.contentLayerEntries,
                    graphDefinitions = graphDefinitions
                )

                UnifiedLayerRenderer(
                    layerType = RenderLayer.GLOBAL_OVERLAY,
                    entries = navigationState.globalOverlayEntries,
                    graphDefinitions = graphDefinitions
                )
            }

            UnifiedLayerRenderer(
                layerType = RenderLayer.SYSTEM,
                entries = navigationState.systemLayerEntries,
                graphDefinitions = graphDefinitions
            )
        }
    }
}
