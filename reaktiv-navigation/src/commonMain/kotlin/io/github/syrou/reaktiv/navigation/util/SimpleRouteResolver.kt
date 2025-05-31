package io.github.syrou.reaktiv.navigation.util

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
        
        // Split route into segments
        val segments = cleanRoute.split("/").filter { it.isNotEmpty() }
        
        // Try to find the screen by traversing the graph hierarchy
        return findScreenInGraphHierarchy(segments, graphDefinitions, availableScreens)
            ?: findScreenInFlatScreens(cleanRoute, availableScreens)
    }
    
    /**
     * Search for screen in nested graph hierarchy
     * Example: "home/workspace/projects/overview" -> traverse home -> workspace -> projects, find overview
     */
    private fun findScreenInGraphHierarchy(
        segments: List<String>,
        graphDefinitions: Map<String, NavigationGraph>,
        availableScreens: Map<String, Screen>
    ): RouteResolution? {
        
        // Try different combinations: more segments = graph path, fewer = screen
        for (graphSegmentCount in (segments.size - 1) downTo 1) {
            val graphPath = segments.take(graphSegmentCount)
            val screenPath = segments.drop(graphSegmentCount).joinToString("/")
            
            val targetGraph = findGraphByPath(graphPath, graphDefinitions)
            if (targetGraph != null) {
                val screenMatch = findScreenInGraph(screenPath, targetGraph)
                if (screenMatch != null) {
                    return RouteResolution(
                        targetScreen = screenMatch.first,
                        targetGraphId = targetGraph.graphId,
                        extractedParams = screenMatch.second
                    )
                }
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
            return RouteResolution(
                targetScreen = screen,
                targetGraphId = "root", // Default to root for flat screens
                extractedParams = emptyMap()
            )
        }
        
        return null
    }
    
    /**
     * Find graph by traversing path segments
     * Example: ["home", "workspace"] -> find "home" graph, then find "workspace" nested graph
     */
    private fun findGraphByPath(
        pathSegments: List<String>,
        graphDefinitions: Map<String, NavigationGraph>
    ): NavigationGraph? {
        
        if (pathSegments.isEmpty()) return null
        
        // Start with root graph or find first segment
        var currentGraph = graphDefinitions["root"] 
            ?: graphDefinitions[pathSegments.first()]
            ?: return null
        
        // If we started with root, process all segments, otherwise skip first
        val segmentsToProcess = if (currentGraph.graphId == "root") pathSegments else pathSegments.drop(1)
        
        // Traverse nested graphs
        for (segment in segmentsToProcess) {
            currentGraph = currentGraph.nestedGraphs.find { it.graphId == segment }
                ?: return null
        }
        
        return currentGraph
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