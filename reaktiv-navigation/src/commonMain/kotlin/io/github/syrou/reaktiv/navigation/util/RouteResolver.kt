package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.model.ScreenResolution


class RouteResolver private constructor(
    private val routeToNavigatable: Map<String, Navigatable>,
    private val navigatableToFullPath: Map<Navigatable, String>,
    private val graphToStartNavigatable: Map<String, ScreenResolution>,
    private val graphHierarchy: Map<String, List<String>>, // graph -> path to root
    private val parentGraphLookup: Map<String, String>, // child -> parent
    private val fullPathToResolution: Map<String, RouteResolution>,
    private val graphDefinitions: Map<String, NavigationGraph>,
    private val parameterizedRouteIndex: Map<ParameterizedRouteKey, List<ParameterizedRouteEntry>>,
    private val notFoundScreen: Screen? = null
) {

    companion object {

        private fun findGraphForNavigatable(
            navigatable: Navigatable?,
            graphDefinitions: Map<String, NavigationGraph>
        ): String? {
            if (navigatable == null) return null
            for ((graphId, graph) in graphDefinitions) {
                if (graph.navigatables.contains(navigatable)) {
                    return graphId
                }
            }
            return null
        }

        fun create(
            graphDefinitions: Map<String, NavigationGraph>,
            notFoundScreen: Screen? = null
        ): RouteResolver {
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>()
            val graphToStartNavigatable = mutableMapOf<String, ScreenResolution>()
            val graphHierarchy = mutableMapOf<String, List<String>>()
            val parentGraphLookup = mutableMapOf<String, String>()
            val fullPathToResolution = mutableMapOf<String, RouteResolution>()
            val parameterizedRouteIndex = mutableMapOf<ParameterizedRouteKey, MutableList<ParameterizedRouteEntry>>()
            for ((parentId, parentGraph) in graphDefinitions) {
                for (nestedGraph in parentGraph.nestedGraphs) {
                    parentGraphLookup[nestedGraph.route] = parentId
                }
            }
            for (graphId in graphDefinitions.keys) {
                graphHierarchy[graphId] = buildHierarchyPath(graphId, parentGraphLookup)
            }
            for ((graphId, graph) in graphDefinitions) {
                val graphPath = buildGraphPath(graphId, graphHierarchy)
                for (navigatable in graph.navigatables) {
                    val fullPath = if (graphPath.isEmpty()) navigatable.route else "$graphPath/${navigatable.route}"

                    // Only register by full path to avoid route collisions between graphs
                    // For root graph screens, fullPath == navigatable.route (no prefix)
                    if (routeToNavigatable.containsKey(fullPath)) {
                        val existing = routeToNavigatable[fullPath]
                        throw IllegalStateException(
                            "Route collision detected: '$fullPath' is already registered to " +
                            "'${existing?.route}' in graph '${findGraphForNavigatable(existing, graphDefinitions)}'. " +
                            "Each screen must have a unique full path."
                        )
                    }
                    routeToNavigatable[fullPath] = navigatable
                    navigatableToFullPath[navigatable] = fullPath

                    if (navigatable.route.contains("{")) {
                        val entry = ParameterizedRouteEntry(
                            template = navigatable.route,
                            fullTemplate = fullPath,
                            navigatable = navigatable,
                            graphId = graphId,
                            paramNames = extractParameterNames(navigatable.route),
                            regex = createRouteRegex(fullPath)
                        )
                        val key = ParameterizedRouteKey.fromTemplate(fullPath)
                        parameterizedRouteIndex.getOrPut(key) { mutableListOf() }.add(entry)

                        ReaktivDebug.nav("📝 Added parameterized route: $fullPath -> ${navigatable.route} (key: $key)")
                    } else {
                        fullPathToResolution[fullPath] = RouteResolution(
                            targetNavigatable = navigatable,
                            targetGraphId = graphId,
                            extractedParams = Params.empty(),
                            isGraphReference = false
                        )
                    }
                }
                val startResolution = resolveGraphStartNavigatable(graph, graphDefinitions)
                if (startResolution != null) {
                    graphToStartNavigatable[graphId] = startResolution
                    val graphRouteResolution = RouteResolution(
                        targetNavigatable = startResolution.navigatable,
                        targetGraphId = startResolution.actualGraphId,
                        extractedParams = Params.empty(),
                        navigationGraphId = graphId,
                        isGraphReference = graph.startDestination is StartDestination.GraphReference
                    )

                    fullPathToResolution[graphId] = graphRouteResolution
                    if (graphPath.isNotEmpty()) {
                        fullPathToResolution[graphPath] = graphRouteResolution
                    }
                }
            }

            val totalParameterizedRoutes = parameterizedRouteIndex.values.sumOf { it.size }
            ReaktivDebug.nav("🎯 Created $totalParameterizedRoutes parameterized route patterns in ${parameterizedRouteIndex.size} index buckets")
            parameterizedRouteIndex.forEach { (key, routes) ->
                routes.forEach { route ->
                    ReaktivDebug.nav("   - ${route.fullTemplate} (params: ${route.paramNames}, key: $key)")
                }
            }

            return RouteResolver(
                routeToNavigatable = routeToNavigatable,
                navigatableToFullPath = navigatableToFullPath,
                graphToStartNavigatable = graphToStartNavigatable,
                graphHierarchy = graphHierarchy,
                parentGraphLookup = parentGraphLookup,
                fullPathToResolution = fullPathToResolution,
                graphDefinitions = graphDefinitions,
                parameterizedRouteIndex = parameterizedRouteIndex,
                notFoundScreen = notFoundScreen
            )
        }

        private fun buildHierarchyPath(graphId: String, parentLookup: Map<String, String>): List<String> {
            val path = mutableListOf<String>()
            var current: String? = graphId

            while (current != null && current != "root") {
                path.add(0, current)
                current = parentLookup[current]
            }

            return path
        }

        private fun buildGraphPath(graphId: String, hierarchies: Map<String, List<String>>): String {
            if (graphId == "root") return ""
            val hierarchy = hierarchies[graphId] ?: return ""
            return hierarchy.filter { it != "root" }.joinToString("/")
        }

        private fun resolveGraphStartNavigatable(
            graph: NavigationGraph,
            allGraphs: Map<String, NavigationGraph>
        ): ScreenResolution? {
            return when (val dest = graph.startDestination) {
                is StartDestination.DirectScreen -> ScreenResolution(
                    navigatable = dest.screen,
                    actualGraphId = graph.route
                )
                is StartDestination.GraphReference -> {
                    val referencedGraph = allGraphs[dest.graphId]
                    referencedGraph?.let { resolveGraphStartNavigatable(it, allGraphs) }
                }
                null -> null
            }
        }

        private fun extractParameterNames(route: String): List<String> {
            val paramRegex = Regex("\\{([^}]+)\\}")
            return paramRegex.findAll(route).map { it.groupValues[1] }.toList()
        }

        private fun createRouteRegex(route: String): Regex {
            val escapedRoute = route
                .replace(".", "\\.")
                .replace("+", "\\+")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("|", "\\|")
            val pattern = escapedRoute.replace(Regex("\\{[^}]+\\}"), "([^/]+)")

            ReaktivDebug.nav("📝 Created regex pattern: ^$pattern$ for route: $route")
            return Regex("^$pattern$")
        }
    }

    
    fun resolve(
        route: String,
        availableNavigatables: Map<String, Navigatable> = emptyMap()
    ): RouteResolution? {
        val cleanRoute = route.trimStart('/').trimEnd('/')
        if (cleanRoute.isEmpty()) return null

        ReaktivDebug.nav("🔍 Resolving route: '$cleanRoute'")
        fullPathToResolution[cleanRoute]?.let {
            ReaktivDebug.nav("✅ Direct full path lookup found: $cleanRoute")
            return it
        }
        routeToNavigatable[cleanRoute]?.let { navigatable ->
            ReaktivDebug.nav("✅ Direct route-to-navigatable lookup found: $cleanRoute")
            val graphId = findGraphForNavigatable(navigatable) ?: "root"
            return RouteResolution(
                targetNavigatable = navigatable,
                targetGraphId = graphId,
                extractedParams = Params.empty(),
                isGraphReference = false
            )
        }
        graphToStartNavigatable[cleanRoute]?.let { startResolution ->
            ReaktivDebug.nav("✅ Graph start destination found: $cleanRoute")
            return RouteResolution(
                targetNavigatable = startResolution.navigatable,
                targetGraphId = startResolution.actualGraphId,
                extractedParams = Params.empty(),
                navigationGraphId = cleanRoute,
                isGraphReference = graphDefinitions[cleanRoute]?.startDestination is StartDestination.GraphReference
            )
        }

        // Check if this is a graph without a startDestination - redirect to notFoundScreen
        if (graphDefinitions.containsKey(cleanRoute) && graphDefinitions[cleanRoute]?.startDestination == null) {
            ReaktivDebug.nav("⚠️ Graph '$cleanRoute' has no startDestination defined")
            notFoundScreen?.let { screen ->
                ReaktivDebug.nav("🔄 Redirecting to notFoundScreen for graph: $cleanRoute")
                return RouteResolution(
                    targetNavigatable = screen,
                    targetGraphId = "root",
                    extractedParams = Params.empty(),
                    navigationGraphId = cleanRoute,
                    isGraphReference = false
                )
            }
        }

        availableNavigatables[cleanRoute]?.let { navigatable ->
            ReaktivDebug.nav("✅ Root navigatable found in provided map: $cleanRoute")
            return RouteResolution(
                targetNavigatable = navigatable,
                targetGraphId = "root",
                extractedParams = Params.empty(),
                isGraphReference = false
            )
        }

        // Check parameterized routes using index for O(1) candidate lookup
        val routeKey = ParameterizedRouteKey.fromRoute(cleanRoute)
        val candidates = parameterizedRouteIndex[routeKey]
        if (candidates != null) {
            val parameterizedResult = matchParameterizedCandidates(cleanRoute, candidates)
            if (parameterizedResult != null) {
                ReaktivDebug.nav("✅ Parameterized route found: $cleanRoute -> ${parameterizedResult.targetNavigatable.route}")
                return parameterizedResult
            }
        }

        // Fallback: Try to find by simple route name (for backward compatibility)
        val simpleRouteResult = resolveSimpleRoute(cleanRoute)
        if (simpleRouteResult != null) {
            return simpleRouteResult
        }

        ReaktivDebug.nav("❌ Route not found: $cleanRoute")

        // Return notFoundScreen resolution if configured
        return notFoundScreen?.let { screen ->
            ReaktivDebug.nav("🔄 Redirecting to notFoundScreen for: $cleanRoute")
            RouteResolution(
                targetNavigatable = screen,
                targetGraphId = "root",
                extractedParams = Params.empty(),
                isGraphReference = false
            )
        }
    }

    /**
     * Fallback resolution for simple route names (without full path).
     * If exactly one navigatable matches the simple route, returns it with a deprecation warning.
     * If multiple navigatables match, logs an error with disambiguation help.
     */
    private fun resolveSimpleRoute(simpleRoute: String): RouteResolution? {
        val matches = navigatableToFullPath.entries
            .filter { (navigatable, _) -> navigatable.route == simpleRoute }
            .map { (navigatable, fullPath) -> navigatable to fullPath }

        return when {
            matches.isEmpty() -> null

            matches.size == 1 -> {
                val (navigatable, fullPath) = matches.first()
                val graphId = findGraphForNavigatable(navigatable) ?: "root"

                // Log deprecation warning
                println(
                    "⚠️ Navigation Warning: Using simple route '$simpleRoute' is deprecated. " +
                    "Please use the full path '$fullPath' or type-safe navigation " +
                    "(e.g., navigateTo<${navigatable::class.simpleName}>()) instead. " +
                    "Simple route resolution may be removed in a future version."
                )
                ReaktivDebug.nav("⚠️ Simple route fallback used: '$simpleRoute' -> '$fullPath'")

                RouteResolution(
                    targetNavigatable = navigatable,
                    targetGraphId = graphId,
                    extractedParams = Params.empty(),
                    isGraphReference = false
                )
            }

            else -> {
                val paths = matches.map { it.second }
                println(
                    "❌ Navigation Error: Ambiguous route '$simpleRoute' matches multiple screens: " +
                    "${paths.joinToString(", ")}. " +
                    "Please use the full path or type-safe navigation to disambiguate."
                )
                ReaktivDebug.nav("❌ Ambiguous simple route: '$simpleRoute' matches: $paths")
                null
            }
        }
    }

    
    private fun findGraphForNavigatable(navigatable: Navigatable): String? {
        for ((graphId, graph) in graphDefinitions) {
            if (graph.navigatables.contains(navigatable)) {
                return graphId
            }
        }
        return null
    }

    private fun matchParameterizedCandidates(
        route: String,
        candidates: List<ParameterizedRouteEntry>
    ): RouteResolution? {
        ReaktivDebug.nav("🔍 Matching parameterized route: $route (${candidates.size} candidates)")

        for (paramRoute in candidates) {
            ReaktivDebug.nav("   Testing against template: ${paramRoute.fullTemplate}")

            val match = paramRoute.regex.find(route)
            if (match != null) {
                val paramsMap = mutableMapOf<String, Any>()
                match.groupValues.drop(1).forEachIndexed { index, value ->
                    if (index < paramRoute.paramNames.size) {
                        paramsMap[paramRoute.paramNames[index]] = value
                        ReaktivDebug.nav("   Extracted param: ${paramRoute.paramNames[index]} = $value")
                    }
                }
                val params = Params.fromMap(paramsMap)

                ReaktivDebug.nav("✅ Parameterized match found: ${paramRoute.fullTemplate}")

                return RouteResolution(
                    targetNavigatable = paramRoute.navigatable,
                    targetGraphId = paramRoute.graphId,
                    extractedParams = params,
                    isGraphReference = false
                )
            }
        }

        ReaktivDebug.nav("❌ No parameterized route match in candidates")
        return null
    }

    
    fun findPathToNavigatable(
        navigatable: Navigatable,
        preferredGraphId: String? = null
    ): String? {
        return navigatableToFullPath[navigatable]
    }

    
    fun findRouteInBackStack(
        targetRoute: String,
        backStack: List<NavigationEntry>
    ): Int {
        val directMatch = backStack.indexOfLast { it.navigatable.route == targetRoute }
        if (directMatch != -1) return directMatch
        val targetResolution = resolve(targetRoute)
        if (targetResolution != null) {
            val resolvedMatch = backStack.indexOfLast { entry ->
                entry.navigatable.route == targetResolution.targetNavigatable.route &&
                        entry.graphId == targetResolution.getEffectiveGraphId()
            }
            if (resolvedMatch != -1) return resolvedMatch
        }
        return backStack.indexOfLast { entry ->
            val fullPath = buildFullPathForEntry(entry)
            fullPath == targetRoute || fullPath.endsWith("/$targetRoute")
        }
    }

    
    fun buildFullPathForEntry(entry: NavigationEntry): String {
        val graphPath = graphHierarchy[entry.graphId]?.filter { it != "root" }?.joinToString("/") ?: ""
        val navigatablePath = substituteRouteParameters(entry.navigatable.route, entry.params)

        return if (graphPath.isEmpty()) {
            navigatablePath
        } else if (navigatablePath != entry.graphId) {
            "$graphPath/$navigatablePath"
        } else {
            graphPath
        }
    }

    private fun substituteRouteParameters(routeTemplate: String, params: Params): String {
        var resolvedRoute = routeTemplate
        val paramRegex = Regex("\\{([^}]+)\\}")

        for (match in paramRegex.findAll(routeTemplate)) {
            val placeholder = match.value
            val paramName = match.groupValues[1]
            val paramValue = params.getString(paramName)

            if (paramValue != null) {
                resolvedRoute = resolvedRoute.replace(placeholder, paramValue)
            }
        }

        return resolvedRoute
    }

    fun getNavigatableNotFoundHint(
        navigatable: Navigatable
    ): String {
        val allRoutes = fullPathToResolution.keys.sorted()
        return "Navigatable with route '${navigatable.route}' not found. Available routes: ${allRoutes.take(10).joinToString(", ")}${if (allRoutes.size > 10) "..." else ""}"
    }

    fun findParentGraphId(graphId: String): String? = parentGraphLookup[graphId]

    /**
     * Check if a path resolves to a valid backstack entry.
     * Returns the resolution if:
     * - It's a Screen
     * - It's a Graph with a startDestination (resolves to a screen)
     * Returns null if:
     * - Path doesn't resolve
     * - It's an umbrella graph (no startDestination)
     */
    fun resolveForBackstackSynthesis(path: String): RouteResolution? {
        val cleanPath = path.trimStart('/').trimEnd('/')
        if (cleanPath.isEmpty()) return null

        if (graphDefinitions.containsKey(cleanPath)) {
            val graph = graphDefinitions[cleanPath]
            if (graph?.startDestination == null) {
                return null
            }
        }

        val resolution = resolve(cleanPath)

        if (resolution != null && notFoundScreen != null && resolution.targetNavigatable == notFoundScreen) {
            return null
        }

        return resolution
    }

    /**
     * Build the hierarchy of paths for backstack synthesis.
     * For a path like "auth/signup/verify", returns ["auth", "auth/signup", "auth/signup/verify"]
     */
    fun buildPathHierarchy(fullPath: String): List<String> {
        val cleanPath = fullPath.trimStart('/').trimEnd('/')
        if (cleanPath.isEmpty()) return emptyList()

        val segments = cleanPath.split("/")
        return segments.indices.map { i ->
            segments.take(i + 1).joinToString("/")
        }
    }
}


private data class ParameterizedRouteEntry(
    val template: String,
    val fullTemplate: String,
    val navigatable: Navigatable,
    val graphId: String,
    val paramNames: List<String>,
    val regex: Regex
)

/**
 * Key for indexing parameterized routes by segment count and first segment.
 * This enables O(1) lookup to find candidate routes instead of iterating all routes.
 */
private data class ParameterizedRouteKey(
    val segmentCount: Int,
    val firstSegment: String
) {
    companion object {
        /**
         * Creates a key from a route template like "user/{id}" or "home/dashboard/{tab}".
         * Extracts the segment count and first static segment.
         */
        fun fromTemplate(template: String): ParameterizedRouteKey {
            val segments = template.split("/")
            val firstSegment = segments.firstOrNull()?.takeIf { !it.startsWith("{") } ?: ""
            return ParameterizedRouteKey(
                segmentCount = segments.size,
                firstSegment = firstSegment
            )
        }

        /**
         * Creates a key from an actual route like "user/123" or "home/dashboard/settings".
         * Extracts the segment count and first segment for index lookup.
         */
        fun fromRoute(route: String): ParameterizedRouteKey {
            val segments = route.split("/")
            return ParameterizedRouteKey(
                segmentCount = segments.size,
                firstSegment = segments.firstOrNull() ?: ""
            )
        }
    }
}