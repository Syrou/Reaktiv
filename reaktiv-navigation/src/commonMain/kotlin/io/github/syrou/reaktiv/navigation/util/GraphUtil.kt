package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph

public fun findLayoutGraphsInHierarchy(
    currentGraphId: String,
    graphDefinitions: Map<String, NavigationGraph>
): List<NavigationGraph> = GraphIndex.of(graphDefinitions).layoutsAround(currentGraphId)

public fun buildGraphHierarchyPath(
    graphId: String,
    graphDefinitions: Map<String, NavigationGraph>
): List<NavigationGraph> = GraphIndex.of(graphDefinitions).layoutChain(graphId)

public fun findParentGraph(
    targetGraph: NavigationGraph,
    graphDefinitions: Map<String, NavigationGraph>
): NavigationGraph? = GraphIndex.of(graphDefinitions).parent(targetGraph.route)?.let { graphDefinitions[it] }
