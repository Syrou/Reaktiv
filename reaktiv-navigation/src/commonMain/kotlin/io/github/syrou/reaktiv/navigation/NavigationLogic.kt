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
import io.github.syrou.reaktiv.navigation.util.SimpleRouteResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NavigationLogic(
    private val storeAccessor: StoreAccessor,
) : ModuleLogic<NavigationAction>() {

    /**
     * Main navigation method - handles all navigation cases with single clear path
     */
    /**
     * Main navigation method with proper layout context preservation
     */
    suspend fun navigate(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        val currentState = getCurrentNavigationState()

        println("DEBUG: 🚀 Navigate called with route: '$route'")

        // Apply configuration
        val builder = NavigationBuilder(route, params)
        config?.let { builder.apply(it) }
        val navigationConfig = builder.build()

        // Validate configuration
        if (navigationConfig.clearBackStack &&
            (navigationConfig.popUpTo != null || navigationConfig.replaceWith != null)) {
            throw IllegalStateException("Cannot combine clearBackStack with popUpTo or replaceWith")
        }

        // Resolve the route to target screen and graph
        val resolution = SimpleRouteResolver.resolve(
            route = route,
            graphDefinitions = currentState.graphDefinitions,
            availableScreens = currentState.availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        // Use the effective graph ID to preserve layout context
        val effectiveGraphId = resolution.getEffectiveGraphId()

        println("DEBUG: ✅ Route resolved to screen: ${resolution.targetScreen.route}")
        println("DEBUG: 📍 Target graph: ${resolution.targetGraphId}, Navigation graph: ${resolution.navigationGraphId}")
        println("DEBUG: 🎨 Effective graph ID for layouts: $effectiveGraphId")

        // Merge all parameters
        val allParams = (resolution.extractedParams + params).mapValues(::sanitizeParam)

        // Create new navigation entry with effective graph ID
        val newEntry = NavigationEntry(
            screen = resolution.targetScreen,
            params = allParams,
            graphId = effectiveGraphId  // This ensures layouts work correctly
        )

        // Calculate new back stack based on configuration
        val newBackStack = calculateNewBackStack(
            currentBackStack = currentState.backStack,
            newEntry = newEntry,
            config = navigationConfig,
            currentState = currentState
        )

        println("DEBUG: 📍 New back stack size: ${newBackStack.size}, clearBackStack: ${navigationConfig.clearBackStack}")

        // Update navigation state
        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = newBackStack
            )
        )
    }

    /**
     * Navigate back in the navigation stack
     */
    suspend fun navigateBack() {
        val currentState = getCurrentNavigationState()

        if (!currentState.canGoBack) {
            println("DEBUG: ⛔ Cannot navigate back - no history available")
            return // Cannot go back
        }

        val newBackStack = currentState.backStack.dropLast(1)
        val newCurrentEntry = newBackStack.last()

        println("DEBUG: 🔙 Navigate back: ${currentState.currentEntry.screen.route} -> ${newCurrentEntry.screen.route}")

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newCurrentEntry,
                backStack = newBackStack
            )
        )
    }

    /**
     * Calculate new back stack based on navigation configuration
     */
    private fun calculateNewBackStack(
        currentBackStack: List<NavigationEntry>,
        newEntry: NavigationEntry,
        config: NavigationConfig,
        currentState: NavigationState
    ): List<NavigationEntry> {
        return when {
            config.clearBackStack -> {
                println("DEBUG: 🧹 Clearing back stack - starting fresh")
                listOf(newEntry)
            }
            config.popUpTo != null -> {
                println("DEBUG: 📤 Pop up to: ${config.popUpTo}, inclusive: ${config.inclusive}")
                val popIndex = currentBackStack.indexOfLast { entry ->
                    entry.screen.route == config.popUpTo
                }
                if (popIndex != -1) {
                    val baseStack = if (config.inclusive) {
                        currentBackStack.take(popIndex)
                    } else {
                        currentBackStack.take(popIndex + 1)
                    }
                    baseStack + newEntry
                } else {
                    println("DEBUG: ⚠️ PopUpTo target '${config.popUpTo}' not found in back stack")
                    currentBackStack + newEntry
                }
            }
            config.replaceWith != null -> {
                println("DEBUG: 🔄 Replace current entry")
                currentBackStack.dropLast(1) + newEntry
            }
            else -> {
                println("DEBUG: ➕ Normal navigation - add to stack")
                val finalParams = if (config.forwardParams && currentBackStack.isNotEmpty()) {
                    currentBackStack.last().params + newEntry.params
                } else {
                    newEntry.params
                }
                currentBackStack + newEntry.copy(params = finalParams)
            }
        }
    }

    /**
     * Pop up to a specific route
     */
    suspend fun popUpTo(
        route: String,
        inclusive: Boolean = false,
        config: (PopUpToBuilder.() -> Unit)? = null
    ) {
        val builder = PopUpToBuilder(route, inclusive)
        config?.let { builder.apply(it) }
        val popUpToConfig = builder.build()

        val currentState = getCurrentNavigationState()
        val popIndex = currentState.backStack.indexOfLast { it.screen.route == route }

        if (popIndex == -1) {
            throw RouteNotFoundException("Route $route not found in back stack")
        }

        val newBackStack = if (inclusive) {
            currentState.backStack.take(popIndex)
        } else {
            currentState.backStack.take(popIndex + 1)
        }

        if (newBackStack.isEmpty()) {
            throw IllegalStateException("Cannot pop to route $route - would result in empty back stack")
        }

        val newCurrentEntry = if (popUpToConfig.replaceWith != null) {
            // Find replacement screen
            val replacementResolution = SimpleRouteResolver.resolve(
                route = popUpToConfig.replaceWith,
                graphDefinitions = currentState.graphDefinitions,
                availableScreens = currentState.availableScreens
            ) ?: throw RouteNotFoundException("Replacement route ${popUpToConfig.replaceWith} not found")

            NavigationEntry(
                screen = replacementResolution.targetScreen,
                params = popUpToConfig.replaceParams,
                graphId = replacementResolution.targetGraphId
            )
        } else {
            newBackStack.last()
        }

        val finalBackStack = if (popUpToConfig.replaceWith != null) {
            newBackStack.dropLast(1) + newCurrentEntry
        } else {
            newBackStack
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newCurrentEntry,
                backStack = finalBackStack
            )
        )
    }

    /**
     * Clear the entire back stack
     */
    suspend fun clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
        val builder = ClearBackStackBuilder()
        config?.let { builder.apply(it) }
        val clearConfig = builder.build()

        val currentState = getCurrentNavigationState()

        val newEntry = if (clearConfig.root != null) {
            val resolution = SimpleRouteResolver.resolve(
                route = clearConfig.root,
                graphDefinitions = currentState.graphDefinitions,
                availableScreens = currentState.availableScreens
            ) ?: throw RouteNotFoundException("Root route ${clearConfig.root} not found")

            NavigationEntry(
                screen = resolution.targetScreen,
                params = clearConfig.params,
                graphId = resolution.targetGraphId
            )
        } else {
            // Keep current entry but clear history
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = listOf(newEntry)
            )
        )
    }

    /**
     * Replace current screen
     */
    suspend fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        navigate(route, params) { replaceWith(route) }
    }

    /**
     * Navigate with validation
     */
    suspend fun navigateWithValidation(
        route: String,
        params: Map<String, Any> = emptyMap(),
        validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
    ) {
        val currentState = getCurrentNavigationState()
        val resolution = SimpleRouteResolver.resolve(
            route = route,
            graphDefinitions = currentState.graphDefinitions,
            availableScreens = currentState.availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        val allParams = (resolution.extractedParams + params).mapValues(::sanitizeParam)

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

    // Parameter management methods (simplified)
    suspend fun clearCurrentScreenParams() {
        val currentState = getCurrentNavigationState()
        val updatedEntry = currentState.currentEntry.copy(params = emptyMap())
        val updatedBackStack = currentState.backStack.dropLast(1) + updatedEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedEntry,
                backStack = updatedBackStack
            )
        )
    }

    suspend fun clearCurrentScreenParam(key: String) {
        val currentState = getCurrentNavigationState()
        val updatedParams = currentState.currentEntry.params - key
        val updatedEntry = currentState.currentEntry.copy(params = updatedParams)
        val updatedBackStack = currentState.backStack.dropLast(1) + updatedEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedEntry,
                backStack = updatedBackStack
            )
        )
    }

    suspend fun clearScreenParams(route: String) {
        val currentState = getCurrentNavigationState()
        if (!currentState.hasRoute(route)) {
            throw RouteNotFoundException("Route $route not found")
        }

        storeAccessor.dispatch(NavigationAction.ClearScreenParams(route))
    }

    suspend fun clearScreenParam(route: String, key: String) {
        val currentState = getCurrentNavigationState()
        if (!currentState.hasRoute(route)) {
            throw RouteNotFoundException("Route $route not found")
        }

        storeAccessor.dispatch(NavigationAction.ClearScreenParam(route, key))
    }

    // Utility methods
    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

    private fun sanitizeParam(value: Any): Any {
        return when (value) {
            is String -> value.replace(Regex("""[^\w\-._~%]"""), "")
            is Number -> value
            is Boolean -> value
            else -> value.toString()
        }
    }
}