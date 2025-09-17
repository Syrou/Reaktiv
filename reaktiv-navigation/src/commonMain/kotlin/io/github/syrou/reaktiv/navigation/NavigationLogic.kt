package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperation
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.dsl.NavigationStep
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.ClearModificationBehavior
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowContext
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.applyModification
import io.github.syrou.reaktiv.navigation.model.getParams
import io.github.syrou.reaktiv.navigation.model.getRoute
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.computeGuidedFlowProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class NavigationLogic(
    val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val guidedFlowDefinitions: Map<String, GuidedFlowDefinition> = emptyMap(),
    private val parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) : ModuleLogic<NavigationAction>() {

    // Mutex to prevent concurrent guided flow operations
    private val guidedFlowMutex = Mutex()


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

    /**
     * Execute the navigation operations built by NavigationBuilder
     * Computes final state by applying all operations sequentially
     */
    private suspend fun executeNavigation(builder: NavigationBuilder) {
        val initialState = getCurrentNavigationState()

        val finalState = computeFinalNavigationState(builder.operations, initialState, builder.guidedFlowContext)
        
        // Check if this navigation will cause a screen transition (has animation)
        val willAnimate = willNavigationAnimate(initialState, finalState)
        val transitionState = if (willAnimate) NavigationTransitionState.ANIMATING else NavigationTransitionState.IDLE
        
        val action = determineNavigationAction(builder.operations, finalState, initialState, builder, transitionState)
        
        // Single atomic dispatch - navigation state + transition state change together
        storeAccessor.dispatch(action)
        
        // Schedule reset only AFTER successful dispatch
        if (willAnimate) {
            scheduleTransitionStateReset(finalState.currentEntry)
        }
    }

    /**
     * Check if navigation will cause a screen transition that requires animation
     */
    private fun willNavigationAnimate(
        initialState: NavigationState, 
        finalState: ComputedNavigationState
    ): Boolean {
        // Check if current entry is changing to a different screen
        val isRouteChanging = initialState.currentEntry.navigatable.route != finalState.currentEntry.navigatable.route
        
        if (!isRouteChanging) {
            // No route change = no animation needed
            return false
        }
        
        // Check if either enter or exit transition has actual duration
        val enterDuration = finalState.currentEntry.navigatable.enterTransition.durationMillis
        val exitDuration = initialState.currentEntry.navigatable.exitTransition.durationMillis
        
        // Only animate if at least one transition has duration > 0
        return maxOf(enterDuration, exitDuration) > 0
    }

    /**
     * Schedule transition state reset after animation completes
     */
    private suspend fun scheduleTransitionStateReset(targetEntry: NavigationEntry) {
        val currentState = getCurrentNavigationState()
        val previousEntry = currentState.currentEntry
        
        // Calculate animation duration considering both enter and exit transitions
        val enterDuration = targetEntry.navigatable.enterTransition.durationMillis
        val exitDuration = previousEntry.navigatable.exitTransition.durationMillis
        
        // Use the longer of the two durations to ensure both animations complete
        val totalAnimationDuration = maxOf(enterDuration, exitDuration)
        
        storeAccessor.launch {
            delay(totalAnimationDuration.toLong())
            
            // Use dedicated action to reset transition state back to IDLE
            storeAccessor.dispatch(NavigationAction.UpdateTransitionState(NavigationTransitionState.IDLE))
        }
    }

    /**
     * Determine the appropriate NavigationAction to dispatch based on the operations and final state
     * Uses specific actions for single operations, BatchUpdate for multiple operations
     */
    private fun determineNavigationAction(
        operations: List<NavigationStep>,
        finalState: ComputedNavigationState,
        initialState: NavigationState,
        builder: NavigationBuilder,
        transitionState: NavigationTransitionState = NavigationTransitionState.IDLE
    ): NavigationAction {
        val modalContexts = if (finalState.modalContexts != initialState.activeModalContexts) {
            finalState.modalContexts
        } else null

        // Determine active guided flow state
        val activeGuidedFlowState = when {
            builder.shouldClearActiveGuidedFlowState -> null
            builder.activeGuidedFlowState != null -> builder.activeGuidedFlowState
            else -> initialState.activeGuidedFlowState
        }

        // If there's guided flow state changes, always use BatchUpdate to preserve it
        val hasGuidedFlowChanges = builder.activeGuidedFlowState != null ||
                builder.shouldClearActiveGuidedFlowState ||
                activeGuidedFlowState != initialState.activeGuidedFlowState

        if (operations.size == 1 && !hasGuidedFlowChanges) {
            val operation = operations.first()
            return when (operation.operation) {
                NavigationOperation.Back -> NavigationAction.Back(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts,
                    transitionState = transitionState
                )

                NavigationOperation.PopUpTo -> NavigationAction.PopUpTo(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts,
                    transitionState = transitionState
                )

                NavigationOperation.Navigate -> NavigationAction.Navigate(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts,
                    transitionState = transitionState
                )

                NavigationOperation.Replace -> NavigationAction.Replace(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts,
                    transitionState = transitionState
                )

                NavigationOperation.ClearBackStack -> NavigationAction.ClearBackstack(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = modalContexts,
                    transitionState = transitionState
                )
            }
        }

        // Use BatchUpdate for multiple operations or when guided flow state changes
        return NavigationAction.BatchUpdate(
            currentEntry = finalState.currentEntry,
            backStack = finalState.backStack,
            modalContexts = modalContexts,
            operations = operations.map { it.operation },
            activeGuidedFlowState = activeGuidedFlowState,
            transitionState = transitionState
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
            val stepResult =
                applyNavigationStep(step, currentEntry, backStack, modalContexts, guidedFlowContext, operations)
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
     * Checks modifications first, then original definition
     */
    internal suspend fun getEffectiveDefinition(flowRoute: String): GuidedFlowDefinition? {
        val currentState = getCurrentNavigationState()

        // First check for stored modifications
        currentState.guidedFlowModifications[flowRoute]?.let { return it }

        // Fall back to original definition
        return guidedFlowDefinitions[flowRoute]
    }

    /**
     * Legacy method for backward compatibility
     */
    internal fun getEffectiveDefinition(flowState: GuidedFlowState?): GuidedFlowDefinition? {
        return flowState?.let { guidedFlowDefinitions[it.flowRoute] }
    }

    /**
     * Get the effective guided flow definition by route, checking active flow state
     * @param flowRoute Route identifier for the guided flow
     * @return Effective definition including runtime modifications, or null if not found
     */
    internal suspend fun getEffectiveGuidedFlowDefinitionByRoute(flowRoute: String): GuidedFlowDefinition? {
        return getEffectiveDefinition(flowRoute)
    }


    /**
     * Find the underlying screen for a modal by looking backwards in the backstack
     * @param modalEntry The modal entry to find underlying screen for
     * @param backStack Current navigation backstack
     * @return The underlying screen entry, or null if not found
     */
    private fun findUnderlyingScreenForModal(
        modalEntry: NavigationEntry,
        backStack: List<NavigationEntry>
    ): NavigationEntry? {
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
        guidedFlowMutex.withLock {
            val currentState = getCurrentNavigationState()
            val activeFlowRoute = currentState.activeGuidedFlowState?.flowRoute
                ?: throw IllegalStateException("No active guided flow to operate on")

            executeGuidedFlowOperations(activeFlowRoute, block)
        }
    }

    /**
     * Execute guided flow operations for a specific flow route
     * Creates placeholder flow state if no active flow exists but modifications are made
     * @param flowRoute Route identifier for the guided flow
     * @param block Lambda containing guided flow operations to execute
     */
    suspend fun executeGuidedFlowOperations(flowRoute: String, block: suspend GuidedFlowOperationBuilder.() -> Unit) {
        guidedFlowMutex.withLock {
            val builder = GuidedFlowOperationBuilder(flowRoute, storeAccessor)
            builder.apply { block() }
            builder.validate()

            executeGuidedFlowOperations(builder)
        }
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

        var currentDefinition = getEffectiveDefinition(flowRoute) ?: return
        val currentFlowState = getCurrentNavigationState().activeGuidedFlowState?.takeIf { it.flowRoute == flowRoute }
        var finalNavigationRoute: String? = null
        var finalNavigationParams: Params = Params.empty()
        var guidedFlowContext: GuidedFlowContext? = null

        var hasModifications = false
        var updatedFlowState = currentFlowState

        for (operation in operations) {
            when (operation) {
                is GuidedFlowOperation.Modify -> {
                    currentDefinition = currentDefinition.applyModification(operation.modification)
                    hasModifications = true

                    // If there's an active flow state for this route, update it
                    if (updatedFlowState != null) {
                        updatedFlowState = adjustFlowStateForModification(
                            updatedFlowState,
                            operation.modification,
                            currentDefinition
                        )
                    }
                }

                is GuidedFlowOperation.NextStep -> {
                    if (updatedFlowState != null) {
                        val result = computeNextStep(updatedFlowState, currentDefinition, operation.params)
                        println("HERPADERPA - computeNextStep: $result")
                        updatedFlowState = result.flowState
                        if (result.shouldNavigate) {
                            finalNavigationRoute = result.route
                            finalNavigationParams = result.params
                            guidedFlowContext = result.guidedFlowContext
                        }
                    }
                }
            }
        }

        // Store modifications for this flow route if any were made
        if (hasModifications) {
            storeAccessor.dispatch(
                NavigationAction.UpdateGuidedFlowModifications(
                    flowRoute = flowRoute,
                    modifiedDefinition = currentDefinition
                )
            )
        }

        // Update active flow state if there's a currently executing flow
        if (updatedFlowState != null) {
            if (updatedFlowState.isCompleted) {
                currentDefinition.onComplete?.let { onCompleteBlock ->
                    onCompleteBlock(storeAccessor)
                }
                // Clear the active flow state via BatchUpdate
                storeAccessor.dispatch(NavigationAction.BatchUpdate(activeGuidedFlowState = null))

                // Clear modifications based on the flow's configuration
                when (currentDefinition.clearModificationsOnComplete) {
                    ClearModificationBehavior.CLEAR_ALL -> {
                        storeAccessor.dispatch(NavigationAction.ClearAllGuidedFlowModifications)
                    }

                    ClearModificationBehavior.CLEAR_SPECIFIC -> {
                        storeAccessor.dispatch(NavigationAction.UpdateGuidedFlowModifications(flowRoute, null))
                    }

                    ClearModificationBehavior.CLEAR_NONE -> {
                        // Don't clear any modifications
                    }
                }
            } else if (finalNavigationRoute != null) {
                // Navigate and update guided flow state in single BatchUpdate
                val builder = NavigationBuilder(storeAccessor, parameterEncoder)
                builder.params(finalNavigationParams)
                builder.navigateTo(finalNavigationRoute, false)
                guidedFlowContext?.let { builder.setGuidedFlowContext(it) }
                builder.setActiveGuidedFlowState(updatedFlowState)
                builder.validate()
                
                executeNavigation(builder)
            } else if (updatedFlowState != null) {
                // Only update guided flow state (no navigation)
                storeAccessor.dispatch(NavigationAction.BatchUpdate(activeGuidedFlowState = updatedFlowState))
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
                        completedAt = Clock.System.now(),
                        isCompleted = true,
                        duration = Clock.System.now() - flowState.startedAt
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
     * Get the current navigation state from the store
     * @return Current navigation state
     */
    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

    // Step application methods - pure functions that compute new state without side effects

    private suspend fun applyBackStep(
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>
    ): NavigationStepResult {
        // Check if current entry is part of an active guided flow
        val guidedFlowContext = currentEntry.guidedFlowContext
        val activeFlowState = getCurrentNavigationState().activeGuidedFlowState

        // Handle guided flow exit from first step
        if (guidedFlowContext != null &&
            activeFlowState?.flowRoute == guidedFlowContext.flowRoute &&
            activeFlowState.currentStepIndex <= 0
        ) {

            ReaktivDebug.nav("🔄 Exiting guided flow from first step")
            storeAccessor.dispatch(NavigationAction.BatchUpdate(activeGuidedFlowState = null))
        }

        // Always use regular back navigation
        val result = applyRegularBackStep(currentEntry, backStack, modalContexts)

        // Update guided flow step index if we're in a guided flow
        if (guidedFlowContext != null && activeFlowState != null &&
            activeFlowState.flowRoute == guidedFlowContext.flowRoute && !activeFlowState.isCompleted
        ) {

            val newCurrentEntry = result.currentEntry
            val newStepIndex = newCurrentEntry.guidedFlowContext?.stepIndex

            if (newStepIndex != null && newStepIndex != activeFlowState.currentStepIndex) {
                val effectiveDefinition = getEffectiveDefinition(guidedFlowContext.flowRoute)
                if (effectiveDefinition != null) {
                    val updatedFlow = computeGuidedFlowProperties(
                        activeFlowState.copy(currentStepIndex = newStepIndex),
                        effectiveDefinition
                    )
                    storeAccessor.dispatch(NavigationAction.BatchUpdate(activeGuidedFlowState = updatedFlow))
                    ReaktivDebug.nav("📍 Updated guided flow step index to: $newStepIndex")
                }
            }
        }

        return result
    }


    /**
     * Apply regular back navigation (non-guided flow)
     */
    private fun applyRegularBackStep(
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
            val hasNavigationOperations =
                allOperations.any { it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace }

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


    /**
     * Start a guided flow directly via logic layer
     * This method prevents concurrent flows and updates state synchronously
     */
    suspend fun startGuidedFlow(flowRoute: String, params: Params = Params.empty()) {
        guidedFlowMutex.withLock {
            try {
                val currentState = getCurrentNavigationState()

                // Prevent starting a new guided flow if one is already active
                if (currentState.activeGuidedFlowState != null && !currentState.activeGuidedFlowState.isCompleted) {
                    ReaktivDebug.nav("⛔ Cannot start guided flow '$flowRoute' - another guided flow '${currentState.activeGuidedFlowState.flowRoute}' is already active")
                    return
                }

                val effectiveDefinition = getEffectiveDefinition(flowRoute)

                if (effectiveDefinition != null && effectiveDefinition.steps.isNotEmpty()) {
                    val flowState = computeGuidedFlowProperties(
                        GuidedFlowState(
                            flowRoute = flowRoute,
                            startedAt = Clock.System.now()
                        ),
                        effectiveDefinition
                    )

                    val firstStep = effectiveDefinition.steps.first()
                    val stepRoute = firstStep.getRoute(precomputedData)
                    val stepParams = params + firstStep.getParams()

                    val builder = NavigationBuilder(storeAccessor, parameterEncoder)
                    builder.params(stepParams)
                    builder.navigateTo(stepRoute, false)
                    builder.setGuidedFlowContext(
                        GuidedFlowContext(
                            flowRoute = flowRoute,
                            stepIndex = 0,
                            totalSteps = effectiveDefinition.steps.size
                        )
                    )
                    // Set the guided flow state to be included in the BatchUpdate
                    builder.setActiveGuidedFlowState(flowState)
                    builder.validate()

                    executeNavigation(builder)
                }
            } catch (e: Exception) {
                ReaktivDebug.nav("❌ Error starting guided flow '$flowRoute': ${e.message}")
                // Log error but don't propagate to avoid breaking calling code
            }
        }
    }

    /**
     * Navigate to the next step in the currently active guided flow
     * This method updates state directly and handles flow completion
     */
    suspend fun nextGuidedFlowStep(params: Params = Params.empty()) {
        guidedFlowMutex.withLock {
            try {
                val currentState = getCurrentNavigationState()
                val flowState = currentState.activeGuidedFlowState
                val definition = if (flowState != null) getEffectiveDefinition(flowState.flowRoute) else null

                if (flowState != null && definition != null) {
                    when {
                        flowState.isOnFinalStep -> {
                            val completedFlowState = flowState.copy(
                                completedAt = Clock.System.now(),
                                isCompleted = true,
                                duration = Clock.System.now() - flowState.startedAt
                            )
                            println("HERPADERPA - completedFlowState: $completedFlowState")

                            // Execute onComplete callback before clearing state
                            definition.onComplete?.let { onCompleteBlock ->
                                onCompleteBlock(storeAccessor)
                            }

                            // Atomically complete the flow and clear state/modifications
                            storeAccessor.dispatch(
                                NavigationAction.CompleteGuidedFlow(
                                    completedFlowState = completedFlowState,
                                    clearBehavior = definition.clearModificationsOnComplete,
                                    flowRoute = flowState.flowRoute
                                )
                            )
                        }

                        else -> {
                            val nextStepIndex = flowState.currentStepIndex + 1
                            val nextStep = definition.steps[nextStepIndex]

                            val updatedFlow = computeGuidedFlowProperties(
                                flowState.copy(currentStepIndex = nextStepIndex),
                                definition
                            )

                            // Include guided flow state in the navigation update

                            val stepRoute = nextStep.getRoute(precomputedData)
                            val stepParams = params + nextStep.getParams()
                            val builder = NavigationBuilder(storeAccessor, parameterEncoder)
                            builder.params(stepParams)
                            builder.navigateTo(stepRoute, false)
                            builder.setGuidedFlowContext(
                                GuidedFlowContext(
                                    flowRoute = flowState.flowRoute,
                                    stepIndex = nextStepIndex,
                                    totalSteps = definition.steps.size
                                )
                            )
                            // Set the updated guided flow state to be included in the BatchUpdate
                            builder.setActiveGuidedFlowState(updatedFlow)
                            builder.validate()

                            executeNavigation(builder)
                        }
                    }
                }
            } catch (e: Exception) {
                ReaktivDebug.nav("❌ Error in guided flow step: ${e.message}")
                // Log error but don't propagate to avoid breaking calling code
            }
        }
    }

}