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
    private val isNestedNavigation: Boolean = false
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
     * Execute navigation with automatic graph switching
     */
    private suspend fun executeIntelligentNavigation(
        resolution: RouteResolution,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        println("DEBUG: 🎯 Executing navigation to graph: ${resolution.targetGraph.graphId}")

        // If we need to switch graphs, do it first
        if (resolution.requiresGraphSwitch && isNestedNavigation) {
            println("DEBUG: 🔄 Graph switch required: ${currentState.activeGraphId} → ${resolution.targetGraph.graphId}")

            // Execute graph switch with navigation
            executeGraphSwitchNavigation(
                targetGraphId = resolution.targetGraph.graphId,
                targetScreen = resolution.targetScreen,
                params = params,
                config = config,
                currentState = currentState
            )
        } else {
            println("DEBUG: ➡️ Same graph navigation")

            // Execute normal navigation within current/target graph
            executeNavigation(
                targetGraphId = resolution.targetGraph.graphId,
                targetScreen = resolution.targetScreen,
                params = params,
                config = config,
                currentState = currentState
            )
        }
    }

    /**
     * Execute navigation that requires graph switching
     */
    private suspend fun executeGraphSwitchNavigation(
        targetGraphId: String,
        targetScreen: Screen,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        val targetGraphState = currentState.graphStates[targetGraphId]

        if (targetGraphState == null) {
            // Graph state doesn't exist, this shouldn't happen but handle gracefully
            println("DEBUG: ⚠️ Target graph state not found: $targetGraphId")
            return
        }

        // Create new entry
        val newEntry = NavigationEntry(targetScreen, params)

        // Handle back stack operations for the target graph
        var newBackStack = targetGraphState.backStack

        when {
            config.clearBackStack -> newBackStack = emptyList()
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

        newBackStack = newBackStack + newEntry

        // Update all graph states
        val updatedGraphStates = currentState.graphStates.mapValues { (graphId, graphState) ->
            when (graphId) {
                targetGraphId -> graphState.copy(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    isActive = true
                )

                else -> graphState.copy(isActive = false)
            }
        }

        // Update global back stack for cross-graph navigation
        val updatedGlobalBackStack = currentState.globalBackStack + newEntry

        println("DEBUG: 🔄 Dispatching graph switch to: $targetGraphId")

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = newBackStack,
                activeGraphId = targetGraphId,
                graphStates = updatedGraphStates,
                globalBackStack = updatedGlobalBackStack,
                clearedBackStackWithNavigate = config.clearBackStack
            )
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
                targetGraphId = if (isNestedNavigation) targetGraphId else null
            )
        )
    }

    suspend fun clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
        val builder = ClearBackStackBuilder()
        config?.let { builder.apply(it) }
        val clearConfig = builder.build()

        val currentState = getCurrentNavigationState()
        val targetGraphId = if (isNestedNavigation) currentState.activeGraphId else null

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

    // ========== Action Handlers ==========

    private suspend fun handleNavigate(action: NavigationAction.Navigate) {
        val currentState = getCurrentNavigationState()
        val targetGraphId = action.targetGraphId ?: currentState.activeGraphId
        val targetScreen = findScreenInGraph(action.route, targetGraphId)
            ?: throw RouteNotFoundException("$targetGraphId/${action.route}")

        executeNavigation(
            targetGraphId = targetGraphId,
            targetScreen = targetScreen,
            params = action.params,
            config = NavigationConfig(
                popUpTo = action.popUpTo,
                inclusive = action.inclusive,
                replaceWith = action.replaceWith,
                clearBackStack = action.clearBackStack,
                forwardParams = action.forwardParams,
            ),
            currentState = currentState
        )
    }

    suspend fun navigateBack() {
        val state = getCurrentNavigationState()

        if (isNestedNavigation) {
            handleNestedBack(state)
        } else {
            handleFlatBack(state)
        }
    }

    private suspend fun handleNestedBack(state: NavigationState) {
        val activeGraphState = state.activeGraphState
        // Try to go back within current graph first
        if (activeGraphState.backStack.size > 1) {
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
                    globalBackStack = updatedGlobalBackStack
                )
            )
            return
        }

        // Cross-graph back navigation
        if (state.globalBackStack.size > 1) {
            val newGlobalBackStack = state.globalBackStack.dropLast(1)
            val previousEntry = newGlobalBackStack.last()

            // Find which graph contains the previous screen
            val previousGraphId = state.graphDefinitions.values
                .find { graph -> graph.screens.any { it.route == previousEntry.screen.route } }
                ?.graphId ?: return

            val previousGraphState = state.graphStates[previousGraphId] ?: return

            val updatedGraphStates = state.graphStates.mapValues { (graphId, graphState) ->
                when (graphId) {
                    previousGraphId -> graphState.copy(
                        currentEntry = previousEntry,
                        isActive = true
                    )

                    else -> graphState.copy(isActive = false)
                }
            }

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = previousEntry,
                    backStack = previousGraphState.backStack,
                    activeGraphId = previousGraphId,
                    graphStates = updatedGraphStates,
                    globalBackStack = newGlobalBackStack
                )
            )
        }
    }

    private suspend fun handleFlatBack(state: NavigationState) {
        if (state.backStack.size > 1) {
            val newBackStack = state.backStack.dropLast(1)
            val newCurrentEntry = newBackStack.last()

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack
                )
            )
        }
    }

    private suspend fun handlePopUpTo(action: NavigationAction.PopUpTo) {
        val state = getCurrentNavigationState()
        val targetGraphId = action.targetGraphId ?: state.activeGraphId

        val workingBackStack = if (isNestedNavigation) {
            state.graphStates[targetGraphId]?.backStack ?: state.backStack
        } else {
            state.backStack
        }

        val targetIndex = workingBackStack.indexOfLast { it.screen.route == action.route }
        if (targetIndex == -1) return

        var newBackStack = if (action.inclusive) {
            workingBackStack.take(targetIndex)
        } else {
            workingBackStack.take(targetIndex + 1)
        }

        var currentEntry = newBackStack.lastOrNull() ?: state.currentEntry

        if (action.replaceWith != null) {
            val replaceScreen = findScreenInGraph(action.replaceWith, targetGraphId)
                ?: throw RouteNotFoundException(action.replaceWith)
            currentEntry = currentEntry.copy(screen = replaceScreen, params = action.replaceParams)
            newBackStack = newBackStack.dropLast(1) + currentEntry
        }

        if (isNestedNavigation) {
            val updatedGraphState = state.graphStates[targetGraphId]!!.copy(
                currentEntry = currentEntry,
                backStack = newBackStack
            )
            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = currentEntry,
                    backStack = newBackStack,
                    graphStates = state.graphStates + (targetGraphId to updatedGraphState)
                )
            )
        } else {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = currentEntry,
                    backStack = newBackStack
                )
            )
        }
    }

    private suspend fun handleClearBackStack(action: NavigationAction.ClearBackStack) {
        val state = getCurrentNavigationState()

        if (isNestedNavigation && action.targetGraphId != null) {
            val targetGraph = graphDefinitions[action.targetGraphId] ?: return
            val rootScreen = if (action.root != null) {
                targetGraph.screens.find { it.route == action.root } ?: targetGraph.startScreen
            } else {
                targetGraph.startScreen
            }

            val newEntry = NavigationEntry(rootScreen, action.params)
            val updatedGraphState = state.graphStates[action.targetGraphId]!!.copy(
                currentEntry = newEntry,
                backStack = listOf(newEntry)
            )

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = if (action.targetGraphId == state.activeGraphId) newEntry else state.currentEntry,
                    backStack = if (action.targetGraphId == state.activeGraphId) listOf(newEntry) else state.backStack,
                    graphStates = state.graphStates + (action.targetGraphId to updatedGraphState)
                )
            )
        } else {
            if (action.root != null) {
                val currentScreen = findScreenInGraph(action.root, state.activeGraphId)
                    ?: throw RouteNotFoundException(action.root)
                val newEntry = NavigationEntry(currentScreen, action.params)
                storeAccessor.dispatch(
                    NavigationAction.BatchUpdate(
                        currentEntry = newEntry,
                        backStack = listOf(newEntry)
                    )
                )
            } else {
                storeAccessor.dispatch(NavigationAction.UpdateBackStack(emptyList()))
            }
        }
    }

    private suspend fun handleReplace(action: NavigationAction.Replace) {
        val state = getCurrentNavigationState()
        val newScreen = findScreenInGraph(action.route, state.activeGraphId)
            ?: throw RouteNotFoundException(action.route)
        val newEntry = NavigationEntry(screen = newScreen, params = action.params)

        if (isNestedNavigation) {
            val activeGraphState = state.activeGraphState
            val newBackStack = if (activeGraphState.backStack.isNotEmpty()) {
                activeGraphState.backStack.dropLast(1) + newEntry
            } else {
                listOf(newEntry)
            }

            val updatedGraphState = activeGraphState.copy(
                currentEntry = newEntry,
                backStack = newBackStack
            )

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    graphStates = state.graphStates + (state.activeGraphId to updatedGraphState)
                )
            )
        } else {
            val newBackStack = if (state.backStack.isNotEmpty()) {
                state.backStack.dropLast(1) + newEntry
            } else {
                listOf(newEntry)
            }

            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = newEntry,
                    backStack = newBackStack
                )
            )
        }
    }

    // ========== Private Helper Methods ==========

    private suspend fun executeNavigation(
        targetGraphId: String,
        targetScreen: Screen,
        params: Map<String, Any>,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        if (isNestedNavigation) {
            executeNestedNavigation(targetGraphId, targetScreen, params, config, currentState)
        } else {
            executeFlatNavigation(targetScreen, params, config, currentState)
        }
    }

    private suspend fun executeNestedNavigation(
        targetGraphId: String,
        targetScreen: Screen,
        params: Map<String, Any>,
        config: NavigationConfig,
        state: NavigationState
    ) {
        val targetGraphState = state.graphStates[targetGraphId] ?: return

        var newBackStack = targetGraphState.backStack

        // Handle back stack operations
        when {
            config.clearBackStack -> newBackStack = emptyList()
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
        val updatedGraphStates = state.graphStates.mapValues { (graphId, graphState) ->
            when (graphId) {
                targetGraphId -> graphState.copy(
                    currentEntry = newEntry,
                    backStack = updatedBackStack,
                    isActive = true
                )

                else -> if (targetGraphId != state.activeGraphId) {
                    graphState.copy(isActive = false)
                } else {
                    graphState
                }
            }
        }


        // Update global back stack for cross-graph navigation
        val updatedGlobalBackStack = if (targetGraphId != state.activeGraphId) {
            state.globalBackStack + newEntry
        } else {
            // Replace last entry in global stack if staying in same graph
            if (state.globalBackStack.isNotEmpty()) {
                state.globalBackStack.dropLast(1) + newEntry
            } else {
                listOf(newEntry)
            }
        }
        
        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = updatedBackStack,
                activeGraphId = targetGraphId,
                graphStates = updatedGraphStates,
                globalBackStack = updatedGlobalBackStack,
                clearedBackStackWithNavigate = config.clearBackStack
            )
        )
    }

    private suspend fun executeFlatNavigation(
        targetScreen: Screen,
        params: Map<String, Any>,
        config: NavigationConfig,
        state: NavigationState
    ) {
        var newBackStack = if (config.clearBackStack) emptyList() else state.backStack

        if (config.popUpTo != null) {
            val popIndex = newBackStack.indexOfLast { it.screen.route == config.popUpTo }
            if (popIndex != -1) {
                newBackStack = if (config.inclusive) {
                    newBackStack.take(popIndex)
                } else {
                    newBackStack.take(popIndex + 1)
                }
            }
        }

        val actualTargetScreen = if (config.replaceWith != null) {
            state.availableScreens[config.replaceWith] ?: throw RouteNotFoundException(config.replaceWith)
        } else {
            targetScreen
        }

        val finalParams: StringAnyMap = if (config.forwardParams && newBackStack.isNotEmpty()) {
            newBackStack.last().params + params
        } else {
            params
        }

        val newEntry = NavigationEntry(screen = actualTargetScreen, params = finalParams)
        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = newBackStack + newEntry,
                clearedBackStackWithNavigate = config.clearBackStack
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

        val screenMap = if (isNestedNavigation) {
            graphDefinitions[graphId]?.getAllScreens() ?: emptyMap()
        } else {
            availableScreens
        }

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
        return if (isNestedNavigation) {
            graphDefinitions[graphId]?.getAllScreens()?.get(route)
        } else {
            availableScreens[route]
        }
    }
}