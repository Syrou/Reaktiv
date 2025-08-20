package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.dsl.NavigationStep
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowContext
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.applyModification
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.model.getRoute
import io.github.syrou.reaktiv.navigation.model.getParams
import io.github.syrou.reaktiv.navigation.param.SerializableParam
import io.github.syrou.reaktiv.navigation.util.computeGuidedFlowProperties
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperation
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperationBuilder
import kotlinx.datetime.Clock
import kotlinx.coroutines.flow.first

class NavigationLogic(
    val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val guidedFlowDefinitions: Map<String, GuidedFlowDefinition> = emptyMap(),
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
            params.forEach { (key, value) -> putRaw(key, value) }
            navigateTo(route, replaceCurrent)
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
        val finalState = computeFinalNavigationState(builder.operations, initialState, builder.guidedFlowContext)
        
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
        initialState: NavigationState,
        guidedFlowContext: GuidedFlowContext? = null
    ): ComputedNavigationState {
        // Collect outcomes of all operations in sequence
        val operationResults = mutableListOf<NavigationStepResult>()
        var currentEntry = initialState.currentEntry
        var backStack = initialState.backStack
        var modalContexts = initialState.activeModalContexts
        
        // Apply each operation and collect its outcome
        for (step in operations) {
            val stepResult = applyNavigationStep(step, currentEntry, backStack, modalContexts, guidedFlowContext)
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
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null
    ): NavigationStepResult {
        return when (step.operation) {
            NavigationOperation.Back -> {
                applyBackStep(currentEntry, backStack, modalContexts)
            }
            
            NavigationOperation.PopUpTo -> {
                applyPopUpToStep(step, currentEntry, backStack, modalContexts, guidedFlowContext)
            }
            
            NavigationOperation.ClearBackStack -> {
                applyClearBackStackStep(step, currentEntry, backStack, modalContexts, guidedFlowContext)
            }
            
            NavigationOperation.Navigate -> {
                applyNavigateStep(step, currentEntry, backStack, modalContexts, guidedFlowContext)
            }
            
            NavigationOperation.Replace -> {
                applyReplaceStep(step, currentEntry, backStack, modalContexts, guidedFlowContext)
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
        stackPosition: Int,
        guidedFlowContext: GuidedFlowContext? = null
    ): NavigationEntry {
        val encodedParams = parameterEncoder.encodeStepParameters(step.params)
        val mergedParams = resolution.extractedParams + encodedParams
        
        return resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition,
            guidedFlowContext = guidedFlowContext
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
     * Execute a set of guided flow operations atomically for the current active flow
     */
    suspend fun executeGuidedFlowOperations(block: suspend GuidedFlowOperationBuilder.() -> Unit) {
        val currentState = getCurrentNavigationState()
        val activeFlowRoute = currentState.activeGuidedFlowState?.flowRoute
            ?: throw IllegalStateException("No active guided flow to operate on")
        
        executeGuidedFlowOperations(activeFlowRoute, block)
    }
    
    /**
     * Execute guided flow operations for a specific flow route
     */
    suspend fun executeGuidedFlowOperations(flowRoute: String, block: suspend GuidedFlowOperationBuilder.() -> Unit) {
        val builder = GuidedFlowOperationBuilder(flowRoute, storeAccessor)
        builder.apply { block() }
        builder.validate()
        
        executeGuidedFlowOperations(builder)
    }
    
    /**
     * Execute the guided flow operations built by GuidedFlowOperationBuilder
     * This method handles all operations atomically - modifications and navigation are applied
     * to compute the final state, then dispatched as batch updates
     */
    private suspend fun executeGuidedFlowOperations(builder: GuidedFlowOperationBuilder) {
        val operations = builder.getOperations()
        val flowRoute = builder.getFlowRoute()
        
        var currentState = getCurrentNavigationState()
        var currentDefinition = currentState.guidedFlowDefinitions[flowRoute] ?: return
        var currentFlowState = currentState.activeGuidedFlowState
        var finalNavigationRoute: String? = null
        var finalNavigationParams: Map<String, Any> = emptyMap()
        var guidedFlowContext: GuidedFlowContext? = null
        
        // Apply all operations in sequence
        for (operation in operations) {
            when (operation) {
                is GuidedFlowOperation.Modify -> {
                    // Apply modification to definition
                    currentDefinition = currentDefinition.applyModification(operation.modification)
                    
                    // Update flow state if there's an active flow for this route
                    if (currentFlowState?.flowRoute == flowRoute) {
                        currentFlowState = adjustFlowStateForModification(
                            currentFlowState, 
                            operation.modification, 
                            currentDefinition
                        )
                    }
                }
                is GuidedFlowOperation.NextStep -> {
                    // Compute next step with current definition
                    if (currentFlowState?.flowRoute == flowRoute) {
                        val result = computeNextStep(currentFlowState, currentDefinition, operation.params)
                        currentFlowState = result.flowState
                        if (result.shouldNavigate) {
                            finalNavigationRoute = result.route
                            finalNavigationParams = result.params
                            guidedFlowContext = result.guidedFlowContext
                        }
                    }
                }
                is GuidedFlowOperation.PreviousStep -> {
                    // Compute previous step with current definition
                    if (currentFlowState?.flowRoute == flowRoute) {
                        val result = computePreviousStep(currentFlowState, currentDefinition)
                        currentFlowState = result.flowState
                        if (result.shouldNavigate) {
                            finalNavigationRoute = result.route
                            finalNavigationParams = result.params
                            guidedFlowContext = result.guidedFlowContext
                        }
                    }
                }
            }
        }
        
        // Dispatch final state updates
        storeAccessor.dispatch(NavigationAction.CreateGuidedFlow(currentDefinition))
        if (currentFlowState != null) {
            if (currentFlowState.isCompleted) {
                // If flow is completed, execute completion handler and clear flow state
                currentDefinition.onComplete?.let { onCompleteBlock ->
                    onCompleteBlock(storeAccessor)
                }
                storeAccessor.dispatch(NavigationAction.ClearActiveGuidedFlow)
            } else {
                storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(currentFlowState))
            }
        }
        
        // Perform final navigation if needed
        if (finalNavigationRoute != null) {
            navigate(finalNavigationRoute, finalNavigationParams, false) {
                guidedFlowContext?.let { setGuidedFlowContext(it) }
            }
        }
    }

    /**
     * Adjust flow state for a single modification
     */
    private fun adjustFlowStateForModification(
        flowState: GuidedFlowState,
        modification: FlowModification,
        updatedDefinition: GuidedFlowDefinition
    ): GuidedFlowState {
        val adjustedIndex = when (modification) {
            is FlowModification.RemoveSteps -> {
                val removedIndices = modification.stepIndices.sorted()
                val removedBeforeCurrent = removedIndices.count { it < flowState.currentStepIndex }
                val adjustedIndex = flowState.currentStepIndex - removedBeforeCurrent
                adjustedIndex.coerceAtMost(updatedDefinition.steps.size - 1).coerceAtLeast(0)
            }
            is FlowModification.AddSteps -> {
                val insertIndex = if (modification.insertIndex == -1) {
                    Int.MAX_VALUE // Will be > any currentIndex
                } else {
                    modification.insertIndex
                }
                if (insertIndex <= flowState.currentStepIndex) {
                    flowState.currentStepIndex + modification.steps.size
                } else {
                    flowState.currentStepIndex
                }
            }
            else -> flowState.currentStepIndex // No index adjustment needed for replace/update operations
        }
        
        return computeGuidedFlowProperties(
            flowState.copy(currentStepIndex = adjustedIndex),
            updatedDefinition
        )
    }

    /**
     * Data class for navigation computation results
     */
    private data class NavigationResult(
        val flowState: GuidedFlowState,
        val shouldNavigate: Boolean = false,
        val route: String = "",
        val params: Map<String, Any> = emptyMap(),
        val guidedFlowContext: GuidedFlowContext? = null
    )

    /**
     * Compute next step navigation
     */
    private suspend fun computeNextStep(
        flowState: GuidedFlowState,
        definition: GuidedFlowDefinition,
        params: Map<String, Any> = emptyMap()
    ): NavigationResult {
        return when {
            flowState.isOnFinalStep -> {
                // Complete flow - no navigation needed
                NavigationResult(
                    flowState = flowState.copy(
                        completedAt = kotlinx.datetime.Clock.System.now(),
                        isCompleted = true,
                        duration = kotlinx.datetime.Clock.System.now() - flowState.startedAt
                    )
                )
            }
            else -> {
                val nextStepIndex = flowState.currentStepIndex + 1
                val nextStep = definition.steps[nextStepIndex]
                
                val stepRoute = nextStep.getRoute(precomputedData)
                val stepParams = params + nextStep.getParams()
                val context = GuidedFlowContext(
                    flowRoute = flowState.flowRoute,
                    stepIndex = nextStepIndex,
                    totalSteps = definition.steps.size
                )
                
                NavigationResult(
                    flowState = computeGuidedFlowProperties(
                        flowState.copy(currentStepIndex = nextStepIndex),
                        definition
                    ),
                    shouldNavigate = true,
                    route = stepRoute,
                    params = stepParams,
                    guidedFlowContext = context
                )
            }
        }
    }

    /**
     * Compute previous step navigation
     */
    private suspend fun computePreviousStep(
        flowState: GuidedFlowState,
        definition: GuidedFlowDefinition
    ): NavigationResult {
        return if (flowState.currentStepIndex > 0) {
            val previousStepIndex = flowState.currentStepIndex - 1
            val previousStep = definition.steps[previousStepIndex]
            
            val stepRoute = previousStep.getRoute(precomputedData)
            val stepParams = previousStep.getParams()
            val context = GuidedFlowContext(
                flowRoute = flowState.flowRoute,
                stepIndex = previousStepIndex,
                totalSteps = definition.steps.size
            )
            
            NavigationResult(
                flowState = computeGuidedFlowProperties(
                    flowState.copy(currentStepIndex = previousStepIndex),
                    definition
                ),
                shouldNavigate = true,
                route = stepRoute,
                params = stepParams,
                guidedFlowContext = context
            )
        } else {
            NavigationResult(flowState = flowState)
        }
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
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null
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
        
        val newEntry = createNavigationEntry(step, resolution, stackPosition, guidedFlowContext)
        
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
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null
    ): NavigationStepResult {
        val resolvedRoute = step.target?.resolve(precomputedData)
            ?: throw IllegalStateException("Replace operation requires a target")

        val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

        val newEntry = createNavigationEntry(step, resolution, backStack.size, guidedFlowContext)

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
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null
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
                stackPosition = newBackStackAfterPop.size + 1,
                guidedFlowContext = guidedFlowContext
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
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null
    ): NavigationStepResult {
        if (step.target != null) {
            // clearBackStack with navigation target - navigate and clear history
            val resolvedRoute = step.target!!.resolve(precomputedData)
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
                ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

            val newEntry = createNavigationEntry(step, resolution, stackPosition = 1, guidedFlowContext)
            
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

    override suspend fun invoke(action: ModuleAction) {
        when (action) {
            is NavigationAction.StartGuidedFlow -> handleStartGuidedFlow(action)
            is NavigationAction.NextStep -> handleNextStep(action)
            is NavigationAction.PreviousStep -> handlePreviousStep()
            is NavigationAction.ModifyGuidedFlow -> handleModifyGuidedFlowPostReducer(action)
            else -> {
                // Other actions are handled by the reducer
            }
        }
    }

    private suspend fun handleStartGuidedFlow(action: NavigationAction.StartGuidedFlow) {
        val currentState = getCurrentNavigationState()
        // Check runtime modifications first, then fall back to base definitions
        val definition = currentState.guidedFlowDefinitions[action.guidedFlow.route] 
            ?: guidedFlowDefinitions[action.guidedFlow.route]
        
        if (definition != null && definition.steps.isNotEmpty()) {
            // 1. Create flow state
            val flowState = computeGuidedFlowProperties(
                GuidedFlowState(
                    flowRoute = action.guidedFlow.route,
                    startedAt = Clock.System.now()
                ),
                definition
            )
            storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(flowState))
            
            // 2. Navigate to first step with flow context using NavigationBuilder
            val firstStep = definition.steps.first()
            val stepRoute = firstStep.getRoute(precomputedData)
            val stepParams = action.params + firstStep.getParams()
            
            // Use NavigationBuilder to properly handle parameters
            val builder = NavigationBuilder(storeAccessor)
            builder.navigateTo(stepRoute, false) { 
                // Add step parameters - SerializableParam objects will be handled correctly
                stepParams.forEach { (key, value) ->
                    putRaw(key, value)
                }
            }
            builder.setGuidedFlowContext(GuidedFlowContext(
                flowRoute = action.guidedFlow.route,
                stepIndex = 0,
                totalSteps = definition.steps.size
            ))
            
            // Execute the navigation through the proper pipeline
            executeNavigation(builder)
        }
    }

    private suspend fun handleNextStep(action: NavigationAction.NextStep) {
        val currentState = getCurrentNavigationState()
        val flowState = currentState.activeGuidedFlowState
        val definition = flowState?.let { 
            // Check runtime modifications first, then fall back to base definitions
            currentState.guidedFlowDefinitions[it.flowRoute] 
                ?: guidedFlowDefinitions[it.flowRoute]
        }
        
        if (flowState != null && definition != null) {
            when {
                flowState.isOnFinalStep -> {
                    // Complete flow
                    val completedFlowState = flowState.copy(
                        completedAt = Clock.System.now(),
                        isCompleted = true,
                        duration = Clock.System.now() - flowState.startedAt
                    )
                    storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(completedFlowState))
                    
                    // Execute completion handler directly with StoreAccessor
                    definition.onComplete?.let { onCompleteBlock ->
                        onCompleteBlock(storeAccessor)
                    }
                    
                    // Clear the guided flow state
                    storeAccessor.dispatch(NavigationAction.ClearActiveGuidedFlow)
                }
                else -> {
                    // Move to next step
                    val nextStepIndex = flowState.currentStepIndex + 1
                    val nextStep = definition.steps[nextStepIndex]
                    
                    // Update flow state
                    val updatedFlow = computeGuidedFlowProperties(
                        flowState.copy(currentStepIndex = nextStepIndex),
                        definition
                    )
                    storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(updatedFlow))
                    
                    // Navigate to next step using NavigationBuilder for proper parameter encoding
                    val stepRoute = nextStep.getRoute(precomputedData)
                    val stepParams = action.params + nextStep.getParams()
                    
                    // Use NavigationBuilder to properly handle parameters
                    val builder = NavigationBuilder(storeAccessor)
                    builder.navigateTo(stepRoute, false) { 
                        // Add step parameters - SerializableParam objects will be handled correctly
                        stepParams.forEach { (key, value) ->
                            putRaw(key, value)
                        }
                    }
                    builder.setGuidedFlowContext(GuidedFlowContext(
                        flowRoute = flowState.flowRoute,
                        stepIndex = nextStepIndex,
                        totalSteps = definition.steps.size
                    ))
                    
                    // Execute the navigation through the proper pipeline
                    executeNavigation(builder)
                }
            }
        }
    }

    private suspend fun handlePreviousStep() {
        val currentState = getCurrentNavigationState()
        val flowState = currentState.activeGuidedFlowState
        val definition = flowState?.let { 
            // Check runtime modifications first, then fall back to base definitions
            currentState.guidedFlowDefinitions[it.flowRoute] 
                ?: guidedFlowDefinitions[it.flowRoute]
        }
        
        if (flowState != null && definition != null && flowState.currentStepIndex > 0) {
            val previousStepIndex = flowState.currentStepIndex - 1
            val previousStep = definition.steps[previousStepIndex]
            
            // Update flow state
            val updatedFlow = computeGuidedFlowProperties(
                flowState.copy(currentStepIndex = previousStepIndex),
                definition
            )
            storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(updatedFlow))
            
            // Navigate to previous step using NavigationBuilder for proper parameter encoding
            val stepRoute = previousStep.getRoute(precomputedData)
            val stepParams = previousStep.getParams()
            
            // Use NavigationBuilder to properly handle parameters
            val builder = NavigationBuilder(storeAccessor)
            builder.navigateTo(stepRoute, false) { 
                // Add step parameters - SerializableParam objects will be handled correctly
                stepParams.forEach { (key, value) ->
                    putRaw(key, value)
                }
            }
            builder.setGuidedFlowContext(GuidedFlowContext(
                flowRoute = flowState.flowRoute,
                stepIndex = previousStepIndex,
                totalSteps = definition.steps.size
            ))
            
            // Execute the navigation through the proper pipeline
            executeNavigation(builder)
        }
    }

    private suspend fun handleModifyGuidedFlowPostReducer(action: NavigationAction.ModifyGuidedFlow) {
        // At this point, the reducer has already updated the flow definition
        // We just need to update the active flow state if there's one for this route
        val currentState = getCurrentNavigationState()
        val updatedDefinition = currentState.guidedFlowDefinitions[action.flowRoute]
        
        if (updatedDefinition != null) {
            // If there's an active flow for this route, update its state if needed
            currentState.activeGuidedFlowState?.let { flowState ->
                if (flowState.flowRoute == action.flowRoute) {
                    // Handle modifications that might affect current step index
                    val updatedFlowState = when (action.modification) {
                        is FlowModification.RemoveSteps -> {
                            val removedIndices = action.modification.stepIndices.sorted()
                            var adjustedCurrentIndex = flowState.currentStepIndex
                            
                            // Count how many steps before current index were removed
                            val removedBeforeCurrent = removedIndices.count { it < flowState.currentStepIndex }
                            adjustedCurrentIndex -= removedBeforeCurrent
                            
                            // Ensure index is within bounds of new definition
                            val finalIndex = adjustedCurrentIndex.coerceAtMost(updatedDefinition.steps.size - 1).coerceAtLeast(0)
                            
                            computeGuidedFlowProperties(
                                flowState.copy(currentStepIndex = finalIndex),
                                updatedDefinition
                            )
                        }
                        is FlowModification.AddSteps -> {
                            val insertIndex = if (action.modification.insertIndex == -1) {
                                // When insertIndex is -1, steps were added at the end, so no adjustment needed
                                updatedDefinition.steps.size // This will be > currentStepIndex
                            } else {
                                action.modification.insertIndex
                            }
                            
                            // If steps are added before or at current position, adjust index
                            val adjustedCurrentIndex = if (insertIndex <= flowState.currentStepIndex) {
                                flowState.currentStepIndex + action.modification.steps.size
                            } else {
                                flowState.currentStepIndex
                            }
                            computeGuidedFlowProperties(
                                flowState.copy(currentStepIndex = adjustedCurrentIndex),
                                updatedDefinition
                            )
                        }
                        is FlowModification.ReplaceStep, is FlowModification.UpdateStepParams, is FlowModification.UpdateOnComplete -> {
                            // For step replacement, parameter updates, and onComplete updates, just recompute properties
                            computeGuidedFlowProperties(flowState, updatedDefinition)
                        }
                    }
                    
                    // Dispatch the updated flow state
                    storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(updatedFlowState))
                }
            }
        }
    }
}