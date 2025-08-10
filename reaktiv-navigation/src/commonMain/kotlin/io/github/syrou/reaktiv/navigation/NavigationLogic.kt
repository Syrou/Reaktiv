package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import kotlinx.coroutines.flow.first

class NavigationLogic(
    val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) : ModuleLogic<NavigationAction>() {


    /**
     * Execute a single navigation operation synchronously
     * This ensures that when the method returns, the navigation state has been updated
     */
    suspend fun navigate(block: suspend NavigationBuilder.() -> Unit) {
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.apply { block() }
        builder.validate()
        executeNavigation(builder)
    }

    /**
     * Navigate to a specific route with parameters
     */
    suspend fun navigate(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        navigate {
            navigateTo(route)
            params.forEach { (key, value) -> putRaw(key, value) }
            config?.invoke(this)
        }
    }

    /**
     * Navigate back in the navigation stack
     */
    suspend fun navigateBack() {
        val currentState = getCurrentNavigationState()
        if (!currentState.canGoBack) {
            ReaktivDebug.nav("⛔ Cannot navigate back - no history available")
            return
        }
        navigate {
            navigateBack()
        }
    }

    /**
     * Pop up to a specific route in the backstack
     */
    suspend fun popUpTo(route: String, inclusive: Boolean = false) {
        navigate {
            popUpTo(route, inclusive)
        }
    }

    /**
     * Replace the current screen with a new one
     */
    suspend fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        navigate {
            replaceWith(route)
            params.forEach { (key, value) -> putRaw(key, value) }
        }
    }

    /**
     * Clear the entire backstack and optionally navigate to a new route
     */
    suspend fun clearBackStack(newRoute: String? = null, params: Map<String, Any> = emptyMap()) {
        if (newRoute != null) {
            navigate {
                navigateTo(newRoute)
                params.forEach { (key, value) -> putRaw(key, value) }
                clearBackStack()
            }
        } else {
            navigate {
                clearBackStack()
            }
        }
    }

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

        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.navigatable.route == route) {
                entry.copy(params = emptyMap())
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.navigatable.route == route) {
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

    suspend fun clearScreenParam(route: String, key: String) {
        val currentState = getCurrentNavigationState()
        if (!currentState.hasRoute(route)) {
            throw RouteNotFoundException("Route $route not found")
        }

        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.navigatable.route == route) {
                entry.copy(params = entry.params - key)
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.navigatable.route == route) {
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

    /**
     * Execute the navigation operation built by NavigationBuilder
     */
    private suspend fun executeNavigation(builder: NavigationBuilder) {
        val currentState = getCurrentNavigationState()

        when (builder.operation) {
            NavigationOperation.Back -> {
                executeBackOperation(currentState, builder.shouldBypassSpamProtection)
            }

            NavigationOperation.PopUpTo -> {
                if (builder.shouldClearBackStack) {
                    throw IllegalStateException("Can not clearBackstack and popUpTo at the same time")
                }
                executePopUpToOperation(builder, currentState)
            }

            NavigationOperation.ClearBackStack -> {
                executeClearBackStackOperation(builder, currentState)
            }

            NavigationOperation.Navigate -> {
                executeNavigationOperation(builder, currentState)
            }

            NavigationOperation.Replace -> {
                if (builder.shouldClearBackStack) {
                    throw IllegalStateException("Can not clearBackstack and replace at the same time")
                }
                executeReplaceOperation(builder, currentState)
            }

            NavigationOperation.Modal -> {
                executeModalOperation(builder, currentState)
            }
        }
    }

    /**
     * Execute a standard navigation operation and return the action
     */
    private suspend fun executeNavigationOperation(
        builder: NavigationBuilder,
        currentState: NavigationState
    ) {
        val resolvedRoute = builder.target?.resolve(precomputedData)
            ?: throw IllegalStateException("Navigate operation requires a target")

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, currentState.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val stackPosition = currentState.backStack.size + 1
        val mergedParams = resolution.extractedParams + builder.encodeParameters()

        val newEntry = resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )

        val finalEntry = applyForwardParams(newEntry, builder, currentState)
        val newBackStack = currentState.backStack + finalEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = finalEntry,
                backStack = newBackStack,
                bypassSpamProtection = builder.shouldBypassSpamProtection
            )
        )
    }

    /**
     * Execute a replace operation and return the action
     */
    private suspend fun executeReplaceOperation(
        builder: NavigationBuilder,
        currentState: NavigationState
    ) {
        val resolvedRoute = builder.target?.resolve(precomputedData)
            ?: throw IllegalStateException("Replace operation requires a target")

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, currentState.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val stackPosition = currentState.backStack.size
        val mergedParams = resolution.extractedParams + builder.encodeParameters()

        val newEntry = resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )

        val finalEntry = applyForwardParams(newEntry, builder, currentState)
        val newBackStack = currentState.backStack.dropLast(1) + finalEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = finalEntry,
                backStack = newBackStack,
                bypassSpamProtection = builder.shouldBypassSpamProtection
            )
        )
    }

    /**
     * Execute a modal presentation operation and return the action
     */
    private suspend fun executeModalOperation(
        builder: NavigationBuilder,
        currentState: NavigationState
    ) {
        val resolvedRoute = builder.target?.resolve(precomputedData)
            ?: throw IllegalStateException("Modal operation requires a target")

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, currentState.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val stackPosition = currentState.backStack.size + 1
        val mergedParams = resolution.extractedParams + builder.encodeParameters()

        val newEntry = resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )

        val finalEntry = applyForwardParams(newEntry, builder, currentState)
        val newBackStack = currentState.backStack + finalEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = finalEntry,
                backStack = newBackStack,
                bypassSpamProtection = builder.shouldBypassSpamProtection
            )
        )
    }

    /**
     * Execute a back navigation operation and return the action
     */
    private suspend fun executeBackOperation(
        currentState: NavigationState,
        bypassSpamProtection: Boolean = false
    ) {
        if (!currentState.canGoBack) {
            ReaktivDebug.nav("⛔ Cannot navigate back - no history available")
            throw IllegalStateException("Cannot navigate back - no history available")
        }

        val newBackStack = currentState.backStack.dropLast(1)
        val targetEntry = newBackStack.last()
        val stackPosition = newBackStack.size
        val updatedTargetEntry = targetEntry.copy(stackPosition = stackPosition)
        val finalBackStack = newBackStack.dropLast(1) + updatedTargetEntry

        ReaktivDebug.nav("🔙 Navigate back: ${currentState.currentEntry.navigatable.route} -> ${updatedTargetEntry.navigatable.route}")

        storeAccessor.dispatch(
            NavigationAction.Back(
                currentEntry = updatedTargetEntry,
                backStack = finalBackStack,
                bypassSpamProtection = bypassSpamProtection
            )
        )
    }

    /**
     * Execute a popUpTo operation and return the action
     */
    private suspend fun executePopUpToOperation(
        builder: NavigationBuilder,
        currentState: NavigationState
    ) {
        val popUpToRoute = builder.popUpToTarget?.resolve(precomputedData)
            ?: throw IllegalStateException("PopUpTo operation requires a popUpTo target")
        val popIndex = precomputedData.routeResolver.findRouteInBackStack(
            targetRoute = popUpToRoute,
            backStack = currentState.backStack
        )

        if (popIndex < 0) {
            throw RouteNotFoundException("No match found for route $popUpToRoute")
        }

        val newBackStackAfterPop = if (builder.popUpToInclusive) {
            currentState.backStack.take(popIndex)
        } else {
            currentState.backStack.take(popIndex + 1)
        }

        val finalBackStack = if (builder.target != null) {
            val resolvedRoute = builder.target!!.resolve(precomputedData)
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, currentState.availableNavigatables)
                ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

            val stackPosition = newBackStackAfterPop.size + 1
            val mergedParams = resolution.extractedParams + builder.encodeParameters()

            val newEntry = resolution.targetNavigatable.toNavigationEntry(
                params = mergedParams,
                graphId = resolution.getEffectiveGraphId(),
                stackPosition = stackPosition
            )
            newBackStackAfterPop + newEntry
        } else {
            newBackStackAfterPop
        }

        if (finalBackStack.isEmpty()) {
            throw IllegalStateException("PopUpTo operation would result in empty back stack")
        }

        val newCurrentEntry = finalBackStack.last()

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = newCurrentEntry,
                backStack = finalBackStack,
                bypassSpamProtection = builder.shouldBypassSpamProtection
            )
        )
    }

    /**
     * Execute a clear backstack operation and return the action
     */
    private suspend fun executeClearBackStackOperation(
        builder: NavigationBuilder,
        currentState: NavigationState
    ) {
        val finalBackStack = if (builder.target != null) {
            val resolvedRoute = builder.target!!.resolve(precomputedData)
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, currentState.availableNavigatables)
                ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

            val mergedParams = resolution.extractedParams + builder.encodeParameters()

            val newEntry = resolution.targetNavigatable.toNavigationEntry(
                params = mergedParams,
                graphId = resolution.getEffectiveGraphId(),
                stackPosition = 1
            )
            listOf(newEntry)
        } else {
            val updatedCurrentEntry = currentState.currentEntry.copy(stackPosition = 1)
            listOf(updatedCurrentEntry)
        }

        val newCurrentEntry = finalBackStack.last()

        storeAccessor.dispatch(
            NavigationAction.ClearBackstack(
                currentEntry = newCurrentEntry,
                backStack = finalBackStack,
                bypassSpamProtection = builder.shouldBypassSpamProtection
            )
        )
    }

    private fun applyForwardParams(
        entry: NavigationEntry,
        builder: NavigationBuilder,
        currentState: NavigationState
    ): NavigationEntry {
        return if (builder.shouldForwardParams && currentState.backStack.isNotEmpty()) {
            val currentParams = currentState.backStack.last().params
            val mergedParams = currentParams + entry.params
            entry.copy(params = mergedParams)
        } else {
            entry
        }
    }

    /**
     * Get the current navigation state
     */
    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }
}