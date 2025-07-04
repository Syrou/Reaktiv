package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger

@Composable
fun NavigationRender(
    modifier: Modifier,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val navigationState by composeState<NavigationState>()
    if(ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState)
    }
    val layoutGraphs = if(navigationState.isCurrentModal) {
        findLayoutGraphsInHierarchy(navigationState.underlyingScreen?.graphId!!, navigationState)
    } else{
        findLayoutGraphsInHierarchy(navigationState.currentEntry.graphId, navigationState)
    }
    
    if (layoutGraphs.isNotEmpty()) {
        RenderLayoutsHierarchically(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
    } else {
        NavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
    }
}


@Composable
fun findLayoutGraphsInHierarchy(
    currentGraphId: String,
    navigationState: NavigationState
): List<NavigationGraph> {
    val hierarchyPath = buildGraphHierarchyPath(currentGraphId, navigationState.graphDefinitions)
    return hierarchyPath.filter { it.layout != null }
}


fun buildGraphHierarchyPath(
    targetGraphId: String,
    graphDefinitions: Map<String, NavigationGraph>
): List<NavigationGraph> {
    val targetGraph = graphDefinitions[targetGraphId] ?: return emptyList()

    val path = mutableListOf<NavigationGraph>()
    var currentGraph: NavigationGraph? = targetGraph
    while (currentGraph != null) {
        path.add(0, currentGraph) // Add to beginning to build root-to-target path
        currentGraph = findParentGraph(currentGraph, graphDefinitions)
    }

    return path
}


fun findParentGraph(
    targetGraph: NavigationGraph,
    graphDefinitions: Map<String, NavigationGraph>
): NavigationGraph? {
    return graphDefinitions.values.find { graph ->
        graph.nestedGraphs.any { it.route == targetGraph.route }
    }
}