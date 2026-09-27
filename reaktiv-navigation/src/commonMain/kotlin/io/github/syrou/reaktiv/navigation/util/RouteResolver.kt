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


public class RouteResolver private constructor(
    private val navigatableToFullPath: Map<Navigatable, String>,
    private val fullPathToResolution: Map<String, RouteResolution>,
    private val graphDefinitions: Map<String, NavigationGraph>,
    private val parameterizedRoutes: Map<Int, List<ParameterizedRouteEntry>>,
    internal val graphIndex: GraphIndex,
    private val notFoundScreen: Screen? = null
) {

    public companion object {

        public fun create(
            graphDefinitions: Map<String, NavigationGraph>,
            routeToNavigatable: Map<String, Navigatable>,
            navigatableToFullPath: Map<Navigatable, String>,
            graphHierarchy: Map<String, List<String>>,
            notFoundScreen: Screen? = null
        ): RouteResolver = create(
            graphDefinitions,
            routeToNavigatable,
            navigatableToFullPath,
            GraphIndex(graphDefinitions, graphHierarchy),
            notFoundScreen
        )

        internal fun create(
            graphDefinitions: Map<String, NavigationGraph>,
            routeToNavigatable: Map<String, Navigatable>,
            navigatableToFullPath: Map<Navigatable, String>,
            graphIndex: GraphIndex,
            notFoundScreen: Screen?
        ): RouteResolver {
            val fullPathToResolution = mutableMapOf<String, RouteResolution>()
            val parameterizedEntries = mutableListOf<ParameterizedRouteEntry>()
            for ((graphId, graph) in graphDefinitions) {
                val graphPath = graphIndex.path(graphId).orEmpty()
                for (navigatable in graph.navigatables) {
                    val ownPath = if (graphPath.isEmpty()) navigatable.route else "$graphPath/${navigatable.route}"
                    val fullPath = ownPath.takeIf { routeToNavigatable[it] === navigatable }
                        ?: navigatableToFullPath[navigatable]
                        ?: continue

                    val template = RouteTemplate.parse(fullPath)
                    if (template.isParameterized) {
                        parameterizedEntries += ParameterizedRouteEntry(
                            template = template,
                            navigatable = navigatable,
                            graphId = graphId
                        )
                        ReaktivDebug.nav("Added parameterized route: $fullPath (params: ${template.paramNames})")
                    } else {
                        fullPathToResolution[fullPath] = RouteResolution(
                            targetNavigatable = navigatable,
                            owningGraphId = graphId,
                            extractedParams = Params.empty(),
                            isGraphReference = false,
                            path = fullPath
                        )
                    }
                }
                val startResolution = resolveGraphStartNavigatable(graph, graphDefinitions)
                if (startResolution != null) {
                    val startGraphPath = graphIndex.path(startResolution.actualGraphId).orEmpty()
                    val startRoute = startResolution.navigatable.route
                    val startPath = if (startGraphPath.isEmpty()) startRoute else "$startGraphPath/$startRoute"
                    val graphRouteResolution = RouteResolution(
                        targetNavigatable = startResolution.navigatable,
                        owningGraphId = startResolution.actualGraphId,
                        extractedParams = Params.empty(),
                        requestedGraphId = graphId,
                        isGraphReference = graph.startDestination is StartDestination.GraphReference,
                        path = startPath.takeIf { routeToNavigatable[it] === startResolution.navigatable }
                    )

                    fullPathToResolution[graphId] = graphRouteResolution
                    if (graphPath.isNotEmpty()) {
                        fullPathToResolution[graphPath] = graphRouteResolution
                    }
                }
            }

            val templateGraphs = parameterizedEntries.associate { it.template.template to it.graphId }
            routeToNavigatable.forEach { (path, navigatable) ->
                fullPathToResolution.getOrPut(path) {
                    RouteResolution(
                        targetNavigatable = navigatable,
                        owningGraphId = templateGraphs[path] ?: ROOT_GRAPH,
                        extractedParams = Params.empty(),
                        isGraphReference = false,
                        path = path
                    )
                }
            }

            warnAboutSameShapeTemplates(parameterizedEntries)
            val parameterizedRoutes = parameterizedEntries
                .sortedWith { a, b -> RouteTemplate.specificity.compare(a.template, b.template) }
                .groupBy { it.template.segmentCount }

            return RouteResolver(
                navigatableToFullPath = navigatableToFullPath,
                fullPathToResolution = fullPathToResolution,
                graphDefinitions = graphDefinitions,
                parameterizedRoutes = parameterizedRoutes,
                graphIndex = graphIndex,
                notFoundScreen = notFoundScreen
            )
        }

        private fun warnAboutSameShapeTemplates(entries: List<ParameterizedRouteEntry>) {
            entries.forEachIndexed { index, first ->
                entries.drop(index + 1)
                    .filter { second -> first.template.sameShapeAs(second.template) }
                    .forEach { second ->
                        ReaktivDebug.warn(
                            "Routes '${first.template.template}' and '${second.template.template}' match the same " +
                                "paths, so a concrete path can only reach one of them. Give one of them a " +
                                "distinct static segment."
                        )
                    }
            }
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

    }

    
    public fun canonicalGraphId(route: String): String? = graphIndex.idForPath(route)

    public fun fullPathForGraph(graphId: String): String? = graphIndex.path(graphId)

    public fun isFullPath(route: String): Boolean {
        val clean = normalizePath(route)
        if (clean.isEmpty()) return false
        if (fullPathToResolution[clean]?.let { it.requestedGraphId == null } == true) return true
        val graphId = canonicalGraphId(clean)
        if (graphId != null) return (fullPathForGraph(graphId) ?: graphId) == clean
        return matchParameterized(clean) != null
    }

    public fun fullPathSuggestions(route: String): List<String> {
        val clean = normalizePath(route)
        if (clean.isEmpty()) return emptyList()
        val screens = navigatableToFullPath.values.filter { it.endsWith("/$clean") }
        val graphs = graphDefinitions.keys.mapNotNull(::fullPathForGraph).filter { it.endsWith("/$clean") }
        return (screens + graphs).distinct().sorted()
    }

    /**
     * Resolves a route or full path to a destination, or `null` when nothing matches.
     *
     * [availableNavigatables] is consulted only after every registry lookup has missed. Passing
     * the registry's own root-navigatable map therefore has no effect, because a root
     * navigatable's full path is its route, so `routeToNavigatable` already answers for it
     * earlier in this function. Callers inside the module pass nothing.
     *
     * @param route A route or slash-separated full path.
     * @param availableNavigatables Extra short-route destinations to fall back on. Deprecated in
     *   effect and removed in a later release together with
     *   `PrecomputedNavigationData.availableNavigatables`.
     */
    public fun resolve(
        route: String,
        availableNavigatables: Map<String, Navigatable> = emptyMap()
    ): RouteResolution? {
        val cleanRoute = normalizePath(route)
        if (cleanRoute.isEmpty()) return null

        ReaktivDebug.nav("Resolving route: '$cleanRoute'")
        fullPathToResolution[cleanRoute]?.let {
            ReaktivDebug.nav("Direct full path lookup found: $cleanRoute")
            return it
        }
        val canonicalId = canonicalGraphId(cleanRoute)
        if (canonicalId != null && graphDefinitions[canonicalId]?.startDestination == null) {
            return null
        }

        availableNavigatables[cleanRoute]?.let { navigatable ->
            ReaktivDebug.nav("Root navigatable found in provided map: $cleanRoute")
            return RouteResolution(
                targetNavigatable = navigatable,
                owningGraphId = ROOT_GRAPH,
                extractedParams = Params.empty(),
                isGraphReference = false
            )
        }

        matchParameterized(cleanRoute)?.let { parameterizedResult ->
            ReaktivDebug.nav("Parameterized route found: $cleanRoute -> ${parameterizedResult.targetNavigatable.route}")
            return parameterizedResult
        }

        // Fallback: Try to find by simple route name (for backward compatibility)
        val simpleRouteResult = resolveSimpleRoute(cleanRoute)
        if (simpleRouteResult != null) {
            return simpleRouteResult
        }

        ReaktivDebug.nav("Route not found: $cleanRoute")
        return null
    }

    /**
     * The resolution for the configured notFound screen, or `null` when none is configured.
     *
     * Call sites that want an unresolvable route to land somewhere rather than fail apply this
     * themselves, so the fallback is visible where it is chosen rather than hidden inside
     * [resolve].
     */
    public fun notFoundResolution(): RouteResolution? = notFoundScreen?.let { screen ->
        RouteResolution(
            targetNavigatable = screen,
            owningGraphId = ROOT_GRAPH,
            extractedParams = Params.empty(),
            isGraphReference = false
        )
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
                val graphId = fullPathToResolution[fullPath]?.owningGraphId ?: ROOT_GRAPH

                ReaktivDebug.warn(
                    "Simple route '$simpleRoute' resolved via fallback to '$fullPath'. " +
                    "Prefer the full path or type-safe navigation " +
                    "(e.g., navigateTo<${navigatable::class.simpleName}>())."
                )

                RouteResolution(
                    targetNavigatable = navigatable,
                    owningGraphId = graphId,
                    extractedParams = Params.empty(),
                    isGraphReference = false
                )
            }

            else -> {
                val paths = matches.map { it.second }
                ReaktivDebug.error(
                    "Ambiguous route '$simpleRoute' matches multiple screens: ${paths.joinToString(", ")}. " +
                    "Use the full path or type-safe navigation to disambiguate."
                )
                null
            }
        }
    }

    
    private fun matchParameterized(route: String): RouteResolution? {
        val candidates = parameterizedRoutes[RouteTemplate.splitPath(route).size] ?: return null
        for (candidate in candidates) {
            val values = candidate.template.match(route) ?: continue
            ReaktivDebug.nav("Parameterized match found: ${candidate.template.template} with $values")
            return RouteResolution(
                targetNavigatable = candidate.navigatable,
                owningGraphId = candidate.graphId,
                extractedParams = Params.fromMap(values),
                isGraphReference = false,
                path = candidate.template.template
            )
        }
        return null
    }

    public fun findRouteInBackStack(
        targetRoute: String,
        backStack: List<NavigationEntry>
    ): Int {
        val targetLocation = RouteTemplate.normalizeLocation(targetRoute)
        val locationMatch = backStack.indexOfLast { it.location == targetLocation }
        if (locationMatch != -1) return locationMatch

        val directRouteMatch = backStack.indexOfLast { it.route == targetRoute }
        if (directRouteMatch != -1) return directRouteMatch

        val directPathMatch = backStack.indexOfLast { it.path == targetRoute }
        if (directPathMatch != -1) return directPathMatch

        val targetResolution = resolve(targetRoute)
        if (targetResolution != null) {
            val resolvedFullPath = targetResolution.path ?: navigatableToFullPath[targetResolution.targetNavigatable]
            if (resolvedFullPath != null) {
                val resolvedMatch = backStack.indexOfLast { it.path == resolvedFullPath }
                if (resolvedMatch != -1) return resolvedMatch
            }
        }

        return backStack.indexOfLast { entry ->
            entry.path == targetRoute || entry.path.endsWith("/$targetRoute")
        }
    }


    public fun buildFullPathForEntry(entry: NavigationEntry): String {
        return RouteTemplate.parse(entry.path).render(entry.params::getString, encoded = false)
    }

    public fun getNavigatableNotFoundHint(
        navigatable: Navigatable
    ): String {
        val allRoutes = fullPathToResolution.keys.sorted()
        return "Navigatable with route '${navigatable.route}' not found. Available routes: ${allRoutes.take(10).joinToString(", ")}${if (allRoutes.size > 10) "..." else ""}"
    }

    /**
     * Check if a path resolves to a valid backstack entry.
     * Returns the resolution if:
     * - It's a Screen
     * - It's a Graph with a startDestination (resolves to a screen)
     * Returns null if:
     * - Path doesn't resolve
     * - It's an umbrella graph (no startDestination)
     */
    public fun resolveForBackstackSynthesis(path: String): RouteResolution? {
        val cleanPath = normalizePath(path)
        if (cleanPath.isEmpty()) return null

        val graphId = canonicalGraphId(cleanPath)
        if (graphId != null && graphDefinitions[graphId]?.startDestination == null) {
            return null
        }

        val resolution = resolve(cleanPath)

        return resolution
    }

    /**
     * Build the hierarchy of paths for backstack synthesis.
     * For a path like "auth/signup/verify", returns ["auth", "auth/signup", "auth/signup/verify"]
     */
    public fun buildPathHierarchy(fullPath: String): List<String> {
        val cleanPath = normalizePath(fullPath)
        if (cleanPath.isEmpty()) return emptyList()

        val segments = cleanPath.split("/")
        return segments.indices.map { i ->
            segments.take(i + 1).joinToString("/")
        }
    }
}


private class ParameterizedRouteEntry(
    val template: RouteTemplate,
    val navigatable: Navigatable,
    val graphId: String
)
