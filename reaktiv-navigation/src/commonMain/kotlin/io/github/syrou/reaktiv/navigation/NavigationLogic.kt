package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.dsl.NavigationStep
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.RouteResolution
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
     * Execute the navigation operations built by NavigationBuilder
     */
    private suspend fun executeNavigation(builder: NavigationBuilder) {
        val initialState = getCurrentNavigationState()
        
        // Compute the final state by applying all operations in sequence
        val finalState = computeFinalNavigationState(builder.operations, initialState)
        
        // Dispatch once with the final computed state
        if (finalState.modalContexts != initialState.activeModalContexts) {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdateWithModalContext(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = finalState.modalContexts
                )
            )
        } else {
            storeAccessor.dispatch(
                NavigationAction.BatchUpdate(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack
                )
            )
        }
    }
    
    private data class ComputedNavigationState(
        val currentEntry: NavigationEntry,
        val backStack: List<NavigationEntry>,
        val modalContexts: Map<String, ModalContext>
    )
    
    /**
     * Compute the final navigation state by applying all steps in sequence
     */
    private suspend fun computeFinalNavigationState(
        operations: List<NavigationStep>, 
        initialState: NavigationState
    ): ComputedNavigationState {
        // Collect outcomes of all operations in sequence
        val operationResults = mutableListOf<NavigationStepResult>()
        var currentEntry = initialState.currentEntry
        var backStack = initialState.backStack
        var modalContexts = initialState.activeModalContexts
        
        // Apply each operation and collect its outcome
        for (step in operations) {
            val stepResult = applyNavigationStep(step, currentEntry, backStack, modalContexts)
            operationResults.add(stepResult)
            
            // Update state for next operation
            currentEntry = stepResult.currentEntry
            backStack = stepResult.backStack
            modalContexts = stepResult.modalContexts
        }
        
        // Resolve final state from all collected outcomes
        val finalResult = if (operationResults.isNotEmpty()) {
            resolveCollectedOperations(operationResults, initialState)
        } else {
            ComputedNavigationState(
                currentEntry = initialState.currentEntry,
                backStack = initialState.backStack,
                modalContexts = initialState.activeModalContexts
            )
        }
        
        return finalResult
    }
    
    /**
     * Resolve the final state from all collected operation outcomes
     */
    private fun resolveCollectedOperations(
        results: List<NavigationStepResult>,
        initialState: NavigationState
    ): ComputedNavigationState {
        // Take the last result as the final state
        val finalResult = results.last()
        
        return ComputedNavigationState(
            currentEntry = finalResult.currentEntry,
            backStack = finalResult.backStack,
            modalContexts = finalResult.modalContexts
        )
    }
    
    
    /**
     * Apply a single navigation step to the current state
     */
    private suspend fun applyNavigationStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        return when (step.operation) {
            NavigationOperation.Back -> {
                applyBackStep(currentEntry, backStack, modalContexts)
            }
            
            NavigationOperation.PopUpTo -> {
                applyPopUpToStep(step, currentEntry, backStack, modalContexts)
            }
            
            NavigationOperation.ClearBackStack -> {
                applyClearBackStackStep(step, currentEntry, backStack, modalContexts)
            }
            
            NavigationOperation.Navigate -> {
                applyNavigateStep(step, currentEntry, backStack, modalContexts)
            }
            
            NavigationOperation.Replace -> {
                applyReplaceStep(step, currentEntry, backStack, modalContexts)
            }
        }
    }
    
    private data class NavigationStepResult(
        val currentEntry: NavigationEntry,
        val backStack: List<NavigationEntry>,
        val modalContexts: Map<String, ModalContext>
    )


    /**
     * Create a navigation entry with proper parameter encoding and position
     */
    private suspend fun createNavigationEntry(
        step: NavigationStep,
        resolution: RouteResolution,
        stackPosition: Int
    ): NavigationEntry {
        val encodedParams = parameterEncoder.encodeStepParameters(step.params)
        val mergedParams = resolution.extractedParams + encodedParams
        
        return resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )
    }
    
    /**
     * Create modal context for a new modal entry
     */
    private fun createModalContext(
        modalEntry: NavigationEntry,
        currentEntry: NavigationEntry,
        modalContexts: Map<String, ModalContext>
    ): ModalContext? {
        val originalUnderlyingScreen = if (currentEntry.isModal) {
            // Find original underlying screen from context or fallback logic
            modalContexts.values.firstOrNull()?.originalUnderlyingScreenEntry 
                ?: findUnderlyingScreenForModal(currentEntry, emptyList())
        } else {
            currentEntry
        }

        return if (originalUnderlyingScreen != null) {
            ModalContext(
                modalEntry = modalEntry,
                originalUnderlyingScreenEntry = originalUnderlyingScreen,
                createdFromScreenRoute = originalUnderlyingScreen.navigatable.route
            )
        } else null
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
    
    // Step application methods - pure functions that compute new state without side effects
    
    private fun applyBackStep(
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        if (backStack.size <= 1) {
            // Cannot navigate back - return current state unchanged
            return NavigationStepResult(
                currentEntry = currentEntry,
                backStack = backStack,
                modalContexts = modalContexts
            )
        }

        val newBackStack = backStack.dropLast(1)
        val targetEntry = newBackStack.last()
        val stackPosition = newBackStack.size
        val updatedTargetEntry = targetEntry.copy(stackPosition = stackPosition)
        val finalBackStack = newBackStack.dropLast(1) + updatedTargetEntry

        val targetRoute = updatedTargetEntry.navigatable.route
        val modalContextForTargetScreen = modalContexts[targetRoute]
        
        return if (modalContextForTargetScreen != null) {
            val modalEntry = modalContextForTargetScreen.modalEntry
            val backStackWithTargetAndModal = finalBackStack + modalEntry
            val restoredModalContext = modalContextForTargetScreen.copy(navigatedAwayToRoute = null)
            val updatedModalContexts = mapOf(modalEntry.navigatable.route to restoredModalContext)
            
            NavigationStepResult(
                currentEntry = modalEntry,
                backStack = backStackWithTargetAndModal,
                modalContexts = updatedModalContexts
            )
        } else {
            val currentRoute = currentEntry.navigatable.route
            val updatedModalContexts = if (modalContexts.containsKey(targetRoute)) {
                modalContexts
            } else {
                modalContexts.filterKeys { it != currentRoute }
            }

            NavigationStepResult(
                currentEntry = updatedTargetEntry,
                backStack = finalBackStack,
                modalContexts = updatedModalContexts
            )
        }
    }
    
    private suspend fun applyNavigateStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        val resolvedRoute = step.target?.resolve(precomputedData)
            ?: throw IllegalStateException("Navigate operation requires a target")

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")


        val isNavigatingToModal = resolution.targetNavigatable is Modal
        
        val baseBackStack = if (step.shouldDismissModals) {
            backStack.filter { it.isScreen }
        } else {
            backStack
        }
        
        val stackPosition = when {
            isNavigatingToModal -> backStack.size + 1  // Modals go on top of current stack
            baseBackStack.isEmpty() -> 1               // After clearBackStack, start fresh
            else -> baseBackStack.size + 1             // Normal case: add to existing backstack
        }
        
        val newEntry = createNavigationEntry(step, resolution, stackPosition)
        
        val newBackStack = if (isNavigatingToModal) {
            // For modals, add to current backstack without modifying it
            backStack + newEntry
        } else if (baseBackStack.isEmpty()) {
            // After clearBackStack, create fresh backstack with just the new entry
            listOf(newEntry)
        } else {
            // Normal case: add to existing backstack
            baseBackStack + newEntry
        }
        
        val finalModalContexts = when {
            step.shouldDismissModals -> emptyMap()
            isNavigatingToModal -> {
                val modalContext = createModalContext(newEntry, currentEntry, modalContexts)
                if (modalContext != null) {
                    modalContexts + (newEntry.navigatable.route to modalContext)
                } else {
                    modalContexts
                }
            }
            else -> modalContexts
        }

        // Handle navigation from modal to screen
        if (currentEntry.isModal && !step.shouldDismissModals && !isNavigatingToModal && modalContexts.isNotEmpty()) {
            val currentModalRoute = currentEntry.navigatable.route
            val modalContext = modalContexts[currentModalRoute]
            
            if (modalContext != null) {
                val backStackBeforeModal = backStack.dropLast(1)
                val newBackStackWithNewScreen = backStackBeforeModal + newEntry
                
                val updatedModalContext = modalContext.copy(
                    navigatedAwayToRoute = newEntry.navigatable.route
                )
                val originalUnderlyingScreenRoute = modalContext.originalUnderlyingScreenEntry.navigatable.route
                val updatedModalContexts = mapOf(originalUnderlyingScreenRoute to updatedModalContext)
                
                return NavigationStepResult(
                    currentEntry = newEntry,
                    backStack = newBackStackWithNewScreen,
                    modalContexts = updatedModalContexts
                )
            }
        }
        
        return NavigationStepResult(
            currentEntry = newEntry,
            backStack = newBackStack,
            modalContexts = finalModalContexts
        )
    }
    
    private suspend fun applyReplaceStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        val resolvedRoute = step.target?.resolve(precomputedData)
            ?: throw IllegalStateException("Replace operation requires a target")

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val newEntry = createNavigationEntry(step, resolution, backStack.size)

        val baseBackStack = if (step.shouldDismissModals) {
            backStack.filter { it.isScreen }
        } else {
            backStack
        }
        
        val newBackStack = baseBackStack.dropLast(1) + newEntry
        val finalModalContexts = if (step.shouldDismissModals) emptyMap() else modalContexts

        return NavigationStepResult(
            currentEntry = newEntry,
            backStack = newBackStack,
            modalContexts = finalModalContexts
        )
    }
    
    
    private suspend fun applyPopUpToStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        val popUpToRoute = step.popUpToTarget?.resolve(precomputedData)
            ?: throw IllegalStateException("PopUpTo operation requires a popUpTo target")
        val popIndex = precomputedData.routeResolver.findRouteInBackStack(
            targetRoute = popUpToRoute,
            backStack = backStack
        )

        if (popIndex < 0) {
            throw RouteNotFoundException("No match found for route $popUpToRoute")
        }

        val newBackStackAfterPop = if (step.popUpToInclusive) {
            backStack.take(popIndex)
        } else {
            backStack.take(popIndex + 1)
        }

        return if (step.target != null) {
            // PopUpTo with navigation target - pop and then navigate to target
            val resolvedRoute = step.target!!.resolve(precomputedData)
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
                ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

            val newEntry = createNavigationEntry(
                step, 
                resolution, 
                stackPosition = newBackStackAfterPop.size + 1
            )
            
            NavigationStepResult(
                currentEntry = newEntry,
                backStack = newBackStackAfterPop + newEntry,
                modalContexts = modalContexts
            )
        } else {
            // PopUpTo without target - pure backStack modifier
            
            // Handle edge case: if popping results in empty backStack, handle gracefully or throw exception
            if (newBackStackAfterPop.isEmpty()) {
                // If we're popping everything including current entry, this is an illegal state for standalone popUpTo
                // But it might be valid in multi-operation sequences where navigate operations follow
                if (currentEntry.navigatable.route == step.popUpToTarget?.resolve(precomputedData) && step.popUpToInclusive) {
                    throw IllegalStateException("Cannot pop up to route that would result in empty back stack")
                } else {
                    // Pop operation resulted in empty stack but currentEntry is different, preserve currentEntry
                    return NavigationStepResult(
                        currentEntry = currentEntry,
                        backStack = listOf(currentEntry),
                        modalContexts = modalContexts
                    )
                }
            }
            
            // Ensure currentEntry is in the backStack (backStack always includes currentEntry)  
            val finalBackStack = if (newBackStackAfterPop.lastOrNull()?.navigatable?.route == currentEntry.navigatable.route) {
                newBackStackAfterPop
            } else {
                newBackStackAfterPop + currentEntry
            }
            
            NavigationStepResult(
                currentEntry = currentEntry,
                backStack = finalBackStack,
                modalContexts = modalContexts
            )
        }
    }
    
    private suspend fun applyClearBackStackStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        if (step.target != null) {
            // clearBackStack with navigation target - navigate and clear history
            val resolvedRoute = step.target!!.resolve(precomputedData)
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
                ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

            val newEntry = createNavigationEntry(step, resolution, stackPosition = 1)
            
            return NavigationStepResult(
                currentEntry = newEntry,
                backStack = listOf(newEntry), // New entry becomes the only entry in the stack
                modalContexts = emptyMap() // Clear back stack also clears modal contexts
            )
        } else {
            // clearBackStack without target - completely clear backstack for fresh start
            // Current entry remains until next operation changes it
            return NavigationStepResult(
                currentEntry = currentEntry,
                backStack = emptyList(), // Completely clear backStack for fresh start
                modalContexts = emptyMap() // Clear back stack also clears modal contexts
            )
        }
    }
}