package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.definition.GraphEnterBehavior
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.ClearBackStackBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.PopUpToBuilder
import io.github.syrou.reaktiv.navigation.exception.ClearingBackStackWithOtherOperations
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.NavigationConfig
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.ParsedRoute
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.util.IntelligentRouteResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NavigationLogic(
    private val coroutineScope: CoroutineScope,
    private val availableScreens: Map<String, Screen>,
    private val storeAccessor: StoreAccessor,
    private val graphDefinitions: Map<String, NavigationGraph> = emptyMap(),
) : ModuleLogic<NavigationAction>() {

    /**
     * Main navigation method with enhanced error handling and validation
     */
    suspend fun navigate(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        val currentState = getCurrentNavigationState()

        println("DEBUG: 🚀 Enhanced navigate called with route: '$route'")

        // Use intelligent route resolution
        val resolution = IntelligentRouteResolver.resolve(
            route = route,
            currentGraphId = currentState.activeGraphId,
            graphDefinitions = graphDefinitions,
            availableScreens = availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        println("DEBUG: ✅ Route resolved to graph: ${resolution.targetGraph.graphId}, screen: ${resolution.screenRoute}")

        // Merge all parameters
        val allParams = (resolution.extractedParams + params).mapValues(::sanitizeParam)

        // Apply configuration
        val builder = NavigationBuilder(resolution.screenRoute, allParams)
        config?.let { builder.apply(it) }
        val navigationConfig = builder.build()

        // Validate navigation configuration
        if (navigationConfig.clearBackStack &&
            (navigationConfig.popUpTo != null || navigationConfig.replaceWith != null)
        ) {
            throw ClearingBackStackWithOtherOperations
        }

        // Execute intelligent navigation
        executeIntelligentNavigation(
            resolution = resolution,
            params = allParams,
            config = navigationConfig,
            currentState = currentState
        )
    }

    /**
     * Execute navigation with automatic graph switching and proper enter behavior
     */
    private suspend fun executeIntelligentNavigation(
        resolution: RouteResolution,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        println("DEBUG: 🎯 Executing navigation to graph: ${resolution.targetGraph.graphId}")

        // Check if this is graph-only navigation (no specific screen route)
        val isGraphOnlyNavigation = resolution.screenRoute.isEmpty() ||
                resolution.screenRoute == resolution.targetGraph.graphId

        if (isGraphOnlyNavigation) {
            println("DEBUG: 📍 Graph-only navigation detected for: ${resolution.targetGraph.graphId}")
            executeGraphOnlyNavigation(
                targetGraph = resolution.targetGraph,
                params = params,
                config = config,
                currentState = currentState
            )
        } else if (resolution.requiresGraphSwitch) {
            println("DEBUG: 🔄 Graph switch required: ${currentState.activeGraphId} → ${resolution.targetGraph.graphId}")
            executeGraphSwitchNavigation(
                targetGraph = resolution.targetGraph,
                targetRoute = resolution.screenRoute,
                params = params,
                config = config,
                currentState = currentState
            )
        } else {
            println("DEBUG: ➡️ Same graph navigation")
            executeGraphNavigation(
                targetGraph = resolution.targetGraph,
                targetScreen = resolution.targetScreen,
                params = params,
                config = config,
                currentState = currentState
            )
        }
    }

    /**
     * Execute graph-only navigation - intelligently determine target screen based on
     * retainState, enterBehavior, and nested graph hierarchy
     */
    private suspend fun executeGraphOnlyNavigation(
        targetGraph: NavigationGraph,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        val targetGraphState = currentState.graphStates[targetGraph.graphId]

        if (targetGraphState == null) {
            println("DEBUG: ⚠️ Target graph state not found: ${targetGraph.graphId}")
            return
        }

        // Determine the correct screen to navigate to based on graph hierarchy and behavior
        val targetScreen = determineEntryScreen(targetGraph, targetGraphState, null)

        println("DEBUG: 🎯 Graph-only navigation resolved to screen: ${targetScreen.route}")
        println("DEBUG: Target graph retainState: ${targetGraph.retainState}")
        println("DEBUG: Target graph enterBehavior: ${targetGraph.graphEnterBehavior}")

        // Find which graph actually contains the target screen (might be a nested graph)
        val actualTargetGraph = findGraphContainingScreen(targetScreen.route, currentState)
            ?: targetGraph

        println("DEBUG: 📍 Actual target graph: ${actualTargetGraph.graphId}")

        // If we're switching graphs, use graph switch navigation
        if (actualTargetGraph.graphId != currentState.activeGraphId) {
            executeGraphSwitchNavigation(
                targetGraph = actualTargetGraph,
                targetRoute = targetScreen.route,
                params = params,
                config = config,
                currentState = currentState
            )
        } else {
            // Same graph navigation
            executeGraphNavigation(
                targetGraph = actualTargetGraph,
                targetScreen = targetScreen,
                params = params,
                config = config,
                currentState = currentState
            )
        }
    }

    /**
     * Find which graph contains a specific screen route
     */
    private suspend fun findGraphContainingScreen(
        screenRoute: String,
        currentState: NavigationState
    ): NavigationGraph? {
        return currentState.graphDefinitions.values.find { graph ->
            graph.getAllScreens().containsKey(screenRoute)
        }
    }

    /**
     * Execute navigation that requires graph switching with proper GraphEnterBehavior implementation
     */
    private suspend fun executeGraphSwitchNavigation(
        targetGraph: NavigationGraph,
        targetRoute: String?,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        val targetGraphState = currentState.graphStates[targetGraph.graphId]

        if (targetGraphState == null) {
            println("DEBUG: ⚠️ Target graph state not found: ${targetGraph.graphId}")
            return
        }

        // Determine entry screen based on GraphEnterBehavior
        val entryScreen = determineEntryScreen(targetGraph, targetGraphState, targetRoute)

        // Create new entry
        val newEntry = NavigationEntry(entryScreen, params)

        // Handle back stack operations for the target graph
        var newBackStack = if (config.clearBackStack) {
            // When clearBackStack is used, start completely fresh
            listOf(newEntry)
        } else if (targetGraph.retainState) {
            // Retain the existing backstack if retainState is true and not clearing
            targetGraphState.backStack
        } else {
            // Start fresh
            emptyList()
        }

        when {
            config.clearBackStack -> {
                // Already handled above - start with just the new entry
                newBackStack = listOf(newEntry)
                // Clear retained state when explicitly clearing backstack
                updateGraphRetainedState(targetGraph.graphId, emptyMap(), currentState)
            }
            config.popUpTo != null -> {
                val popIndex = newBackStack.indexOfLast { it.screen.route == config.popUpTo }
                if (popIndex != -1) {
                    newBackStack = if (config.inclusive) {
                        newBackStack.take(popIndex)
                    } else {
                        newBackStack.take(popIndex + 1)
                    }
                }
                newBackStack = newBackStack + newEntry
            }
            else -> {
                // Normal navigation - add to backstack unless we're resuming and already have the entry
                if (!targetGraph.retainState || newBackStack.isEmpty() || newBackStack.last().screen != entryScreen) {
                    newBackStack = newBackStack + newEntry
                }
            }
        }

        // Update all graph states
        val updatedGraphStates = currentState.graphStates.mapValues { (graphId, graphState) ->
            when (graphId) {
                targetGraph.graphId -> graphState.copy(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    isActive = true,
                    retainedState = if (targetGraph.retainState && !config.clearBackStack) {
                        graphState.retainedState + ("visited" to true)
                    } else {
                        emptyMap()
                    }
                )
                else -> {
                    // When clearBackStack is used, completely clear all other graphs
                    if (config.clearBackStack) {
                        val otherGraph = currentState.graphDefinitions[graphId]
                        if (otherGraph != null) {
                            val otherStartEntry = NavigationEntry(otherGraph.startScreen, emptyMap())
                            graphState.copy(
                                currentEntry = otherStartEntry,
                                backStack = emptyList(), // COMPLETELY EMPTY - no navigation history
                                isActive = false,
                                retainedState = emptyMap()
                            )
                        } else {
                            graphState.copy(
                                backStack = emptyList(),
                                isActive = false,
                                retainedState = emptyMap()
                            )
                        }
                    } else {
                        // Normal navigation - just deactivate
                        graphState.copy(
                            isActive = false,
                            retainedState = if (currentState.graphDefinitions[graphId]?.retainState == true) {
                                graphState.retainedState + ("visited" to true)
                            } else {
                                emptyMap()
                            }
                        )
                    }
                }
            }
        }

        // Update global back stack for cross-graph navigation
        val updatedGlobalBackStack = if (config.clearBackStack) {
            // When clearing backstack, start completely fresh with ONLY the new entry
            listOf(newEntry)
        } else {
            // Normal cross-graph navigation
            currentState.globalBackStack + newEntry
        }

        println("DEBUG: 🔄 Dispatching graph switch to: ${targetGraph.graphId}")
        println("DEBUG: clearBackStack: ${config.clearBackStack}")
        println("DEBUG: updatedGlobalBackStack size: ${updatedGlobalBackStack.size}")
        if (config.clearBackStack) {
            println("DEBUG: 🧹 CLEARED ALL NAVIGATION HISTORY")
            println("DEBUG: Root graph backstack size: ${updatedGraphStates["root"]?.backStack?.size ?: 0}")
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = newBackStack,
                activeGraphId = targetGraph.graphId,
                graphStates = updatedGraphStates,
                globalBackStack = updatedGlobalBackStack,
                clearedBackStackWithNavigate = if (config.clearBackStack) true else false // Only set to true when explicitly clearing
            )
        )
    }

    /**
     * Determine which screen to enter based on GraphEnterBehavior and nested graph hierarchy
     */
    private suspend fun determineEntryScreen(
        targetGraph: NavigationGraph,
        targetGraphState: GraphState?,
        targetRoute: String?
    ): Screen {
        return when {
            // If specific route is provided, use it
            targetRoute != null -> {
                targetGraph.screens.find { it.route == targetRoute } ?: targetGraph.startScreen
            }
            // Apply GraphEnterBehavior with nested graph support
            targetGraph.graphEnterBehavior is GraphEnterBehavior.StartAtRoot -> {
                targetGraph.startScreen
            }
            targetGraph.graphEnterBehavior is GraphEnterBehavior.ResumeOrStart -> {
                if (targetGraph.retainState && targetGraphState != null && targetGraphState.backStack.isNotEmpty()) {
                    // Check if we should resume a nested graph instead
                    findDeepestActiveScreen(targetGraph, getCurrentNavigationState()) ?: targetGraphState.currentEntry.screen
                } else {
                    targetGraph.startScreen
                }
            }
            targetGraph.graphEnterBehavior is GraphEnterBehavior.Custom -> {
                (targetGraph.graphEnterBehavior as GraphEnterBehavior.Custom).determineScreen(targetGraphState)
            }
            else -> targetGraph.startScreen
        }
    }

    /**
     * Find the deepest active screen in a graph hierarchy
     * This handles nested graphs and finds where the user was last active
     */
    private suspend fun findDeepestActiveScreen(
        graph: NavigationGraph,
        currentState: NavigationState
    ): Screen? {
        val graphState = currentState.graphStates[graph.graphId] ?: return null

        // If this graph doesn't retain state, return its start screen
        if (!graph.retainState) {
            return graph.startScreen
        }

        // Check if any child graphs were active and should be resumed
        for (nestedGraph in graph.nestedGraphs) {
            val nestedGraphState = currentState.graphStates[nestedGraph.graphId]

            // If the nested graph retains state and has ResumeOrStart behavior
            if (nestedGraph.retainState &&
                nestedGraph.graphEnterBehavior is GraphEnterBehavior.ResumeOrStart &&
                nestedGraphState?.retainedState?.isNotEmpty() == true) {

                // Recursively check for deeper nesting
                val deeperScreen = findDeepestActiveScreen(nestedGraph, currentState)
                if (deeperScreen != null) {
                    return deeperScreen
                }

                // If no deeper screen, return this nested graph's current screen
                if (nestedGraphState.backStack.isNotEmpty()) {
                    return nestedGraphState.currentEntry.screen
                }
            }
        }

        // No active nested graphs, return this graph's current screen
        return if (graphState.backStack.isNotEmpty()) {
            graphState.currentEntry.screen
        } else {
            graph.startScreen
        }
    }

    /**
     * Update retained state for a graph
     */
    private suspend fun updateGraphRetainedState(
        graphId: String,
        retainedState: StringAnyMap,
        currentState: NavigationState
    ) {
        val graphState = currentState.graphStates[graphId] ?: return
        val updatedGraphState = graphState.copy(retainedState = retainedState)

        storeAccessor.dispatch(
            NavigationAction.UpdateGraphState(graphId, updatedGraphState)
        )
    }

    suspend fun popUpTo(
        route: String,
        inclusive: Boolean = false,
        config: (PopUpToBuilder.() -> Unit)? = null
    ) {
        val currentState = getCurrentNavigationState()
        val parsedRoute = ParsedRoute.parse(route, currentState.activeGraphId, graphDefinitions)
        val targetGraphId = parsedRoute.graphId ?: currentState.activeGraphId

        if (findScreenInGraph(parsedRoute.route, targetGraphId) == null) {
            throw RouteNotFoundException(parsedRoute.route)
        }

        val builder = PopUpToBuilder(parsedRoute.route, inclusive)
        config?.let { builder.apply(it) }
        val popUpToConfig = builder.build()

        storeAccessor.dispatch(
            NavigationAction.PopUpTo(
                route = parsedRoute.route,
                inclusive = inclusive,
                replaceWith = popUpToConfig.replaceWith,
                replaceParams = popUpToConfig.replaceParams,
                targetGraphId = targetGraphId
            )
        )
    }

    suspend fun clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
        val builder = ClearBackStackBuilder()
        config?.let { builder.apply(it) }
        val clearConfig = builder.build()

        val currentState = getCurrentNavigationState()
        val targetGraphId = currentState.activeGraphId

        storeAccessor.dispatch(
            NavigationAction.ClearBackStack(
                root = clearConfig.root,
                params = clearConfig.params,
                targetGraphId = targetGraphId
            )
        )
    }

    suspend fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        val currentState = getCurrentNavigationState()
        val parsedRoute = ParsedRoute.parse(route, currentState.activeGraphId, graphDefinitions)

        if (findScreenInGraph(parsedRoute.route, parsedRoute.graphId ?: currentState.activeGraphId) == null) {
            throw RouteNotFoundException(parsedRoute.route)
        }

        storeAccessor.dispatch(
            NavigationAction.Replace(
                route = parsedRoute.route,
                params = (parsedRoute.params + params)
            )
        )
    }

    suspend fun navigateWithValidation(
        route: String,
        params: Map<String, Any> = emptyMap(),
        storeAccessor: StoreAccessor,
        validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
    ) {
        val currentState = getCurrentNavigationState()
        val parsedRoute = ParsedRoute.parse(route, currentState.activeGraphId, graphDefinitions)
        val (finalRoute, extractedParams) = extractRouteAndParams(
            parsedRoute.route,
            parsedRoute.graphId ?: currentState.activeGraphId
        )
        val targetGraphId = parsedRoute.graphId ?: currentState.activeGraphId

        if (findScreenInGraph(finalRoute, targetGraphId) == null) {
            throw RouteNotFoundException(finalRoute)
        }

        val allParams = (extractedParams + parsedRoute.params + params).mapValues(::sanitizeParam)

        coroutineScope.launch {
            storeAccessor.dispatch(NavigationAction.SetLoading(true))
            try {
                if (validate(storeAccessor, allParams)) {
                    navigate(route, params)
                }
            } catch (e: Exception) {
                throw e
            } finally {
                storeAccessor.dispatch(NavigationAction.SetLoading(false))
            }
        }
    }

    // Parameter management methods
    fun clearCurrentScreenParams() {
        storeAccessor.dispatch(NavigationAction.ClearCurrentScreenParams)
    }

    fun clearCurrentScreenParam(key: String) {
        storeAccessor.dispatch(NavigationAction.ClearCurrentScreenParam(key))
    }

    suspend fun clearScreenParams(route: String) {
        val currentState = getCurrentNavigationState()
        if (findScreenInGraph(route, currentState.activeGraphId) != null) {
            storeAccessor.dispatch(NavigationAction.ClearScreenParams(route))
        } else {
            throw RouteNotFoundException(route)
        }
    }

    suspend fun clearScreenParam(route: String, key: String) {
        val currentState = getCurrentNavigationState()
        if (findScreenInGraph(route, currentState.activeGraphId) != null) {
            storeAccessor.dispatch(NavigationAction.ClearScreenParam(route, key))
        } else {
            throw RouteNotFoundException(route)
        }
    }

    suspend fun navigateBack() {
        val state = getCurrentNavigationState()
        handleNestedBack(state)
    }

    private suspend fun handleNestedBack(state: NavigationState) {
        val activeGraphState = state.activeGraphState

        // Check if back navigation is possible
        val canGoBackInCurrentGraph = activeGraphState.backStack.size > 1
        val canGoBackGlobally = state.globalBackStack.size > 1

        println("DEBUG: 🔙 Back navigation - canGoBackInCurrentGraph: $canGoBackInCurrentGraph, canGoBackGlobally: $canGoBackGlobally")
        println("DEBUG: Current graph backStack size: ${activeGraphState.backStack.size}")
        println("DEBUG: Global backStack size: ${state.globalBackStack.size}")
        println("DEBUG: clearedBackStackWithNavigate: ${state.clearedBackStackWithNavigate}")

        // If we can't go back anywhere, do nothing
        if (!canGoBackInCurrentGraph && !canGoBackGlobally) {
            println("DEBUG: ⛔ Cannot navigate back - no backstack available")
            return
        }

        // Try to go back within current graph first
        if (canGoBackInCurrentGraph) {
            val newBackStack = activeGraphState.backStack.dropLast(1)
            val newCurrentEntry = newBackStack.last()

            val updatedGraphState = activeGraphState.copy(
                currentEntry = newCurrentEntry,
                backStack = newBackStack
            )

            val updatedGlobalBackStack = if (state.globalBackStack.isNotEmpty()) {
                state.globalBackStack.dropLast(1)
            } else {
                emptyList()
            }

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
                    graphStates = state.graphStates + (state.activeGraphId to updatedGraphState),
                    globalBackStack = updatedGlobalBackStack,
                    clearedBackStackWithNavigate = false // Reset the flag after successful navigation
                )
            )
            return
        }

        // Cross-graph back navigation - only if we can go back globally
        if (canGoBackGlobally) {
            val newGlobalBackStack = state.globalBackStack.dropLast(1)
            val previousEntry = newGlobalBackStack.last()

            // Find which graph contains the previous screen
            val previousGraphId = state.graphDefinitions.values
                .find { graph -> graph.screens.any { it.route == previousEntry.screen.route } }
                ?.graphId

            if (previousGraphId == null) {
                println("DEBUG: ⚠️ Could not find graph for previous entry: ${previousEntry.screen.route}")
                return
            }

            val previousGraph = state.graphDefinitions[previousGraphId] ?: return
            val previousGraphState = state.graphStates[previousGraphId] ?: return

            // Check if the previous graph has an empty backstack (was cleared)
            if (previousGraphState.backStack.isEmpty()) {
                println("DEBUG: ⚠️ Previous graph '$previousGraphId' has empty backstack - cannot navigate back")
                return
            }

            // Determine entry based on GraphEnterBehavior when going back
            val entryScreen = determineEntryScreen(previousGraph, previousGraphState, null)
            val actualEntry = if (entryScreen == previousEntry.screen) {
                previousEntry
            } else {
                NavigationEntry(entryScreen, previousEntry.params)
            }

            val updatedGraphStates = state.graphStates.mapValues { (graphId, graphState) ->
                when (graphId) {
                    previousGraphId -> graphState.copy(
                        currentEntry = actualEntry,
                        isActive = true
                    )
                    else -> graphState.copy(isActive = false)
                }
            }

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = actualEntry,
                    backStack = previousGraphState.backStack,
                    activeGraphId = previousGraphId,
                    graphStates = updatedGraphStates,
                    globalBackStack = newGlobalBackStack,
                    clearedBackStackWithNavigate = false // Reset the flag after successful navigation
                )
            )
        }
    }

    private suspend fun executeGraphNavigation(
        targetGraph: NavigationGraph,
        targetScreen: Screen,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        val targetGraphState = currentState.graphStates[targetGraph.graphId] ?: return

        var newBackStack = targetGraphState.backStack

        // Handle back stack operations
        when {
            config.clearBackStack -> {
                // When clearBackStack is used, start completely fresh
                newBackStack = emptyList()
                // Clear retained state when explicitly clearing
                updateGraphRetainedState(targetGraph.graphId, emptyMap(), currentState)

                // Also clear all other graphs when clearBackStack is used
                val clearedGraphStates = currentState.graphStates.mapValues { (graphId, graphState) ->
                    if (graphId == targetGraph.graphId) {
                        graphState // Will be updated below
                    } else {
                        val otherGraph = currentState.graphDefinitions[graphId]
                        if (otherGraph != null) {
                            val otherStartEntry = NavigationEntry(otherGraph.startScreen, emptyMap())
                            graphState.copy(
                                currentEntry = otherStartEntry,
                                backStack = emptyList(), // COMPLETELY EMPTY
                                isActive = false,
                                retainedState = emptyMap()
                            )
                        } else {
                            graphState.copy(
                                backStack = emptyList(),
                                isActive = false,
                                retainedState = emptyMap()
                            )
                        }
                    }
                }

                // Update the graph states immediately for clearBackStack
                storeAccessor.dispatch(
                    NavigationAction.BatchUpdate(
                        graphStates = clearedGraphStates
                    )
                )
            }
            config.popUpTo != null -> {
                val popIndex = newBackStack.indexOfLast { it.screen.route == config.popUpTo }
                if (popIndex != -1) {
                    newBackStack = if (config.inclusive) {
                        newBackStack.take(popIndex)
                    } else {
                        newBackStack.take(popIndex + 1)
                    }
                }
            }
        }

        // Create new entry with proper parameter handling
        val finalParams = if (config.forwardParams && newBackStack.isNotEmpty()) {
            newBackStack.last().params + params
        } else {
            params
        }

        val newEntry = NavigationEntry(targetScreen, finalParams)
        val updatedBackStack = newBackStack + newEntry

        // Update graph states atomically
        val updatedGraphStates = currentState.graphStates.mapValues { (graphId, graphState) ->
            when (graphId) {
                targetGraph.graphId -> graphState.copy(
                    currentEntry = newEntry,
                    backStack = updatedBackStack,
                    isActive = true,
                    retainedState = if (targetGraph.retainState && !config.clearBackStack) {
                        graphState.retainedState + ("navigated" to true)
                    } else {
                        emptyMap()
                    }
                )
                else -> graphState
            }
        }

        // Update global back stack
        val updatedGlobalBackStack = if (config.clearBackStack) {
            // When clearing backstack, start completely fresh
            listOf(newEntry)
        } else {
            // Normal navigation within graph
            if (currentState.globalBackStack.isNotEmpty()) {
                currentState.globalBackStack.dropLast(1) + newEntry
            } else {
                listOf(newEntry)
            }
        }

        println("DEBUG: 📍 Graph navigation - clearBackStack: ${config.clearBackStack}")
        if (config.clearBackStack) {
            println("DEBUG: 🧹 CLEARED ALL NAVIGATION HISTORY (within graph)")
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = updatedBackStack,
                activeGraphId = targetGraph.graphId,
                graphStates = updatedGraphStates,
                globalBackStack = updatedGlobalBackStack,
                clearedBackStackWithNavigate = if (config.clearBackStack) true else false // Only set to true when explicitly clearing
            )
        )
    }

    private suspend fun extractRouteAndParams(fullRoute: String, graphId: String): Pair<String, Map<String, Any>> {
        val (routePart, queryPart) = fullRoute.split("?", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }

        val (matchingRoute, pathParams) = extractPathParameters(routePart, graphId)
        val queryParams = extractQueryParameters(queryPart)
        val sanitizedParams = (pathParams + queryParams).mapValues(::sanitizeParam)

        return Pair(matchingRoute, sanitizedParams)
    }

    private suspend fun extractPathParameters(path: String, graphId: String): Pair<String, Map<String, Any>> {
        val parts = path.split("/")
        val params = mutableMapOf<String, Any>()

        val screenMap = graphDefinitions[graphId]?.getAllScreens() ?: emptyMap()

        val matchingRoute = screenMap.keys.find { routeKey ->
            val routeParts = routeKey.split("/")
            if (routeParts.size != parts.size) return@find false

            routeParts.zip(parts).all { (routePart, actualPart) ->
                when {
                    routePart == actualPart -> true
                    routePart.startsWith("{") && routePart.endsWith("}") -> true
                    else -> false
                }
            }
        } ?: return Pair(path, emptyMap())

        val routeParts = matchingRoute.split("/")
        routeParts.zip(parts).forEach { (routePart, actualPart) ->
            if (routePart.startsWith("{") && routePart.endsWith("}")) {
                val paramName = routePart.substring(1, routePart.length - 1)
                params[paramName] = actualPart
            }
        }

        return Pair(matchingRoute, params)
    }

    private fun extractQueryParameters(query: String): Map<String, Any> {
        return query.split("&")
            .filter { it.isNotEmpty() }
            .associate { param ->
                val (key, value) = param.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                }
                key to value
            }
    }

    private fun sanitizeParam(value: Any): Any {
        return when (value) {
            is String -> value.replace(Regex("""[^\w\-._~%]"""), "")
            is Number -> value
            is Boolean -> value
            else -> value.toString()
        }
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

    private fun findScreenInGraph(route: String, graphId: String): Screen? {
        return graphDefinitions[graphId]?.getAllScreens()?.get(route)
    }
}