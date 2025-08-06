package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph

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
        path.add(0, currentGraph)
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