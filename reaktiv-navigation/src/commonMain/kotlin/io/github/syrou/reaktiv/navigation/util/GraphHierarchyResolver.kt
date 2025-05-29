package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.GraphHierarchy

object GraphHierarchyResolver {

    fun resolveHierarchy(navigationState: NavigationState): GraphHierarchy? {
        if (!navigationState.isNestedNavigation) {
            println("DEBUG: Not nested navigation")
            return null
        }

        val activeGraphId = navigationState.activeGraphId
        val graphDefinitions = navigationState.graphDefinitions

        println("DEBUG: Active graph ID: $activeGraphId")
        println("DEBUG: Available graphs: ${graphDefinitions.keys}")

        // Find the active graph
        val activeGraph = graphDefinitions[activeGraphId]
        if (activeGraph == null) {
            println("DEBUG: Active graph not found in definitions")
            return null
        }

        // Build the path from root to active graph using a simpler approach
        val path = buildHierarchyPath(activeGraph, graphDefinitions)

        println("DEBUG: Resolved hierarchy path: ${path.map { it.graphId }}")
        println("DEBUG: Graphs with layouts: ${path.filter { it.layout != null }.map { it.graphId }}")

        return GraphHierarchy(
            path = path,
            activeGraph = activeGraph
        )
    }

    /**
     * Builds hierarchy path by traversing from active graph up to root, then reversing
     */
    private fun buildHierarchyPath(
        activeGraph: NavigationGraph,
        graphDefinitions: Map<String, NavigationGraph>
    ): List<NavigationGraph> {
        val pathToRoot = mutableListOf<NavigationGraph>()
        var currentGraph: NavigationGraph? = activeGraph

        // Traverse up to root
        while (currentGraph != null) {
            pathToRoot.add(currentGraph)
            currentGraph = findParentGraph(currentGraph, graphDefinitions)
        }

        // Reverse to get root-to-active path
        return pathToRoot.reversed()
    }

    /**
     * Find the parent graph by checking which graph contains the current graph as a nested graph
     */
    private fun findParentGraph(
        targetGraph: NavigationGraph,
        graphDefinitions: Map<String, NavigationGraph>
    ): NavigationGraph? {
        // First try the parentGraph reference if it exists and is valid
        targetGraph.parentGraph?.let { parent ->
            if (graphDefinitions.containsKey(parent.graphId)) {
                return parent
            }
        }

        // If no valid parent reference, search through all graphs
        return graphDefinitions.values.find { graph ->
            graph.nestedGraphs.any { nestedGraph ->
                nestedGraph.graphId == targetGraph.graphId
            }
        }
    }
}
