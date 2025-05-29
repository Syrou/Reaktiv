package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.RouteResolution

object IntelligentRouteResolver {
    
    /**
     * Resolves a route to the correct graph and screen, handling both:
     * - Full paths: "home/workspace/projects/overview" 
     * - Context-specific: "overview" (when in correct graph)
     */
    fun resolve(
        route: String,
        currentGraphId: String,
        graphDefinitions: Map<String, NavigationGraph>,
        availableScreens: Map<String, Screen>
    ): RouteResolution? {
        
        println("DEBUG: Resolving route '$route' from current graph '$currentGraphId'")
        
        // Clean the route
        val cleanRoute = route.trimStart('/').trimEnd('/')
        if (cleanRoute.isEmpty()) return null
        
        // Split into path segments
        val segments = cleanRoute.split("/").filter { it.isNotEmpty() }
        println("DEBUG: Route segments: $segments")
        
        // Try different resolution strategies
        return resolveFullPath(segments, graphDefinitions, availableScreens, currentGraphId)
            ?: resolveContextSpecific(cleanRoute, currentGraphId, graphDefinitions, availableScreens)
            ?: resolveByScreenSearch(cleanRoute, graphDefinitions, availableScreens, currentGraphId)
    }
    
    /**
     * Strategy 1: Resolve as full path (e.g., "home/workspace/projects/overview")
     */
    private fun resolveFullPath(
        segments: List<String>,
        graphDefinitions: Map<String, NavigationGraph>,
        availableScreens: Map<String, Screen>,
        currentGraphId: String
    ): RouteResolution? {
        
        if (segments.size < 2) return null // Need at least graph/screen
        
        // Try different combinations of graph path vs screen route
        for (i in 1 until segments.size) {
            val graphPath = segments.take(i)
            val screenPath = segments.drop(i).joinToString("/")
            
            println("DEBUG: Trying graph path: $graphPath, screen path: $screenPath")
            
            // Find the target graph
            val targetGraph = findGraphByPath(graphPath, graphDefinitions)
            if (targetGraph != null) {
                // Look for the screen in this graph
                val screen = findScreenInGraph(screenPath, targetGraph)
                if (screen != null) {
                    val (finalScreenRoute, params) = extractScreenParams(screenPath, targetGraph)
                    
                    println("DEBUG: ✅ Full path resolved - Graph: ${targetGraph.graphId}, Screen: $finalScreenRoute")
                    return RouteResolution(
                        targetGraph = targetGraph,
                        targetScreen = screen,
                        screenRoute = finalScreenRoute,
                        extractedParams = params,
                        requiresGraphSwitch = targetGraph.graphId != currentGraphId
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Strategy 2: Resolve within current graph context
     */
    private fun resolveContextSpecific(
        route: String,
        currentGraphId: String,
        graphDefinitions: Map<String, NavigationGraph>,
        availableScreens: Map<String, Screen>
    ): RouteResolution? {
        
        val currentGraph = graphDefinitions[currentGraphId] ?: return null
        val screen = findScreenInGraph(route, currentGraph)
        
        if (screen != null) {
            val (finalScreenRoute, params) = extractScreenParams(route, currentGraph)
            
            println("DEBUG: ✅ Context-specific resolved in graph: $currentGraphId, Screen: $finalScreenRoute")
            return RouteResolution(
                targetGraph = currentGraph,
                targetScreen = screen,
                screenRoute = finalScreenRoute,
                extractedParams = params,
                requiresGraphSwitch = false
            )
        }
        
        return null
    }
    
    /**
     * Strategy 3: Search all graphs for the screen (fallback)
     */
    private fun resolveByScreenSearch(
        route: String,
        graphDefinitions: Map<String, NavigationGraph>,
        availableScreens: Map<String, Screen>,
        currentGraphId: String
    ): RouteResolution? {
        
        // Search through all graphs to find the screen
        for ((graphId, graph) in graphDefinitions) {
            val screen = findScreenInGraph(route, graph)
            if (screen != null) {
                val (finalScreenRoute, params) = extractScreenParams(route, graph)
                
                println("DEBUG: ✅ Found by search in graph: $graphId, Screen: $finalScreenRoute")
                return RouteResolution(
                    targetGraph = graph,
                    targetScreen = screen,
                    screenRoute = finalScreenRoute,
                    extractedParams = params,
                    requiresGraphSwitch = graphId != currentGraphId
                )
            }
        }
        
        // Also check flat screens (non-nested)
        availableScreens[route]?.let { screen ->
            // This is a flat screen, create a virtual root graph resolution
            val rootGraph = graphDefinitions["root"] ?: graphDefinitions.values.first()
            
            println("DEBUG: ✅ Found flat screen: $route")
            return RouteResolution(
                targetGraph = rootGraph,
                targetScreen = screen,
                screenRoute = route,
                extractedParams = emptyMap(),
                requiresGraphSwitch = rootGraph.graphId != currentGraphId
            )
        }
        
        return null
    }
    
    /**
     * Find a graph by traversing the path segments
     */
    private fun findGraphByPath(
        pathSegments: List<String>,
        graphDefinitions: Map<String, NavigationGraph>
    ): NavigationGraph? {
        
        // Try exact match first
        val pathString = pathSegments.joinToString("/")
        graphDefinitions[pathString]?.let { return it }
        
        // Try individual segments
        for (segment in pathSegments.reversed()) {
            graphDefinitions[segment]?.let { return it }
        }
        
        // Try nested traversal
        var currentGraph = graphDefinitions["root"] ?: graphDefinitions.values.firstOrNull()
        
        for (segment in pathSegments) {
            currentGraph = currentGraph?.nestedGraphs?.find { it.graphId == segment }
            if (currentGraph == null) break
        }
        
        return currentGraph
    }
    
    /**
     * Find a screen within a specific graph, handling parameterized routes
     */
    private fun findScreenInGraph(route: String, graph: NavigationGraph): Screen? {
        val allScreens = graph.getAllScreens()
        
        // Direct match first
        allScreens[route]?.let { return it }
        
        // Try parameterized route matching
        val routeParts = route.split("/")
        
        return allScreens.values.find { screen ->
            val screenRouteParts = screen.route.split("/")
            if (screenRouteParts.size != routeParts.size) return@find false
            
            screenRouteParts.zip(routeParts).all { (screenPart, routePart) ->
                screenPart == routePart || (screenPart.startsWith("{") && screenPart.endsWith("}"))
            }
        }
    }
    
    /**
     * Extract parameters from parameterized routes
     */
    private fun extractScreenParams(route: String, graph: NavigationGraph): Pair<String, Map<String, Any>> {
        val allScreens = graph.getAllScreens()
        val routeParts = route.split("/")
        
        // Find matching parameterized screen
        val matchingScreen = allScreens.values.find { screen ->
            val screenRouteParts = screen.route.split("/")
            if (screenRouteParts.size != routeParts.size) return@find false
            
            screenRouteParts.zip(routeParts).all { (screenPart, routePart) ->
                screenPart == routePart || (screenPart.startsWith("{") && screenPart.endsWith("}"))
            }
        }
        
        if (matchingScreen != null) {
            val params = mutableMapOf<String, Any>()
            val screenRouteParts = matchingScreen.route.split("/")
            
            screenRouteParts.zip(routeParts).forEach { (screenPart, routePart) ->
                if (screenPart.startsWith("{") && screenPart.endsWith("}")) {
                    val paramName = screenPart.substring(1, screenPart.length - 1)
                    params[paramName] = routePart
                }
            }
            
            return Pair(matchingScreen.route, params)
        }
        
        // No parameterized match found, return as-is
        return Pair(route, emptyMap())
    }
}