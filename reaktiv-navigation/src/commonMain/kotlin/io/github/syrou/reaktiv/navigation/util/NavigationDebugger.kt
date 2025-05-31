package io.github.syrou.reaktiv.navigation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ui.findLayoutGraphsInHierarchy

/**
 * Debug composable to help troubleshoot navigation issues
 */
@Composable
fun NavigationDebugger() {
    val navigationState by composeState<NavigationState>()

    LaunchedEffect(navigationState) {
        println("=== Simplified Navigation Debug ===")
        println("Current screen: ${navigationState.currentEntry.screen.route}")
        println("Current graph: ${navigationState.currentEntry.graphId}")
        println("Back stack size: ${navigationState.backStack.size}")
        println("Back stack: ${navigationState.backStack.map { "${it.graphId}/${it.screen.route}" }}")
        println("Available graphs: ${navigationState.graphDefinitions.keys}")
        println("Can go back: ${navigationState.canGoBack}")

        println("=== End Debug Info ===")
    }

    // Show layout hierarchy for current screen
    val layoutGraphs = findLayoutGraphsInHierarchy(navigationState.currentEntry.graphId, navigationState)
    if (layoutGraphs.isNotEmpty()) {
        println("Layout hierarchy: ${layoutGraphs.map { it.graphId }}")
    } else {
        println("No custom layouts")
    }
}