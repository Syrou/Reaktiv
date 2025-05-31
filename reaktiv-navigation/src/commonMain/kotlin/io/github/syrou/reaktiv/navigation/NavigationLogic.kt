package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.dsl.ClearBackStackBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.PopUpToBuilder
import io.github.syrou.reaktiv.navigation.dsl.TypeSafeParameterBuilder
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.NavigationConfig
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.TypedParam
import io.github.syrou.reaktiv.navigation.util.SimpleRouteResolver
import kotlinx.coroutines.flow.first

class NavigationLogic(
    private val storeAccessor: StoreAccessor,
    private val parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) : ModuleLogic<NavigationAction>() {

    // ==========================================
    // SIMPLE NAVIGATION METHODS (Original)
    // ==========================================

    /**
     * Navigate with simple parameter encoding
     * Maps encoded as "key1:value1,key2:value2"
     * Lists encoded as "item1,item2,item3"
     */
    suspend fun navigate(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        println("DEBUG: 🚀 Simple navigate called with route: '$route'")
        println("DEBUG: 📝 Simple parameters: $params")

        val currentState = getCurrentNavigationState()

        // Apply configuration
        val builder = NavigationBuilder(route, params)
        config?.let { builder.apply(it) }
        val navigationConfig = builder.build()

        // Validate configuration
        validateNavigationConfig(navigationConfig)

        // Resolve the route
        val resolution = SimpleRouteResolver.resolve(
            route = route,
            graphDefinitions = currentState.graphDefinitions,
            availableScreens = currentState.availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        val effectiveGraphId = resolution.getEffectiveGraphId()

        // Simple encoding of parameters
        val encodedParams = encodeSimpleParameters(resolution.extractedParams + params)

        println("DEBUG: 🔐 Simple encoded parameters: $encodedParams")

        // Create navigation entry
        val newEntry = NavigationEntry(
            screen = resolution.targetScreen,
            params = encodedParams,
            graphId = effectiveGraphId
        )

        // Execute navigation
        executeNavigation(newEntry, navigationConfig, currentState)
    }

    /**
     * Navigate with simple validation
     */
    suspend fun navigateWithValidation(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null,
        validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
    ) {
        println("DEBUG: 🔍 Simple navigate with validation")

        val currentState = getCurrentNavigationState()
        val resolution = SimpleRouteResolver.resolve(
            route = route,
            graphDefinitions = currentState.graphDefinitions,
            availableScreens = currentState.availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        val allRawParams = resolution.extractedParams + params

        storeAccessor.dispatch(NavigationAction.SetLoading(true))

        try {
            if (validate(storeAccessor, allRawParams)) {
                navigate(route, params, config)
            }
        } finally {
            storeAccessor.dispatch(NavigationAction.SetLoading(false))
        }
    }

    // ==========================================
    // TYPE-SAFE NAVIGATION METHODS (New)
    // ==========================================

    /**
     * Navigate with type-safe parameter encoding using kotlinx.serialization
     */
    suspend fun navigateTypeSafe(
        route: String,
        params: TypeSafeParameterBuilder.() -> Unit = {},
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        println("DEBUG: 🚀 Type-safe navigate called with route: '$route'")

        val paramBuilder = TypeSafeParameterBuilder().apply(params)
        val typedParams = paramBuilder.build()

        println("DEBUG: 📝 Type-safe parameters: ${typedParams.keys}")

        val currentState = getCurrentNavigationState()

        // Apply configuration
        val builder = NavigationBuilder(route, emptyMap()) // TypeSafe doesn't use the old params map
        config?.let { builder.apply(it) }
        val navigationConfig = builder.build()

        validateNavigationConfig(navigationConfig)

        // Resolve the route
        val resolution = SimpleRouteResolver.resolve(
            route = route,
            graphDefinitions = currentState.graphDefinitions,
            availableScreens = currentState.availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        val effectiveGraphId = resolution.getEffectiveGraphId()

        // Type-safe encoding of parameters
        val encodedParams = encodeTypeSafeParameters(typedParams)

        println("DEBUG: 🔐 Type-safe encoded parameters: ${encodedParams.keys}")

        // Create navigation entry
        val newEntry = NavigationEntry(
            screen = resolution.targetScreen,
            params = encodedParams,
            graphId = effectiveGraphId
        )

        // Execute navigation
        executeNavigation(newEntry, navigationConfig, currentState)
    }

    /**
     * Navigate with type-safe validation
     */
    suspend fun navigateTypeSafeWithValidation(
        route: String,
        params: TypeSafeParameterBuilder.() -> Unit = {},
        config: (NavigationBuilder.() -> Unit)? = null,
        validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
    ) {
        println("DEBUG: 🔍 Type-safe navigate with validation")

        val paramBuilder = TypeSafeParameterBuilder().apply(params)
        val typedParams = paramBuilder.build()

        val currentState = getCurrentNavigationState()
        val resolution = SimpleRouteResolver.resolve(
            route = route,
            graphDefinitions = currentState.graphDefinitions,
            availableScreens = currentState.availableScreens
        ) ?: throw RouteNotFoundException("Could not resolve route: $route")

        // For validation, we need to extract the actual values from TypedParam wrappers
        val rawParams = resolution.extractedParams + extractRawValues(typedParams)

        storeAccessor.dispatch(NavigationAction.SetLoading(true))

        try {
            if (validate(storeAccessor, rawParams)) {
                navigateTypeSafe(route, params, config)
            }
        } finally {
            storeAccessor.dispatch(NavigationAction.SetLoading(false))
        }
    }

    // ==========================================
    // SHARED NAVIGATION METHODS
    // ==========================================

    /**
     * Navigate back in the navigation stack
     */
    suspend fun navigateBack() {
        val currentState = getCurrentNavigationState()

        if (!currentState.canGoBack) {
            println("DEBUG: ⛔ Cannot navigate back - no history available")
            return
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
                graphId = replacementResolution.getEffectiveGraphId()
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
                graphId = resolution.getEffectiveGraphId()
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
        navigate(route, params) {
            replaceWith(route)
        }
    }

    /**
     * Replace current screen with type-safe parameters
     */
    suspend fun replaceWithTypeSafe(
        route: String,
        params:     TypeSafeParameterBuilder.() -> Unit = {}
    ) {
        navigateTypeSafe(route, params) {
            replaceWith(route)
        }
    }

    /**
     * Clear all parameters from the current screen
     * Useful for removing temporary UI state, debug flags, or sensitive data
     */
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

    /**
     * Clear a specific parameter from the current screen
     * Useful for removing temporary flags, debug info, or expired data
     */
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

    /**
     * Clear all parameters from a specific route in the back stack
     * Useful for cleaning up parameters from screens the user might return to
     */
    suspend fun clearScreenParams(route: String) {
        val currentState = getCurrentNavigationState()
        if (!currentState.hasRoute(route)) {
            throw RouteNotFoundException("Route $route not found")
        }

        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.screen.route == route) {
                entry.copy(params = emptyMap())
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.screen.route == route) {
            currentState.currentEntry.copy(params = emptyMap())
        } else {
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedCurrentEntry,
                backStack = updatedBackStack
            )
        )
    }

    /**
     * Clear a specific parameter from a specific route in the back stack
     * Useful for removing temporary data from screens in the navigation history
     */
    suspend fun clearScreenParam(route: String, key: String) {
        val currentState = getCurrentNavigationState()
        if (!currentState.hasRoute(route)) {
            throw RouteNotFoundException("Route $route not found")
        }

        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.screen.route == route) {
                entry.copy(params = entry.params - key)
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.screen.route == route) {
            currentState.currentEntry.copy(params = currentState.currentEntry.params - key)
        } else {
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedCurrentEntry,
                backStack = updatedBackStack
            )
        )
    }

    private fun encodeSimpleParameters(params: Map<String, Any>): Map<String, Any> {
        return params.mapValues { (_, value) ->
            when (value) {
                is String -> {
                    if (needsEncoding(value)) {
                        parameterEncoder.encodeSimple(value)
                    } else {
                        value
                    }
                }
                else -> {
                    parameterEncoder.encodeSimple(value)
                }
            }
        }
    }

    private fun encodeTypeSafeParameters(params: Map<String, Any>): Map<String, Any> {
        return params.mapValues { (_, value) ->
            parameterEncoder.encodeMixed(value)
        }
    }

    private fun extractRawValues(typedParams: Map<String, Any>): Map<String, Any> {
        return typedParams.mapValues { (_, value) ->
            when (value) {
                is TypedParam<*> -> value.value
                else -> value
            } as Any
        }
    }

    private fun validateNavigationConfig(config: NavigationConfig) {
        if (config.clearBackStack && (config.popUpTo != null || config.replaceWith != null)) {
            throw IllegalStateException("Cannot combine clearBackStack with popUpTo or replaceWith")
        }
    }

    private fun needsEncoding(value: String): Boolean {
        return value.any { char ->
            !char.isLetterOrDigit() && char !in "-._~"
        }
    }

    private suspend fun executeNavigation(
        newEntry: NavigationEntry,
        config: NavigationConfig,
        currentState: NavigationState
    ) {
        val newBackStack = calculateNewBackStack(
            currentBackStack = currentState.backStack,
            newEntry = newEntry,
            config = config,
            currentState = currentState
        )

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newEntry,
                backStack = newBackStack
            )
        )
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

    private fun calculateNewBackStack(
        currentBackStack: List<NavigationEntry>,
        newEntry: NavigationEntry,
        config: NavigationConfig,
        currentState: NavigationState
    ): List<NavigationEntry> {
        return when {
            config.clearBackStack -> listOf(newEntry)
            config.popUpTo != null -> {
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
                    currentBackStack + newEntry
                }
            }
            config.replaceWith != null -> currentBackStack.dropLast(1) + newEntry
            else -> {
                val finalParams = if (config.forwardParams && currentBackStack.isNotEmpty()) {
                    currentBackStack.last().params + newEntry.params
                } else {
                    newEntry.params
                }
                currentBackStack + newEntry.copy(params = finalParams)
            }
        }
    }
}