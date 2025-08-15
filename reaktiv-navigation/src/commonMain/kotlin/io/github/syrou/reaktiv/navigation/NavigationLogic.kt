package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.ModalContext
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
        replaceCurrent: Boolean = false,
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        navigate {
            navigateTo(route, replaceCurrent)
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
        if (!precomputedData.routeToNavigatable.containsKey(route)) {
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
        if (!precomputedData.routeToNavigatable.containsKey(route)) {
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

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        // Check if the target is actually a Modal - if so, delegate to modal operation
        if (resolution.targetNavigatable is Modal) {
            executeModalOperation(builder, currentState)
            return
        }

        val stackPosition = currentState.backStack.size + 1
        val mergedParams = resolution.extractedParams + builder.encodeParameters()

        val newEntry = resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )

        // Handle modal dismissal if requested
        val baseBackStack = if (builder.shouldDismissModals) {
            // When dismissing modals, remove all modals from the backstack
            currentState.backStack.filter { it.isScreen }
        } else {
            currentState.backStack
        }
        
        val newBackStack = baseBackStack + newEntry
        val finalModalContexts = if (builder.shouldDismissModals) emptyMap() else currentState.activeModalContexts

        // Handle navigation from a modal: close modal and navigate normally, but preserve context for restoration
        if (currentState.isCurrentModal && !builder.shouldDismissModals && currentState.activeModalContexts.isNotEmpty()) {
            val currentModalRoute = currentState.currentEntry.navigatable.route
            val modalContext = currentState.activeModalContexts[currentModalRoute]
            
            if (modalContext != null) {
                val backStackBeforeModal = currentState.backStack.dropLast(1) // Remove the modal
                val newBackStackWithNewScreen = backStackBeforeModal + newEntry
                
                // Update modal context to track where we navigated to
                val updatedModalContext = modalContext.copy(
                    navigatedAwayToRoute = newEntry.navigatable.route
                )
                val originalUnderlyingScreenRoute = modalContext.originalUnderlyingScreenEntry.navigatable.route
                val updatedModalContexts = mapOf(originalUnderlyingScreenRoute to updatedModalContext)
                
                storeAccessor.dispatch(
                    NavigationAction.BatchUpdateWithModalContext(
                        currentEntry = newEntry,
                        backStack = newBackStackWithNewScreen,
                        modalContexts = updatedModalContexts,
                        bypassSpamProtection = builder.shouldBypassSpamProtection
                    )
                )
                return
            }
        }
        
        // Regular navigation or modal dismissal
        if (builder.shouldDismissModals || finalModalContexts.isNotEmpty()) {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdateWithModalContext(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    modalContexts = finalModalContexts,
                    bypassSpamProtection = builder.shouldBypassSpamProtection
                )
            )
        } else {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    bypassSpamProtection = builder.shouldBypassSpamProtection
                )
            )
        }
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

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val stackPosition = currentState.backStack.size
        val mergedParams = resolution.extractedParams + builder.encodeParameters()

        val newEntry = resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )

        // Handle modal dismissal if requested
        val baseBackStack = if (builder.shouldDismissModals) {
            currentState.backStack.filter { it.isScreen }
        } else {
            currentState.backStack
        }
        
        val newBackStack = baseBackStack.dropLast(1) + newEntry
        val finalModalContexts = if (builder.shouldDismissModals) emptyMap() else currentState.activeModalContexts

        if (builder.shouldDismissModals || finalModalContexts.isNotEmpty()) {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdateWithModalContext(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    modalContexts = finalModalContexts,
                    bypassSpamProtection = builder.shouldBypassSpamProtection
                )
            )
        } else {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    bypassSpamProtection = builder.shouldBypassSpamProtection
                )
            )
        }
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

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val stackPosition = currentState.backStack.size + 1
        val mergedParams = resolution.extractedParams + builder.encodeParameters()

        val newEntry = resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )

        val newBackStack = currentState.backStack + newEntry

        // Create modal context to remember the original underlying screen
        val originalUnderlyingScreen = if (currentState.isCurrentModal) {
            // If we're already in a modal, find the original underlying screen
            currentState.underlyingScreen ?: findUnderlyingScreenForModal(currentState.currentEntry, currentState.backStack)
        } else {
            // If we're on a regular screen, that's the underlying screen for this modal
            currentState.currentEntry
        }

        val modalContext = if (originalUnderlyingScreen != null) {
            ModalContext(
                modalEntry = newEntry,
                originalUnderlyingScreenEntry = originalUnderlyingScreen,
                createdFromScreenRoute = originalUnderlyingScreen.navigatable.route
            )
        } else null

        val updatedModalContexts = if (modalContext != null) {
            currentState.activeModalContexts + (newEntry.navigatable.route to modalContext)
        } else {
            currentState.activeModalContexts
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdateWithModalContext(
                currentEntry = newEntry,
                backStack = newBackStack,
                modalContexts = updatedModalContexts,
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

        // Check if the TARGET screen (where we're going back to) has a modal context
        val targetRoute = updatedTargetEntry.navigatable.route
        val modalContextForTargetScreen = currentState.activeModalContexts[targetRoute]
        
        if (modalContextForTargetScreen != null) {
            // We're going back to a screen that has a modal that should be restored
            val modalEntry = modalContextForTargetScreen.modalEntry
            
            // Restore the backstack to: [screens before target] + [target screen] + [modal]
            val backStackWithTargetAndModal = finalBackStack + modalEntry
            
            // Clear the navigatedAwayToRoute since we're restoring the modal
            val restoredModalContext = modalContextForTargetScreen.copy(navigatedAwayToRoute = null)
            val updatedModalContexts = mapOf(modalEntry.navigatable.route to restoredModalContext)
            
            storeAccessor.dispatch(
                NavigationAction.BatchUpdateWithModalContext(
                    currentEntry = modalEntry,
                    backStack = backStackWithTargetAndModal,
                    modalContexts = updatedModalContexts,
                    bypassSpamProtection = bypassSpamProtection
                )
            )
        } else {
            // Regular back navigation
            val currentRoute = currentState.currentEntry.navigatable.route
            val updatedModalContexts = if (currentState.activeModalContexts.containsKey(targetRoute)) {
                // We're going back to a modal - preserve its context
                currentState.activeModalContexts
            } else {
                // We're going back to a regular screen - clean up modal contexts for current screen
                currentState.activeModalContexts.filterKeys { it != currentRoute }
            }

            if (updatedModalContexts != currentState.activeModalContexts) {
                storeAccessor.dispatch(
                    NavigationAction.BatchUpdateWithModalContext(
                        currentEntry = updatedTargetEntry,
                        backStack = finalBackStack,
                        modalContexts = updatedModalContexts,
                        bypassSpamProtection = bypassSpamProtection
                    )
                )
            } else {
                storeAccessor.dispatch(
                    NavigationAction.Back(
                        currentEntry = updatedTargetEntry,
                        backStack = finalBackStack,
                        bypassSpamProtection = bypassSpamProtection
                    )
                )
            }
        }
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
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
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
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
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

    /**
     * Find the underlying screen for a modal by looking backwards in the backstack
     */
    private fun findUnderlyingScreenForModal(modalEntry: NavigationEntry, backStack: List<NavigationEntry>): NavigationEntry? {
        val modalIndex = backStack.indexOf(modalEntry)
        if (modalIndex <= 0) return null
        
        // Look backwards from modal position to find the screen it was opened from
        return backStack.subList(0, modalIndex).lastOrNull { it.isScreen }
    }

    /**
     * Get the current navigation state
     */
    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }
}