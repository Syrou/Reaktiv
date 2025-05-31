package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.MutableNavigationGraph
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.RouteResolution

object SimpleRouteResolver {

    fun resolve(
        route: String,
        graphDefinitions: Map<String, NavigationGraph>,
        availableScreens: Map<String, Screen>
    ): RouteResolution? {

        val cleanRoute = route.trimStart('/').trimEnd('/')
        if (cleanRoute.isEmpty()) return null

        println("DEBUG: 🔍 Resolving route: '$cleanRoute'")

        // Split route into segments
        val segments = cleanRoute.split("/").filter { it.isNotEmpty() }

        // Try to resolve as graph path first, then fall back to screen search
        return resolveGraphPath(segments, graphDefinitions)
            ?: findScreenInAllGraphs(cleanRoute, graphDefinitions)
            ?: findScreenInFlatScreens(cleanRoute, availableScreens)
    }

    /**
     * Resolve a route as a graph path
     * Examples:
     * - "home" -> find home graph, use its start screen (may be startGraph reference)
     * - "home/workspace" -> find workspace graph in home, use its start screen
     * - "home/workspace/projects/overview" -> find projects graph, find overview screen
     */
    private fun resolveGraphPath(
        segments: List<String>,
        graphDefinitions: Map<String, NavigationGraph>
    ): RouteResolution? {

        if (segments.isEmpty()) return null

        // Start from root and traverse the graph hierarchy
        var currentGraph = graphDefinitions["root"] ?: return null
        var segmentIndex = 0

        // Traverse through graph segments
        while (segmentIndex < segments.size) {
            val segment = segments[segmentIndex]

            // Try to find nested graph with this segment name
            val nestedGraph = currentGraph.nestedGraphs.find { it.graphId == segment }

            if (nestedGraph != null) {
                // Found a graph with this name
                currentGraph = nestedGraph
                segmentIndex++

                // Check if we have more segments
                if (segmentIndex < segments.size) {
                    // More segments remain, continue traversing
                    continue
                } else {
                    // This is the final segment - it's a graph name
                    // Resolve the start screen for this graph
                    val startScreen = resolveGraphStartScreen(currentGraph, graphDefinitions)
                    if (startScreen != null) {
                        println("DEBUG: ✅ Resolved graph path '$segments' to graph '${currentGraph.graphId}' with start screen '${startScreen.route}'")
                        return RouteResolution(
                            targetScreen = startScreen,
                            targetGraphId = getActualGraphId(currentGraph, startScreen, graphDefinitions),
                            extractedParams = emptyMap()
                        )
                    }
                }
            } else {
                // No nested graph found, remaining segments must be a screen route
                val remainingPath = segments.drop(segmentIndex).joinToString("/")
                val screenMatch = findScreenInGraph(remainingPath, currentGraph)

                if (screenMatch != null) {
                    println("DEBUG: ✅ Resolved path '$segments' to screen '${screenMatch.first.route}' in graph '${currentGraph.graphId}'")
                    return RouteResolution(
                        targetScreen = screenMatch.first,
                        targetGraphId = currentGraph.graphId,
                        extractedParams = screenMatch.second
                    )
                }

                // Screen not found in current graph
                break
            }
        }

        return null
    }

    /**
     * Resolve the start screen for a graph, handling startGraph references
     */
    private fun resolveGraphStartScreen(
        graph: NavigationGraph,
        graphDefinitions: Map<String, NavigationGraph>
    ): Screen? {

        // Check if this graph uses startGraph reference
        if (graph is MutableNavigationGraph && graph.usesStartGraph()) {
            val startGraphId = graph.startGraphId
            if (startGraphId != null) {
                println("DEBUG: 🔗 Graph '${graph.graphId}' uses startGraph reference to '$startGraphId'")

                // Find the referenced graph
                val referencedGraph = findGraphById(startGraphId, graphDefinitions)
                if (referencedGraph != null) {
                    // Recursively resolve the referenced graph's start screen
                    return resolveGraphStartScreen(referencedGraph, graphDefinitions)
                } else {
                    println("DEBUG: ⚠️ Referenced graph '$startGraphId' not found")
                }
            }
        }

        // Use the graph's direct start screen
        return graph.startScreen
    }

    /**
     * Get the actual graph ID where the screen belongs
     * This is important for layout rendering
     */
    private fun getActualGraphId(
        currentGraph: NavigationGraph,
        targetScreen: Screen,
        graphDefinitions: Map<String, NavigationGraph>
    ): String {

        // If current graph uses startGraph, find where the screen actually belongs
        if (currentGraph is MutableNavigationGraph && currentGraph.usesStartGraph()) {
            val startGraphId = currentGraph.startGraphId
            if (startGraphId != null) {
                val referencedGraph = findGraphById(startGraphId, graphDefinitions)
                if (referencedGraph != null && referencedGraph.getAllScreens().containsKey(targetScreen.route)) {
                    return referencedGraph.graphId
                }
            }
        }

        // Default to current graph
        return currentGraph.graphId
    }

    /**
     * Find a graph by ID in the entire graph hierarchy
     */
    private fun findGraphById(
        graphId: String,
        graphDefinitions: Map<String, NavigationGraph>
    ): NavigationGraph? {
        return graphDefinitions[graphId]
    }

    /**
     * Search for a screen in all graphs (fallback)
     */
    private fun findScreenInAllGraphs(
        route: String,
        graphDefinitions: Map<String, NavigationGraph>
    ): RouteResolution? {

        for ((graphId, graph) in graphDefinitions) {
            val screenMatch = findScreenInGraph(route, graph)
            if (screenMatch != null) {
                println("DEBUG: ✅ Found screen '$route' in graph '$graphId' via fallback search")
                return RouteResolution(
                    targetScreen = screenMatch.first,
                    targetGraphId = graphId,
                    extractedParams = screenMatch.second
                )
            }
        }

        return null
    }

    /**
     * Find screen in flat available screens (non-nested)
     */
    private fun findScreenInFlatScreens(
        route: String,
        availableScreens: Map<String, Screen>
    ): RouteResolution? {

        availableScreens[route]?.let { screen ->
            println("DEBUG: ✅ Found screen '$route' in flat screens")
            return RouteResolution(
                targetScreen = screen,
                targetGraphId = "root", // Default to root for flat screens
                extractedParams = emptyMap()
            )
        }

        return null
    }

    /**
     * Find screen within a specific graph, handling parameterized routes
     */
    private fun findScreenInGraph(route: String, graph: NavigationGraph): Pair<Screen, Map<String, Any>>? {
        val allScreens = graph.getAllScreens()

        // Direct match first
        allScreens[route]?.let { return it to emptyMap() }

        // Try parameterized route matching
        val routeParts = route.split("/")

        for ((screenRoute, screen) in allScreens) {
            val screenRouteParts = screenRoute.split("/")
            if (screenRouteParts.size != routeParts.size) continue

            val params = mutableMapOf<String, Any>()
            var matches = true

            for ((screenPart, routePart) in screenRouteParts.zip(routeParts)) {
                when {
                    screenPart == routePart -> continue
                    screenPart.startsWith("{") && screenPart.endsWith("}") -> {
                        val paramName = screenPart.substring(1, screenPart.length - 1)
                        params[paramName] = routePart
                    }
                    else -> {
                        matches = false
                        break
                    }
                }
            }

            if (matches) {
                return screen to params
            }
        }

        return null
    }
}