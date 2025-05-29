package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen

/**
 * Recursively composes layouts from outer to inner
 */
@Composable
fun ComposeHierarchicalLayouts(
    layoutGraphs: List<NavigationGraph>,
    modifier: Modifier,
    navigationState: NavigationState,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit,
    currentIndex: Int = 0
) {
    println("DEBUG: ComposeHierarchicalLayouts - index: $currentIndex, total: ${layoutGraphs.size}")

    if (currentIndex >= layoutGraphs.size) {
        println("DEBUG: Reached end of layouts, rendering final content")
        // Base case: render the actual content
        GraphNavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
        return
    }

    val currentGraph = layoutGraphs[currentIndex]
    val layout = currentGraph.layout

    println("DEBUG: Processing graph: ${currentGraph.graphId}, has layout: ${layout != null}")

    if (layout != null) {
        // Apply current layout and recurse for nested layouts
        layout {
            println("DEBUG: Applying layout for graph: ${currentGraph.graphId}")
            ComposeHierarchicalLayouts(
                layoutGraphs = layoutGraphs,
                modifier = modifier,
                navigationState = navigationState,
                screenContent = screenContent,
                currentIndex = currentIndex + 1
            )
        }
    } else {
        // This shouldn't happen since we filtered for layout graphs, but handle it gracefully
        println("DEBUG: WARNING - Graph ${currentGraph.graphId} in layoutGraphs but has no layout")
        ComposeHierarchicalLayouts(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent,
            currentIndex = currentIndex + 1
        )
    }
}