package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperation
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
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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


    private val guidedFlowMutex = Mutex()
    private var transitionResetJob: Job? = null
    
    /**
     * Coroutine scope for transition state reset timers.
     * Uses SupervisorJob + store context minus job to:
     * - Survive store resets (won't be cancelled by store.reset())
     * - Work with test time control (inherits store's dispatcher)
     * - Prevent stuck ANIMATING states after animation completion
     */
    private val timerScope = CoroutineScope(
        SupervisorJob() + storeAccessor.coroutineContext.minusKey(Job)
    )


    /**
     * Execute a single navigation operation synchronously
     * This ensures that when the method returns, the navigation state has been updated
     */
    suspend fun navigate(block: suspend NavigationBuilder.() -> Unit) {
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.apply { block() }
        builder.validate()

        executeNavigation(builder, "navigate")
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

    private suspend fun executeNavigation(builder: NavigationBuilder, source: String) {
        val initialState = getCurrentNavigationState()
        val unifiedOps = collectUnifiedOperations(builder, initialState)

        validateUnifiedOperations(unifiedOps)

        val finalState = computeFinalNavigationState(unifiedOps, initialState)
        val willAnimate = willNavigationAnimate(initialState, finalState)

        val action = determineNavigationAction(unifiedOps, finalState, initialState, willAnimate)

        // Capture backstack before dispatch for lifecycle callbacks
        val previousBackStack = initialState.backStack

        storeAccessor.dispatch(action)

        // Call lifecycle callbacks for added/removed entries
        invokeLifecycleCallbacks(previousBackStack, finalState.backStack)

        if (willAnimate) {
            scheduleTransitionStateReset(finalState.currentEntry, initialState.currentEntry)
        }
    }

    /**
     * Invokes lifecycle callbacks for entries that were added or removed from the backstack.
     */
    private suspend fun invokeLifecycleCallbacks(
        previousBackStack: List<NavigationEntry>,
        newBackStack: List<NavigationEntry>
    ) {
        val previousKeys = previousBackStack.map { it.stableKey }.toSet()
        val newKeys = newBackStack.map { it.stableKey }.toSet()

        // Find added entries (in new but not in previous)
        val addedEntries = newBackStack.filter { it.stableKey !in previousKeys }

        // Find removed entries (in previous but not in new)
        val removedEntries = previousBackStack.filter { it.stableKey !in newKeys }

        // Call onAddedToBackstack for new entries
        addedEntries.forEach { entry ->
            try {
                entry.navigatable.onAddedToBackstack(storeAccessor)
            } catch (e: Exception) {
                // Log but don't prevent navigation
                println("Warning: onAddedToBackstack failed for ${entry.navigatable.route}: ${e.message}")
            }
        }

        // Call onRemovedFromBackstack for removed entries
        removedEntries.forEach { entry ->
            try {
                entry.navigatable.onRemovedFromBackstack(storeAccessor)
            } catch (e: Exception) {
                // Log but don't prevent navigation
                println("Warning: onRemovedFromBackstack failed for ${entry.navigatable.route}: ${e.message}")
            }
        }
    }

    /**
     * Determines the appropriate NavigationAction based on the operation type and complexity
     */
    private fun determineNavigationAction(
        operations: UnifiedOperations,
        finalState: FinalNavigationState,
        initialState: NavigationState,
        willAnimate: Boolean
    ): NavigationAction {
        val transitionState = if (willAnimate) NavigationTransitionState.ANIMATING else NavigationTransitionState.IDLE

        return when {
            isSimpleBackOperation(operations, finalState, initialState) -> {
                NavigationAction.Back(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = finalState.modalContexts,
                    transitionState = transitionState
                )
            }

            isSimpleNavigateOperation(operations, finalState, initialState) -> {
                NavigationAction.Navigate(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = finalState.modalContexts,
                    transitionState = transitionState
                )
            }

            isSimpleReplaceOperation(operations, finalState, initialState) -> {
                NavigationAction.Replace(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = finalState.modalContexts,
                    transitionState = transitionState
                )
            }

            else -> {
                // Complex operations use BatchUpdate
                NavigationAction.BatchUpdate(
                    currentEntry = finalState.currentEntry,
                    backStack = finalState.backStack,
                    modalContexts = finalState.modalContexts,
                    operations = operations.navigationSteps.map { it.operation },
                    activeGuidedFlowState = finalState.activeGuidedFlowState,
                    guidedFlowModifications = finalState.guidedFlowModifications,
                    transitionState = transitionState
                )
            }
        }
    }

    /**
     * Checks if this is a simple back operation that can use NavigationAction.Back
     */
    private fun isSimpleBackOperation(
        operations: UnifiedOperations,
        finalState: FinalNavigationState,
        initialState: NavigationState
    ): Boolean {
        return operations.navigationSteps.size == 1 &&
               operations.navigationSteps.first().operation == NavigationOperation.Back &&
               !hasComplexStateChanges(operations, finalState, initialState)
    }

    /**
     * Checks if this is a simple navigate operation that can use NavigationAction.Navigate
     */
    private fun isSimpleNavigateOperation(
        operations: UnifiedOperations,
        finalState: FinalNavigationState,
        initialState: NavigationState
    ): Boolean {
        return operations.navigationSteps.size == 1 &&
               operations.navigationSteps.first().operation == NavigationOperation.Navigate &&
               !hasComplexStateChanges(operations, finalState, initialState)
    }

    /**
     * Checks if this is a simple replace operation that can use NavigationAction.Replace
     */
    private fun isSimpleReplaceOperation(
        operations: UnifiedOperations,
        finalState: FinalNavigationState,
        initialState: NavigationState
    ): Boolean {
        return operations.navigationSteps.size == 1 &&
               operations.navigationSteps.first().operation == NavigationOperation.Replace &&
               !hasComplexStateChanges(operations, finalState, initialState)
    }

    /**
     * Determines if the operation involves complex state changes that require BatchUpdate
     */
    private fun hasComplexStateChanges(
        operations: UnifiedOperations,
        finalState: FinalNavigationState,
        initialState: NavigationState
    ): Boolean {
        return operations.guidedFlowModifications.isNotEmpty() ||
               finalState.activeGuidedFlowState != initialState.activeGuidedFlowState ||
               operations.hasGuidedFlowNavigation
    }

    /**
     * Unified representation of all operations (navigation + guided flow)
     */
    private data class UnifiedOperations(
        val navigationSteps: List<NavigationStep>,
        val activeGuidedFlowState: GuidedFlowState?,
        val guidedFlowModifications: Map<String, GuidedFlowDefinition>,
        val guidedFlowContext: GuidedFlowContext?,
        val hasExplicitNavigation: Boolean,
        val hasGuidedFlowNavigation: Boolean
    )

    /**
     * Final computed navigation state
     */
    private data class FinalNavigationState(
        val currentEntry: NavigationEntry,
        val backStack: List<NavigationEntry>,
        val modalContexts: Map<String, ModalContext>,
        val activeGuidedFlowState: GuidedFlowState?,
        val guidedFlowModifications: Map<String, GuidedFlowDefinition>
    )

    /**
     * Collect and unify all operations (navigation + guided flow) into a single format
     */
    private suspend fun collectUnifiedOperations(
        builder: NavigationBuilder,
        initialState: NavigationState
    ): UnifiedOperations {
        val hasExplicitNavigation = builder.operations.isNotEmpty()
        val allNavigationSteps = mutableListOf<NavigationStep>()
        val allGuidedFlowModifications = mutableMapOf<String, GuidedFlowDefinition>()

        // Start with any explicit navigation operations
        allNavigationSteps.addAll(builder.operations)

        // Add existing guided flow modifications from state
        allGuidedFlowModifications.putAll(initialState.guidedFlowModifications)

        var finalActiveGuidedFlowState = initialState.activeGuidedFlowState
        var finalGuidedFlowContext = builder.guidedFlowContext
        var hasGuidedFlowNavigation = false

        for ((flowRoute, operationBuilder) in builder.getGuidedFlowOperations()) {
            val operations = operationBuilder.getOperations()

            if (operations.any { it is GuidedFlowOperation.NextStep }) {
                hasGuidedFlowNavigation = true
            }

            var currentDefinition = getEffectiveGuidedFlowDefinitionByRoute(flowRoute)
            var hasModifications = false

            for (operation in operations) {
                if (operation is GuidedFlowOperation.Modify) {
                    if (currentDefinition != null) {
                        currentDefinition = currentDefinition.applyModification(operation.modification)
                        hasModifications = true

                        if (finalActiveGuidedFlowState?.flowRoute == flowRoute) {
                            finalActiveGuidedFlowState = adjustFlowStateForModification(
                                finalActiveGuidedFlowState,
                                operation.modification,
                                currentDefinition
                            )
                        }
                    }
                }
            }

            if (hasModifications && currentDefinition != null) {
                allGuidedFlowModifications[flowRoute] = currentDefinition
            }

            for (operation in operations) {
                if (operation is GuidedFlowOperation.NextStep) {
                    if (finalActiveGuidedFlowState?.flowRoute == flowRoute && currentDefinition != null) {
                        val result = computeNextStep(finalActiveGuidedFlowState, currentDefinition, operation.params)
                        finalActiveGuidedFlowState = result.flowState
                        finalGuidedFlowContext = result.guidedFlowContext

                        if (result.shouldNavigate) {
                            allNavigationSteps.add(
                                NavigationStep(
                                    operation = NavigationOperation.Navigate,
                                    target = NavigationTarget.Path(result.route),
                                    params = result.params
                                )
                            )
                        } else if (result.flowState.isCompleted) {
                            currentDefinition.onComplete?.let { onCompleteHandler ->
                                val completionBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                                onCompleteHandler(completionBuilder, storeAccessor)

                                allNavigationSteps.addAll(completionBuilder.operations)

                                for ((completionFlowRoute, completionFlowBuilder) in completionBuilder.getGuidedFlowOperations()) {
                                    val completionOperations = completionFlowBuilder.getOperations()
                                    var completionDefinition = allGuidedFlowModifications[completionFlowRoute]
                                        ?: getEffectiveGuidedFlowDefinitionByRoute(completionFlowRoute)

                                    for (completionOperation in completionOperations) {
                                        when (completionOperation) {
                                            is GuidedFlowOperation.Modify -> {
                                                if (completionDefinition != null) {
                                                    completionDefinition =
                                                        completionDefinition.applyModification(completionOperation.modification)
                                                }
                                            }

                                            is GuidedFlowOperation.NextStep -> {
                                                if (completionDefinition != null && completionFlowRoute == flowRoute) {
                                                    finalActiveGuidedFlowState?.let { activeFlow ->
                                                        val completionResult = computeNextStep(
                                                            activeFlow,
                                                            completionDefinition,
                                                            completionOperation.params
                                                        )
                                                        if (completionResult.shouldNavigate) {
                                                            allNavigationSteps.add(
                                                                NavigationStep(
                                                                    operation = NavigationOperation.Navigate,
                                                                    target = NavigationTarget.Path(completionResult.route),
                                                                    params = completionResult.params
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    completionDefinition?.let {
                                        allGuidedFlowModifications[completionFlowRoute] = it
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Handle builder's guided flow state overrides
        if (builder.shouldClearActiveGuidedFlowState) {
            finalActiveGuidedFlowState = null
        } else if (builder.activeGuidedFlowState != null) {
            finalActiveGuidedFlowState = builder.activeGuidedFlowState
        }

        return UnifiedOperations(
            navigationSteps = allNavigationSteps,
            activeGuidedFlowState = finalActiveGuidedFlowState,
            guidedFlowModifications = allGuidedFlowModifications,
            guidedFlowContext = finalGuidedFlowContext,
            hasExplicitNavigation = hasExplicitNavigation,
            hasGuidedFlowNavigation = hasGuidedFlowNavigation
        )
    }

    /**
     * Validate unified operations for conflicts
     */
    private fun validateUnifiedOperations(operations: UnifiedOperations) {
        // Check if we have both explicit navigation and guided flow navigation
        if (operations.hasExplicitNavigation && operations.hasGuidedFlowNavigation) {
            throw IllegalStateException(
                "Cannot combine guided flow nextStep() operations with explicit navigation operations (navigateTo, navigateBack, etc.) " +
                        "in the same atomic block. Use either guided flow navigation OR explicit navigation, not both."
            )
        }
    }

    private suspend fun computeFinalNavigationState(
        operations: UnifiedOperations,
        initialState: NavigationState
    ): FinalNavigationState {
        var currentEntry = initialState.currentEntry
        var backStack = initialState.backStack
        var modalContexts = initialState.activeModalContexts

        for (step in operations.navigationSteps) {
            val result = applyNavigationStep(
                step = step,
                currentEntry = currentEntry,
                backStack = backStack,
                modalContexts = modalContexts,
                guidedFlowContext = operations.guidedFlowContext,
                allOperations = operations.navigationSteps,
                activeGuidedFlowState = operations.activeGuidedFlowState
            )

            currentEntry = result.currentEntry
            backStack = result.backStack
            modalContexts = result.modalContexts
        }

        val completionInfo = operations.activeGuidedFlowState?.let { activeFlow ->
            if (activeFlow.isCompleted) {
                val flowDefinition = getEffectiveGuidedFlowDefinitionByRoute(activeFlow.flowRoute)
                Triple(activeFlow.flowRoute, activeFlow, flowDefinition)
            } else null
        }

        val finalActiveGuidedFlowState = if (shouldSyncGuidedFlowState(operations)) {
            syncGuidedFlowStateWithEntry(currentEntry, operations.activeGuidedFlowState)
        } else {
            operations.activeGuidedFlowState
        }

        val clearedActiveGuidedFlowState = if (completionInfo != null) null else finalActiveGuidedFlowState
        val clearedGuidedFlowModifications = if (completionInfo != null) {
            val (completedFlowRoute, _, flowDefinition) = completionInfo
            val clearBehavior = flowDefinition?.clearModificationsOnComplete ?: ClearModificationBehavior.CLEAR_NONE

            when (clearBehavior) {
                ClearModificationBehavior.CLEAR_ALL -> emptyMap()
                ClearModificationBehavior.CLEAR_SPECIFIC -> operations.guidedFlowModifications - completedFlowRoute
                ClearModificationBehavior.CLEAR_NONE -> operations.guidedFlowModifications
            }
        } else {
            operations.guidedFlowModifications
        }

        return FinalNavigationState(
            currentEntry = currentEntry,
            backStack = backStack,
            modalContexts = modalContexts,
            activeGuidedFlowState = clearedActiveGuidedFlowState,
            guidedFlowModifications = clearedGuidedFlowModifications
        )
    }

    /**
     * Determine if we should sync guided flow state with the navigation entry context
     */
    private fun shouldSyncGuidedFlowState(operations: UnifiedOperations): Boolean {
        return when {
            // Always sync for guided flow navigation (nextStep, etc.)
            operations.hasGuidedFlowNavigation -> true

            // Also sync for back navigation (to handle guided flow step changes or exits)
            operations.navigationSteps.any { it.operation == NavigationOperation.Back } -> true

            // Don't sync for other explicit navigation (navigateTo, replace, etc.)
            else -> false
        }
    }

    /**
     * Sync guided flow state with navigation entry's guided flow context
     * This ensures the active guided flow state matches the current step
     */
    private fun syncGuidedFlowStateWithEntry(
        navigationEntry: NavigationEntry,
        currentGuidedFlowState: GuidedFlowState?
    ): GuidedFlowState? {
        val entryGuidedFlowContext = navigationEntry.guidedFlowContext

        return when {
            // If entry has no guided flow context but we have an active flow, exit the flow
            entryGuidedFlowContext == null && currentGuidedFlowState != null -> {
                null // Exit the guided flow
            }

            // If entry has guided flow context and we have an active guided flow state
            entryGuidedFlowContext != null && currentGuidedFlowState != null -> {
                // Update the guided flow state to match the entry's step
                if (currentGuidedFlowState.flowRoute == entryGuidedFlowContext.flowRoute) {
                    val definition = guidedFlowDefinitions[entryGuidedFlowContext.flowRoute]
                    if (definition != null) {
                        currentGuidedFlowState.copy(currentStepIndex = entryGuidedFlowContext.stepIndex)
                            .let { updatedState ->
                                computeGuidedFlowProperties(updatedState, definition)
                            }
                    } else {
                        currentGuidedFlowState
                    }
                } else {
                    // Different flow - exit current flow
                    null
                }
            }

            // All other cases: preserve current state
            else -> currentGuidedFlowState
        }
    }

    /**
     * Check if navigation will cause a screen transition that requires animation
     */
    private fun willNavigationAnimate(
        initialState: NavigationState,
        finalState: FinalNavigationState
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
     * Schedule transition state reset after animation completes.
     * Cancels any previous pending reset to prevent race conditions.
     */
    private suspend fun scheduleTransitionStateReset(targetEntry: NavigationEntry, previousEntry: NavigationEntry) {
        // Cancel any existing transition reset job to prevent conflicts
        transitionResetJob?.cancel()

        // Calculate animation duration considering both enter and exit transitions
        val enterDuration = targetEntry.navigatable.enterTransition.durationMillis
        val exitDuration = previousEntry.navigatable.exitTransition.durationMillis

        // Use the longer of the two durations to ensure both animations complete
        val totalAnimationDuration = maxOf(enterDuration, exitDuration)

        transitionResetJob = timerScope.launch {
            delay(totalAnimationDuration.toLong())

            // Verify we're still in ANIMATING state before resetting
            val currentState = storeAccessor.selectState<NavigationState>().first()
            if (currentState.transitionState == NavigationTransitionState.ANIMATING) {
                storeAccessor.dispatch(NavigationAction.UpdateTransitionState(NavigationTransitionState.IDLE))
            }
        }
    }


    private suspend fun applyNavigationStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null,
        allOperations: List<NavigationStep> = emptyList(),
        activeGuidedFlowState: GuidedFlowState?
    ): NavigationStepResult {
        return when (step.operation) {
            NavigationOperation.Back -> {
                applyBackStep(currentEntry, backStack, modalContexts, activeGuidedFlowState)
            }

            NavigationOperation.PopUpTo -> {
                applyPopUpToStep(
                    step,
                    currentEntry,
                    backStack,
                    modalContexts,
                    guidedFlowContext,
                    allOperations,
                    activeGuidedFlowState
                )
            }

            NavigationOperation.ClearBackStack -> {
                applyClearBackStackStep(
                    step,
                    currentEntry,
                    backStack,
                    modalContexts,
                    guidedFlowContext,
                    activeGuidedFlowState
                )
            }

            NavigationOperation.Navigate -> {
                applyNavigateStep(
                    step,
                    currentEntry,
                    backStack,
                    modalContexts,
                    guidedFlowContext,
                    allOperations,
                    activeGuidedFlowState
                )
            }

            NavigationOperation.Replace -> {
                applyReplaceStep(
                    step,
                    currentEntry,
                    backStack,
                    modalContexts,
                    guidedFlowContext,
                    activeGuidedFlowState
                )
            }
        }
    }

    private data class NavigationStepResult(
        val currentEntry: NavigationEntry,
        val backStack: List<NavigationEntry>,
        val modalContexts: Map<String, ModalContext>,
        val activeGuidedFlowState: GuidedFlowState?
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

    internal suspend fun getEffectiveDefinition(flowRoute: String): GuidedFlowDefinition? {
        val currentState = getCurrentNavigationState()

        // First check for stored modifications
        currentState.guidedFlowModifications[flowRoute]?.let { return it }

        // Fall back to original definition
        return guidedFlowDefinitions[flowRoute]
    }

    internal fun getEffectiveDefinition(flowState: GuidedFlowState?): GuidedFlowDefinition? {
        return flowState?.let { guidedFlowDefinitions[it.flowRoute] }
    }

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

    private data class NavigationResult(
        val flowState: GuidedFlowState,
        val shouldNavigate: Boolean = false,
        val route: String = "",
        val params: Params = Params.empty(),
        val guidedFlowContext: GuidedFlowContext? = null
    )

    private suspend fun computeNextStep(
        flowState: GuidedFlowState,
        definition: GuidedFlowDefinition,
        params: Params = Params.empty()
    ): NavigationResult {
        val nextStepIndex = flowState.currentStepIndex + 1

        return when {
            nextStepIndex >= definition.steps.size -> {
                NavigationResult(
                    flowState = flowState.copy(
                        completedAt = Clock.System.now(),
                        isCompleted = true,
                        duration = Clock.System.now() - flowState.startedAt
                    )
                )
            }

            else -> {
                val nextStep = definition.steps[nextStepIndex]

                val fullRoute = nextStep.getRoute(precomputedData)
                val (cleanRoute, queryParams) = parseUrlWithQueryParams(fullRoute)
                val queryParamsAsParams = Params.fromMap(queryParams)
                val stepParams = params + nextStep.getParams() + queryParamsAsParams
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
                    route = cleanRoute,
                    params = stepParams,
                    guidedFlowContext = context
                )
            }
        }
    }


    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }


    // Step application methods - pure functions that compute new state without side effects
    private suspend fun applyBackStep(
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        activeGuidedFlowState: GuidedFlowState?
    ): NavigationStepResult {
        val result = applyRegularBackStep(
            currentEntry,
            backStack,
            modalContexts,
            activeGuidedFlowState
        )
        return result
    }


    private fun applyRegularBackStep(
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        activeGuidedFlowState: GuidedFlowState?
    ): NavigationStepResult {
        if (backStack.size <= 1) {
            return NavigationStepResult(
                currentEntry = currentEntry,
                backStack = backStack,
                modalContexts = modalContexts,
                activeGuidedFlowState = activeGuidedFlowState
            )
        }

        val newBackStack = backStack.dropLast(1)
        val targetEntry = newBackStack.last().copy(stackPosition = newBackStack.size)
        val finalBackStack = newBackStack.dropLast(1) + targetEntry

        val targetRoute = targetEntry.navigatable.route
        val modalContext = modalContexts[targetRoute]

        return if (modalContext != null) {
            val modalEntry = modalContext.modalEntry
            NavigationStepResult(
                currentEntry = modalEntry,
                backStack = finalBackStack + modalEntry,
                modalContexts = mapOf(modalEntry.navigatable.route to modalContext.copy(navigatedAwayToRoute = null)),
                activeGuidedFlowState = activeGuidedFlowState
            )
        } else {
            NavigationStepResult(
                currentEntry = targetEntry,
                backStack = finalBackStack,
                modalContexts = modalContexts.filterKeys { it != currentEntry.navigatable.route },
                activeGuidedFlowState = activeGuidedFlowState
            )
        }
    }

    private suspend fun applyNavigateStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null,
        allOperations: List<NavigationStep> = emptyList(),
        activeGuidedFlowState: GuidedFlowState?
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
            step.shouldDismissModals -> {
                emptyMap()
            }

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
                    modalContexts = updatedModalContexts,
                    activeGuidedFlowState = activeGuidedFlowState
                )
            }
        }

        return NavigationStepResult(
            currentEntry = newEntry,
            backStack = newBackStack,
            modalContexts = finalModalContexts,
            activeGuidedFlowState = activeGuidedFlowState
        )
    }

    private suspend fun applyReplaceStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null,
        activeGuidedFlowState: GuidedFlowState?
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
            modalContexts = finalModalContexts,
            activeGuidedFlowState = activeGuidedFlowState
        )
    }


    private suspend fun applyPopUpToStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null,
        allOperations: List<NavigationStep> = emptyList(),
        activeGuidedFlowState: GuidedFlowState?
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
                modalContexts = modalContexts,
                activeGuidedFlowState = activeGuidedFlowState
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
                            modalContexts = modalContexts,
                            activeGuidedFlowState = activeGuidedFlowState
                        )
                    }
                }

                NavigationStepResult(
                    currentEntry = currentEntry,
                    backStack = newBackStackAfterPop,
                    modalContexts = modalContexts,
                    activeGuidedFlowState = activeGuidedFlowState
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
                            modalContexts = modalContexts,
                            activeGuidedFlowState = activeGuidedFlowState
                        )
                    }
                }

                val targetEntry = newBackStackAfterPop.last()

                NavigationStepResult(
                    currentEntry = targetEntry,
                    backStack = newBackStackAfterPop,
                    modalContexts = modalContexts,
                    activeGuidedFlowState = activeGuidedFlowState
                )
            }
        }
    }

    private suspend fun applyClearBackStackStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        guidedFlowContext: GuidedFlowContext? = null,
        activeGuidedFlowState: GuidedFlowState?
    ): NavigationStepResult {
        if (step.target != null) {
            val resolvedRoute = step.target!!.resolve(precomputedData)
            val resolution = precomputedData.routeResolver.resolve(resolvedRoute, precomputedData.availableNavigatables)
                ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

            val newEntry = createNavigationEntry(step, resolution, stackPosition = 1, guidedFlowContext)

            return NavigationStepResult(
                currentEntry = newEntry,
                backStack = listOf(newEntry),
                modalContexts = emptyMap(),
                activeGuidedFlowState = activeGuidedFlowState
            )
        } else {
            // clearBackStack without target - completely clear backstack for fresh start
            // Current entry remains until next operation changes it
            return NavigationStepResult(
                currentEntry = currentEntry,
                backStack = emptyList(),
                modalContexts = emptyMap(),
                activeGuidedFlowState = activeGuidedFlowState
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
                    return
                }

                val effectiveDefinition = getEffectiveDefinition(flowRoute)

                if (effectiveDefinition != null) {
                } else {
                    val currentState = getCurrentNavigationState()
                }

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

                    executeNavigation(builder, "startGuidedFlow")
                }
            } catch (e: Exception) {
                // Log error but don't propagate to avoid breaking calling code
            }
        }
    }

    /**
     * Navigate to the next step in the currently active guided flow
     * This method updates state directly and handles flow completion
     */

}