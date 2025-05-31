package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.ScreenResolution

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
     * Resolve a route as a graph path with proper layout context tracking
     */
    private fun resolveGraphPath(
        segments: List<String>,
        graphDefinitions: Map<String, NavigationGraph>
    ): RouteResolution? {

        if (segments.isEmpty()) return null

        // Start from root and traverse the graph hierarchy
        var currentGraph = graphDefinitions["root"] ?: return null
        var segmentIndex = 0
        var navigationGraphId: String? = null  // Track the graph we're navigating to

        // Traverse through graph segments
        while (segmentIndex < segments.size) {
            val segment = segments[segmentIndex]

            // Try to find nested graph with this segment name
            val nestedGraph = currentGraph.nestedGraphs.find { it.graphId == segment }

            if (nestedGraph != null) {
                // Found a graph with this name
                currentGraph = nestedGraph
                navigationGraphId = nestedGraph.graphId  // This is the graph being navigated to
                segmentIndex++

                // Check if we have more segments
                if (segmentIndex < segments.size) {
                    // More segments remain, continue traversing
                    continue
                } else {
                    // This is the final segment - it's a graph name
                    // Resolve the start screen for this graph
                    val screenResolution = resolveGraphStartScreen(currentGraph, graphDefinitions)
                    if (screenResolution != null) {
                        println("DEBUG: ✅ Resolved graph path '$segments' to screen '${screenResolution.screen.route}' (nav graph: $navigationGraphId, target graph: ${screenResolution.actualGraphId})")

                        return RouteResolution(
                            targetScreen = screenResolution.screen,
                            targetGraphId = screenResolution.actualGraphId,
                            extractedParams = emptyMap(),
                            navigationGraphId = navigationGraphId  // Preserve the navigation context
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
                        extractedParams = screenMatch.second,
                        navigationGraphId = navigationGraphId
                    )
                }
                break
            }
        }

        return null
    }

    /**
     * Resolve the start screen for a graph, handling graph references
     * Returns both the screen and the graph where it actually belongs
     */
    private fun resolveGraphStartScreen(
        graph: NavigationGraph,
        graphDefinitions: Map<String, NavigationGraph>
    ): ScreenResolution? {

        return when (val dest = graph.startDestination) {
            is StartDestination.DirectScreen -> {
                println("DEBUG: 🎯 Direct screen: ${dest.screen.route}")
                ScreenResolution(
                    screen = dest.screen,
                    actualGraphId = graph.graphId  // Screen belongs to this graph
                )
            }

            is StartDestination.GraphReference -> {
                println("DEBUG: 🔗 Graph reference from '${graph.graphId}' to '${dest.graphId}'")

                val referencedGraph = graphDefinitions[dest.graphId]
                if (referencedGraph != null) {
                    // Recursively resolve the referenced graph's start screen
                    resolveGraphStartScreen(referencedGraph, graphDefinitions)
                } else {
                    println("DEBUG: ⚠️ Warning: Referenced graph '${dest.graphId}' not found")
                    null
                }
            }
        }
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
                targetGraphId = "root",
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