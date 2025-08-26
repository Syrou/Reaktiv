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
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
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
import io.github.syrou.reaktiv.navigation.param.Params
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
     * Navigate to a route with optional parameters and configuration
     * @param route Target route to navigate to
     * @param params Parameters to pass to the destination screen
     * @param replaceCurrent If true, replaces current entry instead of pushing new one
     * @param config Optional additional navigation configuration
     */
    suspend fun navigate(
        route: String,
        params: Params = Params.empty(),
        replaceCurrent: Boolean = false,
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        navigate {
            params(params)
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
     * @param route Target route to pop back to
     * @param inclusive If true, also removes the target route from backstack
     */
    suspend fun popUpTo(route: String, inclusive: Boolean = false) {
        navigate {
            popUpTo(route, inclusive)
        }
    }


    /**
     * Clear the entire backstack and optionally navigate to a new route
     * @param newRoute Optional route to navigate to after clearing backstack
     * @param params Parameters for the new route if specified
     */
    suspend fun clearBackStack(newRoute: String? = null, params: Params = Params.empty()) {
        if (newRoute != null) {
            navigate {
                params(params)
                navigateTo(newRoute)
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
        val updatedEntry = currentState.currentEntry.copy(params = Params.empty())
        val updatedBackStack = currentState.backStack.dropLast(1) + updatedEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedEntry,
                backStack = updatedBackStack,
                modalContexts = null,
                operations = emptyList()
            )
        )
    }

    suspend fun clearCurrentScreenParam(key: String) {
        val currentState = getCurrentNavigationState()
        val updatedParams = currentState.currentEntry.params.without(key)
        val updatedEntry = currentState.currentEntry.copy(params = updatedParams)
        val updatedBackStack = currentState.backStack.dropLast(1) + updatedEntry

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedEntry,
                backStack = updatedBackStack,
                modalContexts = null,
                operations = emptyList()
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
                entry.copy(params = Params.empty())
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.navigatable.route == route) {
            currentState.currentEntry.copy(params = Params.empty())
        } else {
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedCurrentEntry,
                backStack = updatedBackStack,
                modalContexts = null,
                operations = emptyList()
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
                entry.copy(params = entry.params.without(key))
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.navigatable.route == route) {
            currentState.currentEntry.copy(params = currentState.currentEntry.params.without(key))
        } else {
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.BatchUpdate(
                currentEntry = updatedCurrentEntry,
                backStack = updatedBackStack,
                modalContexts = null,
                operations = emptyList()
            )
        )
    }

    /**
     * Execute the navigation operations built by NavigationBuilder
     * Computes final state by applying all operations sequentially
     */
    private suspend fun executeNavigation(builder: NavigationBuilder) {
        val initialState = getCurrentNavigationState()
        
        val finalState = computeFinalNavigationState(builder.operations, initialState, builder.guidedFlowContext)
        val action = determineNavigationAction(builder.operations, finalState, initialState)
        storeAccessor.dispatch(action)
    }

    /**
     * Determine the appropriate NavigationAction to dispatch based on the operations and final state
     * Uses specific actions for single operations, BatchUpdate for multiple operations
     */
    private fun determineNavigationAction(
        operations: List<NavigationStep>,
        finalState: ComputedNavigationState,
        initialState: NavigationState
    ): NavigationAction {
        val modalContexts = if (finalState.modalContexts != initialState.activeModalContexts) {
            finalState.modalContexts
        } else null
        if (operations.size == 1) {
            val operation = operations.first()
            return when (operation.operation) {
                NavigationOperation.Back -> NavigationAction.Back(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts
                )
                NavigationOperation.PopUpTo -> NavigationAction.PopUpTo(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts
                )
                NavigationOperation.Navigate -> NavigationAction.Navigate(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts
                )
                NavigationOperation.Replace -> NavigationAction.Replace(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts
                )
                NavigationOperation.ClearBackStack -> NavigationAction.ClearBackstack(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts
                )
            }
        }
        
        return NavigationAction.BatchUpdate(
            currentEntry = finalState.currentEntry,
            backStack = finalState.backStack,
            modalContexts = modalContexts,
            operations = operations.map { it.operation }
        )
    }
    
    private data class ComputedNavigationState(
        val currentEntry: NavigationEntry,
        val backStack: List<NavigationEntry>,
        val modalContexts: Map<String, ModalContext>
    )
    
    /**
     * Compute the final navigation state by applying all steps in sequence
     * @param operations List of navigation steps to apply
     * @param initialState Starting navigation state
     * @param guidedFlowContext Optional guided flow context
     * @return Computed final navigation state
     */
    private suspend fun computeFinalNavigationState(
        operations: List<NavigationStep>, 
        initialState: NavigationState,
        guidedFlowContext: GuidedFlowContext? = null
    ): ComputedNavigationState {
        val operationResults = mutableListOf<NavigationStepResult>()
        var currentEntry = initialState.currentEntry
        var backStack = initialState.backStack
        var modalContexts = initialState.activeModalContexts
        for (step in operations) {
            val stepResult = applyNavigationStep(step, currentEntry, backStack, modalContexts, guidedFlowContext, operations)
            operationResults.add(stepResult)
            
            currentEntry = stepResult.currentEntry
            backStack = stepResult.backStack
            modalContexts = stepResult.modalContexts
        }
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
     * @param results List of navigation step results
     * @param initialState Original navigation state for fallback
     * @return Final computed navigation state
     */
    private fun resolveCollectedOperations(
        results: List<NavigationStepResult>,
        initialState: NavigationState
    ): ComputedNavigationState {
        val finalResult = results.last()
        
        return ComputedNavigationState(
            currentEntry = finalResult.currentEntry,
            backStack = finalResult.backStack,
            modalContexts = finalResult.modalContexts
        )
    }
    
    
    /**
     * Apply a single navigation step to the current state
     * @param step The navigation step to apply
     * @param currentEntry Current navigation entry
     * @param backStack Current navigation backstack
     * @param modalContexts Active modal contexts
     * @param guidedFlowContext Optional guided flow context for the step
     * @param allOperations All operations in the current batch (used for coordination)
     * @return NavigationStepResult with updated navigation state
     */
    private suspend fun applyNavigationStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null,
        allOperations: List<NavigationStep> = emptyList()
    ): NavigationStepResult {
        return when (step.operation) {
            NavigationOperation.Back -> {
                applyBackStep(currentEntry, backStack, modalContexts)
            }
            
            NavigationOperation.PopUpTo -> {
                applyPopUpToStep(step, currentEntry, backStack, modalContexts, guidedFlowContext, allOperations)
            }
            
            NavigationOperation.ClearBackStack -> {
                applyClearBackStackStep(step, currentEntry, backStack, modalContexts, guidedFlowContext)
            }
            
            NavigationOperation.Navigate -> {
                applyNavigateStep(step, currentEntry, backStack, modalContexts, guidedFlowContext, allOperations)
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
     * @param step Navigation step containing target and parameters
     * @param resolution Resolved route information
     * @param stackPosition Position in the navigation stack
     * @param guidedFlowContext Optional guided flow context
     * @return Configured navigation entry
     */
    private suspend fun createNavigationEntry(
        step: NavigationStep,
        resolution: RouteResolution,
        stackPosition: Int,
        guidedFlowContext: GuidedFlowContext? = null
    ): NavigationEntry {
        val encodedParams = step.params
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
     * Tracks the original underlying screen for proper modal restoration
     * @param modalEntry The modal navigation entry
     * @param currentEntry Current navigation entry when modal was opened
     * @param modalContexts Existing modal contexts
     * @return Modal context or null if unable to determine underlying screen
     */
    private fun createModalContext(
        modalEntry: NavigationEntry,
        currentEntry: NavigationEntry,
        modalContexts: Map<String, ModalContext>
    ): ModalContext? {
        val originalUnderlyingScreen = if (currentEntry.isModal) {
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
     * Get the effective guided flow definition - single source of truth
     * Always checks runtimeDefinition first, falls back to original definition
     */
    internal fun getEffectiveDefinition(flowState: GuidedFlowState?): GuidedFlowDefinition? {
        return when {
            flowState?.runtimeDefinition != null -> flowState.runtimeDefinition
            flowState != null -> guidedFlowDefinitions[flowState.flowRoute]
            else -> null
        }
    }

    /**
     * Get the effective guided flow definition by route, checking active flow state
     * @param flowRoute Route identifier for the guided flow
     * @return Effective definition including runtime modifications, or null if not found
     */
    internal suspend fun getEffectiveGuidedFlowDefinitionByRoute(flowRoute: String): GuidedFlowDefinition? {
        val currentState = getCurrentNavigationState()
        val flowState = currentState.activeGuidedFlowState?.takeIf { it.flowRoute == flowRoute }
        return getEffectiveDefinition(flowState) ?: guidedFlowDefinitions[flowRoute]
    }
    

    /**
     * Find the underlying screen for a modal by looking backwards in the backstack
     * @param modalEntry The modal entry to find underlying screen for
     * @param backStack Current navigation backstack
     * @return The underlying screen entry, or null if not found
     */
    private fun findUnderlyingScreenForModal(modalEntry: NavigationEntry, backStack: List<NavigationEntry>): NavigationEntry? {
        val modalIndex = backStack.indexOf(modalEntry)
        if (modalIndex <= 0) return null
        
        return backStack.subList(0, modalIndex).lastOrNull { it.isScreen }
    }

    /**
     * Execute a set of guided flow operations atomically for the current active flow
     * @param block Lambda containing guided flow operations to execute
     * @throws IllegalStateException if no active guided flow exists
     */
    suspend fun executeGuidedFlowOperations(block: suspend GuidedFlowOperationBuilder.() -> Unit) {
        val currentState = getCurrentNavigationState()
        val activeFlowRoute = currentState.activeGuidedFlowState?.flowRoute
            ?: throw IllegalStateException("No active guided flow to operate on")
        
        executeGuidedFlowOperations(activeFlowRoute, block)
    }
    
    /**
     * Execute guided flow operations for a specific flow route
     * Creates placeholder flow state if no active flow exists but modifications are made
     * @param flowRoute Route identifier for the guided flow
     * @param block Lambda containing guided flow operations to execute
     */
    suspend fun executeGuidedFlowOperations(flowRoute: String, block: suspend GuidedFlowOperationBuilder.() -> Unit) {
        val builder = GuidedFlowOperationBuilder(flowRoute, storeAccessor)
        builder.apply { block() }
        builder.validate()
        
        executeGuidedFlowOperations(builder)
    }
    
    /**
     * Execute guided flow operations atomically
     * 
     * All operations (modifications and navigation) are applied as a single transaction
     * to ensure consistency. Handles both active flows and creates placeholder flows
     * for modifications when no flow is currently active.
     * @param builder Configured operation builder containing operations to execute
     */
    private suspend fun executeGuidedFlowOperations(builder: GuidedFlowOperationBuilder) {
        val operations = builder.getOperations()
        val flowRoute = builder.getFlowRoute()

        val flowState = getCurrentNavigationState().activeGuidedFlowState?.takeIf { it.flowRoute == flowRoute }
        var currentDefinition = getEffectiveDefinition(flowState) ?: guidedFlowDefinitions[flowRoute] ?: return
        var currentFlowState = getCurrentNavigationState().activeGuidedFlowState
        var finalNavigationRoute: String? = null
        var finalNavigationParams: Params = Params.empty()
        var guidedFlowContext: GuidedFlowContext? = null
        
        for (operation in operations) {
            when (operation) {
                is GuidedFlowOperation.Modify -> {
                    currentDefinition = currentDefinition.applyModification(operation.modification)
                    if (currentFlowState?.flowRoute == flowRoute) {
                        currentFlowState = adjustFlowStateForModification(
                            currentFlowState, 
                            operation.modification, 
                            currentDefinition
                        )
                    }
                }
                is GuidedFlowOperation.NextStep -> {
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
        
        if (currentFlowState != null) {
            if (currentFlowState.isCompleted) {
                currentDefinition.onComplete?.let { onCompleteBlock ->
                    onCompleteBlock(storeAccessor)
                }
                storeAccessor.dispatch(NavigationAction.ClearActiveGuidedFlow)
            } else {
                val updatedFlowState = currentFlowState.copy(
                    runtimeDefinition = currentDefinition
                )
                storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(updatedFlowState))
            }
        } else {
            val hasModifications = operations.any { it is GuidedFlowOperation.Modify }
            if (hasModifications) {
                val placeholderFlowState = GuidedFlowState(
                    flowRoute = flowRoute,
                    startedAt = Clock.System.now(),
                    runtimeDefinition = currentDefinition
                )
                storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(placeholderFlowState))
            }
        }
        
        if (finalNavigationRoute != null) {
            navigate(finalNavigationRoute, finalNavigationParams, false) {
                guidedFlowContext?.let { setGuidedFlowContext(it) }
            }
        }
    }

    /**
     * Adjust flow state for a single modification
     * Recalculates current step index when steps are added/removed
     * @param flowState Current guided flow state
     * @param modification The modification being applied
     * @param updatedDefinition The definition after applying the modification
     * @return Adjusted flow state with correct step index and properties
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
                    Int.MAX_VALUE
                } else {
                    modification.insertIndex
                }
                if (insertIndex <= flowState.currentStepIndex) {
                    flowState.currentStepIndex + modification.steps.size
                } else {
                    flowState.currentStepIndex
                }
            }
            else -> flowState.currentStepIndex
        }
        
        return computeGuidedFlowProperties(
            flowState.copy(currentStepIndex = adjustedIndex),
            updatedDefinition
        )
    }

    /**
     * Data class for navigation computation results
     * @property flowState Updated guided flow state
     * @property shouldNavigate Whether navigation should occur
     * @property route Target route for navigation (if shouldNavigate is true)
     * @property params Parameters for navigation (if shouldNavigate is true)
     * @property guidedFlowContext Guided flow context for navigation (if shouldNavigate is true)
     */
    private data class NavigationResult(
        val flowState: GuidedFlowState,
        val shouldNavigate: Boolean = false,
        val route: String = "",
        val params: Params = Params.empty(),
        val guidedFlowContext: GuidedFlowContext? = null
    )

    /**
     * Compute next step navigation
     * @param flowState Current guided flow state
     * @param definition Effective guided flow definition
     * @param params Additional parameters for the step
     * @return NavigationResult indicating next step or flow completion
     */
    private suspend fun computeNextStep(
        flowState: GuidedFlowState,
        definition: GuidedFlowDefinition,
        params: Params = Params.empty()
    ): NavigationResult {
        return when {
            flowState.isOnFinalStep -> {
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
     * @param flowState Current guided flow state
     * @param definition Effective guided flow definition
     * @return NavigationResult for previous step, or current state if at first step
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
     * Get the current navigation state from the store
     * @return Current navigation state
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
        guidedFlowContext: GuidedFlowContext? = null,
        allOperations: List<NavigationStep> = emptyList()
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
            isNavigatingToModal -> backStack.size + 1
            baseBackStack.isEmpty() -> 1
            else -> baseBackStack.size + 1
        }
        
        val newEntry = createNavigationEntry(step, resolution, stackPosition, guidedFlowContext)
        
        val newBackStack = if (isNavigatingToModal) {
            backStack + newEntry
        } else if (baseBackStack.isEmpty()) {
            listOf(newEntry)
        } else {
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
        guidedFlowContext: GuidedFlowContext? = null,
        allOperations: List<NavigationStep> = emptyList()
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
            val hasNavigationOperations = allOperations.any { it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace }
            
            if (hasNavigationOperations) {
                // PopUpTo combined with navigation - return modified backstack without changing current entry
                // The subsequent navigation operation will use this modified backstack
                if (newBackStackAfterPop.isEmpty()) {
                    if (currentEntry.navigatable.route == step.popUpToTarget?.resolve(precomputedData) && step.popUpToInclusive) {
                        throw IllegalStateException("Cannot pop up to route that would result in empty back stack")
                    } else {
                        return NavigationStepResult(
                            currentEntry = currentEntry,
                            backStack = listOf(currentEntry),
                            modalContexts = modalContexts
                        )
                    }
                }
                
                NavigationStepResult(
                    currentEntry = currentEntry,
                    backStack = newBackStackAfterPop,
                    modalContexts = modalContexts
                )
            } else {
                // Standalone popUpTo - navigate to the target screen
                if (newBackStackAfterPop.isEmpty()) {
                    if (step.popUpToInclusive) {
                        throw IllegalStateException("Cannot pop up to route with inclusive=true that would result in empty back stack")
                    } else {
                        return NavigationStepResult(
                            currentEntry = currentEntry,
                            backStack = listOf(currentEntry),
                            modalContexts = modalContexts
                        )
                    }
                }
                
                val targetEntry = newBackStackAfterPop.last()
                
                NavigationStepResult(
                    currentEntry = targetEntry,
                    backStack = newBackStackAfterPop,
                    modalContexts = modalContexts
                )
            }
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
            else -> {
            }
        }
    }

    private suspend fun handleStartGuidedFlow(action: NavigationAction.StartGuidedFlow) {
        val currentState = getCurrentNavigationState()
        val existingFlowState = currentState.activeGuidedFlowState?.takeIf { it.flowRoute == action.guidedFlow.route }
        val effectiveDefinition = getEffectiveDefinition(existingFlowState) ?: guidedFlowDefinitions[action.guidedFlow.route]
        
        if (effectiveDefinition != null && effectiveDefinition.steps.isNotEmpty()) {
            val originalDefinition = guidedFlowDefinitions[action.guidedFlow.route]
            val runtimeDefinition = if (effectiveDefinition != originalDefinition) effectiveDefinition else null
            
            val flowState = computeGuidedFlowProperties(
                GuidedFlowState(
                    flowRoute = action.guidedFlow.route,
                    startedAt = Clock.System.now(),
                    runtimeDefinition = runtimeDefinition
                ),
                effectiveDefinition
            )
            storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(flowState))
            
            val firstStep = effectiveDefinition.steps.first()
            val stepRoute = firstStep.getRoute(precomputedData)
            val stepParams = action.params + firstStep.getParams()
            
            val builder = NavigationBuilder(storeAccessor)
            builder.params(stepParams)
            builder.navigateTo(stepRoute, false)
            builder.setGuidedFlowContext(GuidedFlowContext(
                flowRoute = action.guidedFlow.route,
                stepIndex = 0,
                totalSteps = effectiveDefinition.steps.size
            ))
            
            executeNavigation(builder)
        }
    }

    private suspend fun handleNextStep(action: NavigationAction.NextStep) {
        val currentState = getCurrentNavigationState()
        val flowState = currentState.activeGuidedFlowState
        val definition = getEffectiveDefinition(flowState)
        
        if (flowState != null && definition != null) {
            when {
                flowState.isOnFinalStep -> {
                    val completedFlowState = flowState.copy(
                        completedAt = Clock.System.now(),
                        isCompleted = true,
                        duration = Clock.System.now() - flowState.startedAt
                    )
                    storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(completedFlowState))
                    
                    definition.onComplete?.let { onCompleteBlock ->
                        onCompleteBlock(storeAccessor)
                    }
                    
                    storeAccessor.dispatch(NavigationAction.ClearActiveGuidedFlow)
                }
                else -> {
                    val nextStepIndex = flowState.currentStepIndex + 1
                    val nextStep = definition.steps[nextStepIndex]
                    
                    val updatedFlow = computeGuidedFlowProperties(
                        flowState.copy(currentStepIndex = nextStepIndex),
                        definition
                    )
                    storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(updatedFlow))
                    
                    val stepRoute = nextStep.getRoute(precomputedData)
                    val stepParams = action.params + nextStep.getParams()
                    val builder = NavigationBuilder(storeAccessor)
                    builder.params(stepParams)
                    builder.navigateTo(stepRoute, false)
                    builder.setGuidedFlowContext(GuidedFlowContext(
                        flowRoute = flowState.flowRoute,
                        stepIndex = nextStepIndex,
                        totalSteps = definition.steps.size
                    ))
                    
                    executeNavigation(builder)
                }
            }
        }
    }

    private suspend fun handlePreviousStep() {
        val currentState = getCurrentNavigationState()
        val flowState = currentState.activeGuidedFlowState
        val definition = getEffectiveDefinition(flowState)
        
        if (flowState != null && definition != null && flowState.currentStepIndex > 0) {
            val previousStepIndex = flowState.currentStepIndex - 1
            val previousStep = definition.steps[previousStepIndex]
            
            val updatedFlow = computeGuidedFlowProperties(
                flowState.copy(currentStepIndex = previousStepIndex),
                definition
            )
            storeAccessor.dispatch(NavigationAction.UpdateActiveGuidedFlow(updatedFlow))
            
            val stepRoute = previousStep.getRoute(precomputedData)
            val stepParams = previousStep.getParams()
            val builder = NavigationBuilder(storeAccessor)
            builder.params(stepParams)
            builder.navigateTo(stepRoute, false)
            builder.setGuidedFlowContext(GuidedFlowContext(
                flowRoute = flowState.flowRoute,
                stepIndex = previousStepIndex,
                totalSteps = definition.steps.size
            ))
            
            executeNavigation(builder)
        }
    }

}