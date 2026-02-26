package io.github.syrou.reaktiv.navigation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState


@Composable
fun NavigationDebugger(navigationState: NavigationState, store: io.github.syrou.reaktiv.core.Store) {
    LaunchedEffect(navigationState) {
        val graphKeys = store.getNavigationModule().getGraphDefinitions().keys
        ReaktivDebug.nav("=== Simplified Navigation Debug ===")
        val navModule = store.getNavigationModule()
        val currentGraphId = navModule.getGraphId(navigationState.currentEntry) ?: "unknown"
        ReaktivDebug.nav("Current screen: ${navigationState.currentEntry.route}")
        ReaktivDebug.nav("Current path: ${navigationState.currentEntry.path}")
        ReaktivDebug.nav("Current graph: $currentGraphId")
        ReaktivDebug.nav("Back stack size: ${navigationState.backStack.size}")
        ReaktivDebug.nav("Back stack: ${navigationState.backStack.map { it.path }}")
        ReaktivDebug.nav("Available graphs: $graphKeys")
        ReaktivDebug.nav("Can go back: ${navigationState.canGoBack}")

        ReaktivDebug.nav("=== End Debug Info ===")
    }
    val navModule = store.getNavigationModule()
    val currentGraphId = navModule.getGraphId(navigationState.currentEntry) ?: "unknown"
    val graphDefinitions = navModule.getGraphDefinitions()
    val layoutGraphs = findLayoutGraphsInHierarchy(currentGraphId, graphDefinitions)
    if (layoutGraphs.isNotEmpty()) {
        ReaktivDebug.nav("Layout hierarchy: ${layoutGraphs.map { it.route }}")
    } else {
        ReaktivDebug.nav("No custom layouts")
    }
}