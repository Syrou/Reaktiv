package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen

@Composable
fun SimplifiedNavigationRender(
    modifier: Modifier,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit
) {
    val navigationState by composeState<NavigationState>()
    
    // Find if current entry's graph has any layouts in the hierarchy
    val layoutGraphs = findLayoutGraphsInHierarchy(navigationState.currentEntry.graphId, navigationState)
    
    if (layoutGraphs.isNotEmpty()) {
        // Apply layouts hierarchically from root to leaf
        ComposeLayoutsHierarchically(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
    } else {
        // No custom layouts, render content directly
        SimpleNavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
    }
}

/**
 * Find all graphs with layouts in the hierarchy path to current graph
 */
@Composable
fun findLayoutGraphsInHierarchy(
    currentGraphId: String,
    navigationState: NavigationState
): List<NavigationGraph> {
    val hierarchyPath = buildGraphHierarchyPath(currentGraphId, navigationState.graphDefinitions)
    return hierarchyPath.filter { it.layout != null }
}

/**
 * Build path from root to current graph
 */
fun buildGraphHierarchyPath(
    targetGraphId: String,
    graphDefinitions: Map<String, NavigationGraph>
): List<NavigationGraph> {
    val targetGraph = graphDefinitions[targetGraphId] ?: return emptyList()

    val path = mutableListOf<NavigationGraph>()
    var currentGraph: NavigationGraph? = targetGraph

    // Build path from target to root
    while (currentGraph != null) {
        path.add(0, currentGraph) // Add to beginning to build root-to-target path
        currentGraph = findParentGraph(currentGraph, graphDefinitions)
    }

    return path
}

/**
 * Find parent graph by searching which graph contains the target as nested
 */
fun findParentGraph(
    targetGraph: NavigationGraph,
    graphDefinitions: Map<String, NavigationGraph>
): NavigationGraph? {
    return graphDefinitions.values.find { graph ->
        graph.nestedGraphs.any { it.graphId == targetGraph.graphId }
    }
}