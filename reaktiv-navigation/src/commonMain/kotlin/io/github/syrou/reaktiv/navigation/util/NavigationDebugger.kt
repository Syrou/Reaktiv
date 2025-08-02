package io.github.syrou.reaktiv.navigation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import findLayoutGraphsInHierarchy
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState


@Composable
fun NavigationDebugger(navigationState: NavigationState) {
    LaunchedEffect(navigationState) {
        ReaktivDebug.nav("=== Simplified Navigation Debug ===")
        ReaktivDebug.nav("Current screen: ${navigationState.currentEntry.screen.route}")
        ReaktivDebug.nav("Current graph: ${navigationState.currentEntry.graphId}")
        ReaktivDebug.nav("Back stack size: ${navigationState.backStack.size}")
        ReaktivDebug.nav("Back stack: ${navigationState.backStack.map { "${it.graphId}/${it.screen.route}" }}")
        ReaktivDebug.nav("Available graphs: ${navigationState.graphDefinitions.keys}")
        ReaktivDebug.nav("Can go back: ${navigationState.canGoBack}")

        ReaktivDebug.nav("=== End Debug Info ===")
    }
    val layoutGraphs = findLayoutGraphsInHierarchy(navigationState.currentEntry.graphId, navigationState)
    if (layoutGraphs.isNotEmpty()) {
        ReaktivDebug.nav("Layout hierarchy: ${layoutGraphs.map { it.route }}")
    } else {
        ReaktivDebug.nav("No custom layouts")
    }
}