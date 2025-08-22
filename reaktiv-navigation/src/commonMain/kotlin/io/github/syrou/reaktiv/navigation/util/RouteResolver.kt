package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
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
    private val parameterizedRoutes: List<ParameterizedRouteEntry>
) {

    companion object {
        
        fun create(graphDefinitions: Map<String, NavigationGraph>): RouteResolver {
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>()
            val graphToStartNavigatable = mutableMapOf<String, ScreenResolution>()
            val graphHierarchy = mutableMapOf<String, List<String>>()
            val parentGraphLookup = mutableMapOf<String, String>()
            val fullPathToResolution = mutableMapOf<String, RouteResolution>()
            val parameterizedRoutes = mutableListOf<ParameterizedRouteEntry>()
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
                    routeToNavigatable[navigatable.route] = navigatable
                    routeToNavigatable[fullPath] = navigatable
                    navigatableToFullPath[navigatable] = fullPath
                    if (navigatable.route.contains("{")) {
                        val fullPathTemplate = if (graphPath.isEmpty()) {
                            navigatable.route
                        } else {
                            "$graphPath/${navigatable.route}"
                        }

                        parameterizedRoutes.add(
                            ParameterizedRouteEntry(
                                template = navigatable.route,
                                fullTemplate = fullPathTemplate,
                                navigatable = navigatable,
                                graphId = graphId,
                                paramNames = extractParameterNames(navigatable.route),
                                regex = createRouteRegex(fullPathTemplate) // Use full path for regex!
                            )
                        )

                        ReaktivDebug.nav("📝 Added parameterized route: $fullPathTemplate -> ${navigatable.route}")
                    } else {
                        fullPathToResolution[fullPath] = RouteResolution(
                            targetNavigatable = navigatable,
                            targetGraphId = graphId,
                            extractedParams = Params.empty(),
                            isGraphReference = false
                        )
                        if (!fullPathToResolution.containsKey(navigatable.route)) {
                            fullPathToResolution[navigatable.route] = RouteResolution(
                                targetNavigatable = navigatable,
                                targetGraphId = graphId,
                                extractedParams = Params.empty(),
                                isGraphReference = false
                            )
                        }
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

            ReaktivDebug.nav("🎯 Created ${parameterizedRoutes.size} parameterized route patterns")
            parameterizedRoutes.forEach { route ->
                ReaktivDebug.nav("   - ${route.fullTemplate} (params: ${route.paramNames})")
            }

            return RouteResolver(
                routeToNavigatable = routeToNavigatable,
                navigatableToFullPath = navigatableToFullPath,
                graphToStartNavigatable = graphToStartNavigatable,
                graphHierarchy = graphHierarchy,
                parentGraphLookup = parentGraphLookup,
                fullPathToResolution = fullPathToResolution,
                graphDefinitions = graphDefinitions,
                parameterizedRoutes = parameterizedRoutes
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
        availableNavigatables[cleanRoute]?.let { navigatable ->
            ReaktivDebug.nav("✅ Root navigatable found in provided map: $cleanRoute")
            return RouteResolution(
                targetNavigatable = navigatable,
                targetGraphId = "root",
                extractedParams = Params.empty(),
                isGraphReference = false
            )
        }
        val parameterizedResult = resolveParameterizedRoute(cleanRoute)
        if (parameterizedResult != null) {
            ReaktivDebug.nav("✅ Parameterized route found: $cleanRoute -> ${parameterizedResult.targetNavigatable.route}")
            return parameterizedResult
        }

        ReaktivDebug.nav("❌ Route not found: $cleanRoute")
        return null
    }

    
    private fun findGraphForNavigatable(navigatable: Navigatable): String? {
        for ((graphId, graph) in graphDefinitions) {
            if (graph.navigatables.contains(navigatable)) {
                return graphId
            }
        }
        return null
    }

    private fun resolveParameterizedRoute(route: String): RouteResolution? {
        ReaktivDebug.nav("🔍 Trying parameterized route matching for: $route")

        for (paramRoute in parameterizedRoutes) {
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

        ReaktivDebug.nav("❌ No parameterized route match found")
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
}


private data class ParameterizedRouteEntry(
    val template: String,
    val fullTemplate: String,
    val navigatable: Navigatable,
    val graphId: String,
    val paramNames: List<String>,
    val regex: Regex
)