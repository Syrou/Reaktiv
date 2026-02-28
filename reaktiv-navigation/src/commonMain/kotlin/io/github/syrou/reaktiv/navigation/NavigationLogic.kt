package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.CrashListener
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.definition.RemovalReason
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.dsl.NavigationStep
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Side-effecting logic for the navigation system.
 *
 * `NavigationLogic` orchestrates all navigation operations — guard evaluation, entry-point
 * resolution, back-stack synthesis, deep-link handling, and lifecycle callbacks. It is
 * created automatically by [NavigationModule] and registered with the store.
 *
 * The preferred way to trigger navigation from application code is via the
 * [StoreAccessor] extension functions (`navigation { }`, `navigateBack()`, etc.) which
 * delegate to the public methods on this class. Direct access via
 * `storeAccessor.selectLogic<NavigationLogic>()` is also supported when finer control
 * is needed.
 *
 * ```kotlin
 * // Typical usage via extension (recommended)
 * storeAccessor.navigation {
 *     navigateTo(ProfileScreen)
 * }
 *
 * // Or directly
 * val navLogic = storeAccessor.selectLogic<NavigationLogic>()
 * navLogic.navigate { navigateTo(ProfileScreen) }
 * ```
 *
 * @see NavigationModule
 * @see NavigationState
 */
@OptIn(ExperimentalReaktivApi::class)
class NavigationLogic(
    val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder(),
    private val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
) : ModuleLogic() {

    private val bootstrapCompleted = CompletableDeferred<Unit>()
    private val navigationMutex = Mutex()
    private val deepLinkStartedBeforeBootstrap = MutableStateFlow(false)

    private val entryLifecycleJobs = mutableMapOf<String, Job>()
    private val entryLifecycles = mutableMapOf<String, BackstackLifecycle>()

    private fun NavigationEntry.resolvedNavigatable(): Navigatable? =
        precomputedData.allNavigatables[path]

    private fun NavigationEntry.isModal(): Boolean = resolvedNavigatable() is Modal

    private fun NavigationEntry.isScreen(): Boolean = resolvedNavigatable() is Screen

    init {
        startLifecycleObservation()
        registerCrashListenerIfNeeded()
        bootstrapRootEntryIfNeeded()
    }

    private fun bootstrapRootEntryIfNeeded() {
        val rootEntryDef = precomputedData.graphEntries["root"]
        if (rootEntryDef?.route == null) {
            storeAccessor.launch {
                storeAccessor.dispatchAndAwait(NavigationAction.BootstrapComplete)
                bootstrapCompleted.complete(Unit)
            }
            return
        }

        storeAccessor.launch {
            try {
                val selectedNode = rootEntryDef.route.invoke(storeAccessor)

                if (!deepLinkStartedBeforeBootstrap.value) {
                    val routeBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                    routeBuilder.clearBackStack()
                    if (selectedNode is Navigatable) {
                        routeBuilder.navigateTo(selectedNode)
                    } else {
                        routeBuilder.navigateTo(selectedNode.route)
                    }
                    routeBuilder.validate()
                    executeNavigation(routeBuilder) { it + listOf(NavigationAction.BootstrapComplete) }
                }
            } finally {
                bootstrapCompleted.complete(Unit)
            }
        }
    }

    override suspend fun beforeReset() {
        entryLifecycles.values.forEach { it.runRemovalHandlers(RemovalReason.RESET) }
        entryLifecycleJobs.clear()
        entryLifecycles.clear()
    }

    private fun registerCrashListenerIfNeeded() {
        val crashScreenDef = precomputedData.crashScreen ?: return
        storeAccessor.addCrashListener(object : CrashListener {
            override suspend fun onLogicCrash(exception: Throwable, action: ModuleAction?): CrashRecovery {
                val recovery = onCrash?.invoke(exception, action)
                    ?: CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
                if (recovery == CrashRecovery.NAVIGATE_TO_CRASH_SCREEN) {
                    navigateToCrashScreen(exception, action, crashScreenDef)
                }
                return recovery
            }
        })
    }

    private suspend fun navigateToCrashScreen(
        exception: Throwable,
        action: ModuleAction?,
        crashScreenDef: Screen
    ) {
        try {
            val crashParams = Params.of(
                "exceptionType" to (exception::class.simpleName ?: "Unknown"),
                "exceptionMessage" to (exception.message ?: ""),
                "actionType" to (action?.let { it::class.simpleName } ?: "Logic Method")
            )
            val crashPath = precomputedData.navigatableToFullPath[crashScreenDef] ?: crashScreenDef.route
            val crashEntry = NavigationEntry(
                path = crashPath,
                params = crashParams,
                navigatableRoute = crashScreenDef.route
            )
            storeAccessor.dispatch(
                NavigationAction.Navigate(
                    entry = crashEntry,
                    modalContext = null,
                    dismissModals = false
                )
            )
        } catch (e: Exception) {
            println("NavigationLogic: Failed to navigate to crash screen - ${e.message}")
        }
    }

    private fun startLifecycleObservation() {
        storeAccessor.launch {
            storeAccessor.selectState<NavigationState>()
                .map { it.backStack }
                .distinctUntilChanged { old, new -> old.map { it.stableKey } == new.map { it.stableKey } }
                .scan(emptyList<NavigationEntry>() to emptyList<NavigationEntry>()) { (_, prev), current ->
                    prev to current
                }
                .collect { (previousBackStack, newBackStack) ->
                    invokeLifecycleCallbacks(previousBackStack, newBackStack)
                }
        }
    }

    /**
     * Execute a navigation operation. Evaluates intercept guards and entry definitions
     * before committing navigation.
     *
     * [io.github.syrou.reaktiv.navigation.layer.RenderLayer.SYSTEM] navigatables bypass the
     * bootstrap wait so they can appear above the loading screen immediately without waiting
     * for startup to complete.
     *
     * @return [NavigationOutcome] describing whether the navigation succeeded, was dropped,
     *   rejected, or redirected. Callers can ignore the return value for fire-and-forget use.
     */
    suspend fun navigate(block: suspend NavigationBuilder.() -> Unit): NavigationOutcome {
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.apply { block() }
        builder.validate()
        if (!isSystemLayerNavigation(builder)) {
            bootstrapCompleted.await()
        }
        return evaluateAndExecute(builder)
    }

    private fun isSystemLayerNavigation(builder: NavigationBuilder): Boolean {
        val primaryNavigate = builder.operations.firstOrNull {
            it.operation == NavigationOperation.Navigate
        } ?: return false
        val targetRoute = try {
            primaryNavigate.target?.resolve(precomputedData)
        } catch (e: Exception) {
            return false
        } ?: return false
        val resolution = precomputedData.routeResolver.resolve(
            targetRoute, precomputedData.availableNavigatables
        ) ?: return false
        return resolution.targetNavigatable.renderLayer == RenderLayer.SYSTEM
    }

    private sealed class GuardEvaluation {
        object Allow : GuardEvaluation()
        object Reject : GuardEvaluation()
        data class Redirect(val route: String) : GuardEvaluation()
        data class PendAndRedirect(
            val pending: PendingNavigation,
            val redirectRoute: String,
            val alreadyAtRedirect: Boolean
        ) : GuardEvaluation()
    }

    private suspend fun evaluateGuard(
        targetRoute: String,
        primaryStep: NavigationStep,
        currentState: NavigationState
    ): GuardEvaluation? {
        val targetResolution = precomputedData.routeResolver.resolve(targetRoute)
        val targetGraphId = targetResolution?.navigationGraphId
        val targetActualGraphId = targetResolution?.targetGraphId
        val interceptDef = precomputedData.interceptedRoutes[targetRoute]
            ?: targetGraphId?.let { precomputedData.interceptedRoutes[it] }
            ?: targetActualGraphId?.let { precomputedData.interceptedRoutes[it] }
            ?: return null

        return when (val result = evaluateWithThreshold(
            loadingThreshold = interceptDef.loadingThreshold
        ) { interceptDef.guard(storeAccessor) }) {
            is GuardResult.Allow -> GuardEvaluation.Allow
            is GuardResult.Reject -> GuardEvaluation.Reject
            is GuardResult.RedirectTo -> GuardEvaluation.Redirect(result.route)
            is GuardResult.PendAndRedirectTo -> {
                val pending = PendingNavigation(
                    route = targetRoute,
                    params = primaryStep.params,
                    metadata = result.metadata,
                    displayHint = result.displayHint
                )
                val redirectResolution = precomputedData.routeResolver.resolve(
                    result.route, precomputedData.availableNavigatables
                )
                val redirectPath = redirectResolution?.targetNavigatable?.let {
                    precomputedData.navigatableToFullPath[it]
                }
                GuardEvaluation.PendAndRedirect(
                    pending = pending,
                    redirectRoute = result.route,
                    alreadyAtRedirect = redirectPath == currentState.currentEntry.path
                )
            }
        }
    }

    private suspend fun resolveEntryNavigatable(targetRoute: String): NavigationNode? {
        precomputedData.graphDefinitions[targetRoute] ?: return null
        val entryDef = precomputedData.graphEntries[targetRoute] ?: return null
        if (entryDef.route == null) return null
        return evaluateWithThreshold(
            loadingThreshold = entryDef.loadingThreshold
        ) { entryDef.route.invoke(storeAccessor) }
    }

    private suspend fun navigateDirect(route: String) {
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.navigateTo(route)
        builder.validate()
        executeNavigation(builder)
    }

    /**
     * Evaluate intercept guards and entry definitions for the given builder, then execute
     * the navigation. Runs inside [NonCancellable] so that guard evaluation and state
     * commits are never partially cancelled.
     *
     * Returns [NavigationOutcome.Dropped] immediately if another navigation is already in progress.
     */
    private suspend fun evaluateAndExecute(builder: NavigationBuilder): NavigationOutcome {
        if (!navigationMutex.tryLock()) return NavigationOutcome.Dropped
        val isSystemLayer = isSystemLayerNavigation(builder)
        try {
            return withContext(NonCancellable) {
                try {
                    val primaryStep = builder.operations.firstOrNull {
                        it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
                    }

                    if (primaryStep == null) {
                        executeNavigation(builder)
                        return@withContext NavigationOutcome.Success
                    }

                    val targetRoute = try {
                        primaryStep.target?.resolve(precomputedData)
                    } catch (e: Exception) {
                        null
                    }

                    if (targetRoute == null) {
                        executeNavigation(builder)
                        return@withContext NavigationOutcome.Success
                    }

                    val currentState = getCurrentNavigationState()

                    when (val guard = evaluateGuard(targetRoute, primaryStep, currentState)) {
                        is GuardEvaluation.Reject -> return@withContext NavigationOutcome.Rejected
                        is GuardEvaluation.Redirect -> {
                            navigateDirect(guard.route)
                            return@withContext NavigationOutcome.Redirected(guard.route)
                        }
                        is GuardEvaluation.PendAndRedirect -> {
                            storeAccessor.dispatchAndAwait(NavigationAction.SetPendingNavigation(guard.pending))
                            if (!guard.alreadyAtRedirect) {
                                val redirectBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                                redirectBuilder.clearBackStack()
                                redirectBuilder.navigateTo(guard.redirectRoute)
                                redirectBuilder.validate()
                                executeNavigation(redirectBuilder)
                            }
                            return@withContext NavigationOutcome.Redirected(guard.redirectRoute)
                        }
                        is GuardEvaluation.Allow, null -> Unit
                    }

                    val entryNode = resolveEntryNavigatable(targetRoute)
                    if (entryNode != null) {
                        val routeBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                        if (entryNode is Navigatable) routeBuilder.navigateTo(entryNode)
                        else routeBuilder.navigateTo(entryNode.route)
                        routeBuilder.validate()
                        executeNavigation(routeBuilder)
                        return@withContext NavigationOutcome.Success
                    }

                    executeNavigation(builder)
                    NavigationOutcome.Success
                } finally {
                    if (!isSystemLayer) {
                        val stateAfter = getCurrentNavigationState()
                        if (stateAfter.backStack.any { precomputedData.allNavigatables[it.path] is LoadingModal }) {
                            storeAccessor.dispatchAndAwait(NavigationAction.RemoveLoadingModals)
                        }
                    }
                }
            }
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Evaluate a suspend block, showing the global [LoadingModal] as a SYSTEM overlay if
     * evaluation takes longer than [loadingThreshold].
     *
     * The modal is dispatched directly as [NavigationAction.Navigate] so it bypasses
     * [evaluateAndExecute] and the navigation mutex. Cleanup is handled by the
     * [evaluateAndExecute] finally block via [NavigationAction.RemoveLoadingModals].
     */
    private suspend fun <T> evaluateWithThreshold(
        loadingThreshold: Duration,
        evaluate: suspend () -> T
    ): T = coroutineScope {
        val deferred = async { evaluate() }
        val quickResult = withTimeoutOrNull(loadingThreshold) { deferred.await() }
        if (quickResult != null) {
            quickResult
        } else {
            val loadingModal = precomputedData.loadingModal
            if (loadingModal != null) {
                val loadingPath = precomputedData.navigatableToFullPath[loadingModal] ?: loadingModal.route
                val loadingEntry = loadingModal.toNavigationEntry(
                    path = loadingPath,
                    params = Params.empty()
                )
                storeAccessor.dispatchAndAwait(NavigationAction.Navigate(loadingEntry))
            }
            deferred.await()
        }
    }

    /**
     * Navigate to a route with optional parameters and configuration.
     *
     * @param route Target route to navigate to
     * @param params Parameters to pass to the destination screen
     * @param replaceCurrent If true, replaces current entry instead of pushing new one
     * @param config Optional additional navigation configuration
     * @return [NavigationOutcome] describing whether the navigation succeeded, was dropped,
     *   rejected, or redirected.
     */
    suspend fun navigate(
        route: String,
        params: Params = Params.empty(),
        replaceCurrent: Boolean = false,
        config: (NavigationBuilder.() -> Unit)? = null
    ): NavigationOutcome {
        return navigate {
            params(params)
            navigateTo(route, replaceCurrent)
            config?.invoke(this)
        }
    }

    /**
     * Navigate back in the navigation stack.
     *
     * No-op if the current entry is a [Modal] with [Modal.dismissable] set to false —
     * such modals cannot be dismissed by the user via back gesture or tap-outside.
     *
     * Dispatches [NavigationAction.Back] directly, bypassing the navigation mutex.
     * This is intentional: a back/dismiss requires no guard evaluation, and the mutex
     * may be held while a loading modal is showing (e.g. during guard evaluation).
     * Routing through [evaluateAndExecute] would silently drop the dismiss via tryLock.
     */
    suspend fun navigateBack() {
        val currentState = getCurrentNavigationState()
        if (!currentState.canGoBack) return
        val currentModal = precomputedData.allNavigatables[currentState.currentEntry.path] as? Modal
        if (currentModal != null && !currentModal.dismissable) return
        storeAccessor.dispatchAndAwait(NavigationAction.Back)
    }

    /**
     * Pop up to a specific route in the backstack.
     *
     * @param route Target route to pop back to
     * @param inclusive If true, also removes the target route from backstack
     * @param fallback Optional fallback route if the target route is not found
     */
    suspend fun popUpTo(route: String, inclusive: Boolean = false, fallback: String? = null) {
        navigate {
            popUpTo(route, inclusive, fallback)
        }
    }

    /**
     * Navigate to a deep link route with guard evaluation.
     * Checks alias mappings first before resolving the route normally.
     *
     * @param route Target route to navigate to
     * @param params Parameters to pass to the destination screen
     */
    suspend fun navigateDeepLink(route: String, params: Params = Params.empty()) {
        val (cleanRoute, queryParams) = parseUrlWithQueryParams(route)

        val alias = precomputedData.deepLinkAliases.find { it.pattern == cleanRoute }

        val targetRoute: String
        val targetParams: Params
        if (alias != null) {
            targetRoute = alias.targetRoute
            targetParams = alias.paramsMapping(Params.fromMap(queryParams) + params)
        } else {
            targetRoute = route
            targetParams = params
        }

        deepLinkStartedBeforeBootstrap.value = true
        val bootstrapWasComplete = bootstrapCompleted.isCompleted
        if (!bootstrapWasComplete) {
            bootstrapCompleted.await()
        }

        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.clearBackStack()
        builder.params(targetParams)
        builder.navigateTo(targetRoute, synthesizeBackstack = true)
        builder.validate()
        evaluateAndExecute(builder)

        if (!bootstrapWasComplete) {
            storeAccessor.dispatchAndAwait(NavigationAction.BootstrapComplete)
        }
    }

    /**
     * Clear the entire backstack and optionally navigate to a new route.
     *
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
     * Resume a pending navigation stored by [GuardResult.PendAndRedirectTo].
     *
     * Navigates to the stored route with backstack synthesis, then clears the pending
     * navigation from state. Bypasses guard evaluation for the resumed navigation.
     *
     * No-op if there is no pending navigation in state.
     */
    suspend fun resumePendingNavigation() {
        withContext(NonCancellable) {
            bootstrapCompleted.await()
            val pending = getCurrentNavigationState().pendingNavigation ?: return@withContext
            val routeBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
            routeBuilder.clearBackStack()
            routeBuilder.params(pending.params)
            routeBuilder.navigateTo(pending.route, synthesizeBackstack = true)
            routeBuilder.validate()
            executeNavigation(routeBuilder) { listOf(NavigationAction.ClearPendingNavigation) + it }
        }
    }

    private suspend fun executeNavigation(
        builder: NavigationBuilder,
        wrapActions: (List<NavigationAction>) -> List<NavigationAction> = { it }
    ) {
        val initialState = getCurrentNavigationState()
        var simulatedCurrentEntry = initialState.currentEntry
        val navigationStartEntry = simulatedCurrentEntry
        var simulatedBackStack = initialState.backStack
        var simulatedModalContexts = initialState.activeModalContexts
        var lastNavigatedEntry: NavigationEntry? = null

        val batchedActions = mutableListOf<NavigationAction>()

        for (step in builder.operations) {
            when (step.operation) {
                NavigationOperation.Navigate -> {
                    val resolvedRoute = step.target?.resolve(precomputedData)
                        ?: throw IllegalStateException("Navigate requires a target")
                    val resolution = precomputedData.routeResolver.resolve(
                        resolvedRoute, precomputedData.availableNavigatables
                    ) ?: throw RouteNotFoundException("Route not found: $resolvedRoute")

                    if (step.synthesizeBackstack) {
                        val pathHierarchy = precomputedData.routeResolver.buildPathHierarchy(resolvedRoute)
                        val destinationPath = precomputedData.navigatableToFullPath[resolution.targetNavigatable]
                            ?: resolution.targetNavigatable.route
                        val seenPaths = (simulatedBackStack.map { it.path } + destinationPath).toMutableSet()
                        for (intermediatePath in pathHierarchy.dropLast(1)) {
                            val res = precomputedData.routeResolver.resolveForBackstackSynthesis(intermediatePath)
                            if (res == null) continue
                            val resPath = precomputedData.navigatableToFullPath[res.targetNavigatable]
                                ?: res.targetNavigatable.route
                            if (resPath in seenPaths) continue
                            seenPaths.add(resPath)
                            val intermediate = res.targetNavigatable.toNavigationEntry(
                                path = resPath,
                                params = res.extractedParams
                            )
                            batchedActions.add(NavigationAction.Navigate(intermediate))
                            simulatedBackStack = simulatedBackStack + intermediate
                            simulatedCurrentEntry = intermediate
                            lastNavigatedEntry = intermediate
                        }
                        val finalPath = precomputedData.navigatableToFullPath[resolution.targetNavigatable]
                            ?: resolution.targetNavigatable.route
                        val finalEntry = createNavigationEntry(step, resolution, finalPath, 0)
                        batchedActions.add(NavigationAction.Navigate(finalEntry, dismissModals = step.shouldDismissModals))
                        simulatedBackStack = simulatedBackStack + finalEntry
                        simulatedCurrentEntry = finalEntry
                        lastNavigatedEntry = finalEntry
                    } else {
                        val entryPath = precomputedData.navigatableToFullPath[resolution.targetNavigatable]
                            ?: resolution.targetNavigatable.route
                        val entry = createNavigationEntry(step, resolution, entryPath, 0)
                        if (simulatedBackStack.isNotEmpty() && entry.path == simulatedCurrentEntry.path) {
                            continue
                        }
                        val isModal = precomputedData.allNavigatables[entry.path] is Modal
                        val modalCtx = if (isModal) buildModalContext(
                            entry, simulatedCurrentEntry, simulatedBackStack, simulatedModalContexts
                        ) else null
                        batchedActions.add(NavigationAction.Navigate(entry, modalCtx, step.shouldDismissModals))

                        val baseBackStack = if (step.shouldDismissModals)
                            simulatedBackStack.filter { precomputedData.allNavigatables[it.path] !is Modal }
                        else simulatedBackStack
                        simulatedBackStack = when {
                            isModal -> simulatedBackStack + entry
                            baseBackStack.isEmpty() -> listOf(entry)
                            else -> baseBackStack + entry
                        }
                        simulatedModalContexts = when {
                            step.shouldDismissModals -> emptyMap()
                            isModal && modalCtx != null ->
                                simulatedModalContexts + (entry.path to modalCtx)
                            !isModal && !step.shouldDismissModals &&
                                    precomputedData.allNavigatables[simulatedCurrentEntry.path] is Modal &&
                                    simulatedModalContexts.isNotEmpty() -> {
                                val modalPath = simulatedCurrentEntry.path
                                val ctx = simulatedModalContexts[modalPath]
                                if (ctx != null) {
                                    val underlying = ctx.originalUnderlyingScreenEntry.path
                                    mapOf(underlying to ctx.copy(navigatedAwayToRoute = entry.path))
                                } else simulatedModalContexts
                            }
                            else -> simulatedModalContexts
                        }
                        simulatedCurrentEntry = entry
                        lastNavigatedEntry = entry
                    }
                }

                NavigationOperation.Replace -> {
                    val resolvedRoute = step.target?.resolve(precomputedData)
                        ?: throw IllegalStateException("Replace requires a target")
                    val resolution = precomputedData.routeResolver.resolve(
                        resolvedRoute, precomputedData.availableNavigatables
                    ) ?: throw RouteNotFoundException("Route not found: $resolvedRoute")
                    val entryPath = precomputedData.navigatableToFullPath[resolution.targetNavigatable]
                        ?: resolution.targetNavigatable.route
                    val entry = createNavigationEntry(step, resolution, entryPath, simulatedBackStack.size)
                    batchedActions.add(NavigationAction.Replace(entry))
                    simulatedBackStack = if (simulatedBackStack.isEmpty()) listOf(entry)
                                         else simulatedBackStack.dropLast(1) + entry
                    simulatedCurrentEntry = entry
                    lastNavigatedEntry = entry
                }

                NavigationOperation.Back -> {
                    batchedActions.add(NavigationAction.Back)
                    if (simulatedBackStack.size > 1) {
                        simulatedBackStack = simulatedBackStack.dropLast(1)
                        simulatedCurrentEntry = simulatedBackStack.last()
                    }
                    lastNavigatedEntry = null
                }

                NavigationOperation.ClearBackStack -> {
                    batchedActions.add(NavigationAction.ClearBackstack)
                    simulatedBackStack = emptyList()
                    simulatedModalContexts = emptyMap()
                    lastNavigatedEntry = null
                }

                NavigationOperation.PopUpTo -> {
                    val resolvedRoute = step.popUpToTarget?.resolve(precomputedData)
                        ?: throw IllegalStateException("PopUpTo operation requires a popUpTo target")

                    val targetIndex = precomputedData.routeResolver.findRouteInBackStack(
                        resolvedRoute, simulatedBackStack
                    )

                    if (targetIndex < 0) {
                        if (step.popUpToFallback != null) {
                            val fallbackRoute = step.popUpToFallback.resolve(precomputedData)
                            val resolution = precomputedData.routeResolver.resolve(
                                fallbackRoute, precomputedData.availableNavigatables
                            ) ?: throw RouteNotFoundException("Fallback route not found: $fallbackRoute")
                            val fallbackPath = precomputedData.navigatableToFullPath[resolution.targetNavigatable]
                                ?: resolution.targetNavigatable.route
                            val newEntry = createNavigationEntry(
                                step.copy(target = step.popUpToFallback),
                                resolution,
                                fallbackPath,
                                stackPosition = 1
                            )
                            batchedActions.add(NavigationAction.Navigate(newEntry))
                            simulatedBackStack = listOf(newEntry)
                            simulatedCurrentEntry = newEntry
                            simulatedModalContexts = emptyMap()
                            lastNavigatedEntry = newEntry
                        } else {
                            throw RouteNotFoundException("No match found for route $resolvedRoute")
                        }
                    } else {
                        val trimmedBackStack = if (step.popUpToInclusive) {
                            simulatedBackStack.take(targetIndex)
                        } else {
                            simulatedBackStack.take(targetIndex + 1)
                        }

                        val entryToReAdd = if (lastNavigatedEntry != null &&
                            trimmedBackStack.none { it.path == lastNavigatedEntry!!.path }) {
                            lastNavigatedEntry
                        } else null

                        val wouldBeEmpty = trimmedBackStack.isEmpty() && entryToReAdd == null
                        if (wouldBeEmpty) {
                            throw IllegalStateException(
                                "PopUpTo with inclusive=true on route '$resolvedRoute' would result in an empty back stack. " +
                                "Either use inclusive=false, or navigate to a new destination before calling popUpTo."
                            )
                        }

                        batchedActions.add(NavigationAction.PopUpTo(resolvedRoute, step.popUpToInclusive, entryToReAdd))

                        simulatedBackStack = if (entryToReAdd != null) {
                            val pos = trimmedBackStack.size + 1
                            trimmedBackStack + entryToReAdd.copy(stackPosition = pos)
                        } else {
                            trimmedBackStack
                        }
                        simulatedCurrentEntry = simulatedBackStack.lastOrNull() ?: simulatedCurrentEntry
                        val trimmedPaths = simulatedBackStack.map { it.path }.toSet()
                        simulatedModalContexts = simulatedModalContexts.filterKeys { it in trimmedPaths }
                        lastNavigatedEntry = null
                    }
                }
            }
        }

        val stateBeforeDispatch = getCurrentNavigationState()
        val baseActions = wrapActions(batchedActions)
        val hasNonSystemNavigate = batchedActions
            .filterIsInstance<NavigationAction.Navigate>()
            .any { precomputedData.allNavigatables[it.entry.path]?.renderLayer != RenderLayer.SYSTEM }
        val allActions = if (hasNonSystemNavigate && stateBeforeDispatch.backStack.any { precomputedData.allNavigatables[it.path] is LoadingModal }) {
            listOf(NavigationAction.RemoveLoadingModals) + baseActions
        } else {
            baseActions
        }
        when {
            allActions.isEmpty() -> return
            allActions.size == 1 -> storeAccessor.dispatchAndAwait(allActions[0])
            else -> storeAccessor.dispatchAndAwait(NavigationAction.AtomicBatch(allActions))
        }

        val lastNavigatedNavEntry = batchedActions
            .filterIsInstance<NavigationAction.Navigate>()
            .lastOrNull()?.entry
        val lastNavigatedNavigatable = lastNavigatedNavEntry?.let { precomputedData.allNavigatables[it.path] }
        val enterMs = lastNavigatedNavigatable?.enterTransition?.durationMillis?.toLong() ?: 0L
        val exitMs = precomputedData.allNavigatables[navigationStartEntry.path]
            ?.exitTransition?.durationMillis?.toLong() ?: 0L
        val animMs = maxOf(enterMs, exitMs)
        if (animMs > 0L) delay(animMs)
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

        val addedEntries = newBackStack.filter { it.stableKey !in previousKeys }
        val removedEntries = previousBackStack.filter { it.stableKey !in newKeys }

        val navigationStateFlow = storeAccessor.selectState<NavigationState>()

        addedEntries.forEach { entry ->
            val navigatable = precomputedData.allNavigatables[entry.path] ?: return@forEach
            try {
                val lifecycleJob = SupervisorJob(storeAccessor.coroutineContext[Job])
                val lifecycleScope = CoroutineScope(storeAccessor.coroutineContext + lifecycleJob)
                entryLifecycleJobs[entry.stableKey] = lifecycleJob

                val lifecycle = BackstackLifecycle(entry, navigationStateFlow, storeAccessor, lifecycleScope)
                entryLifecycles[entry.stableKey] = lifecycle
                navigatable.onLifecycleCreated(lifecycle)
            } catch (e: Exception) {
                ReaktivDebug.warn("Warning: onLifecycle failed for ${entry.path}: ${e.message}")
            }
        }

        removedEntries.forEach { entry ->
            entryLifecycles.remove(entry.stableKey)?.runRemovalHandlers(RemovalReason.NAVIGATION)
            entryLifecycleJobs.remove(entry.stableKey)?.cancel()
        }
    }

    /**
     * Create a navigation entry with proper parameter encoding and position.
     */
    private suspend fun createNavigationEntry(
        step: NavigationStep,
        resolution: RouteResolution,
        path: String,
        stackPosition: Int
    ): NavigationEntry {
        val encodedParams = step.params
        val mergedParams = resolution.extractedParams + encodedParams

        return NavigationEntry(
            path = path,
            params = mergedParams,
            stackPosition = stackPosition,
            navigatableRoute = resolution.targetNavigatable.route
        )
    }

    private fun buildModalContext(
        entry: NavigationEntry,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        activeModalContexts: Map<String, ModalContext>
    ): ModalContext? {
        val underlying = if (precomputedData.allNavigatables[currentEntry.path] is Modal)
            activeModalContexts.values.firstOrNull()?.originalUnderlyingScreenEntry
                ?: findUnderlyingScreenForModal(currentEntry, backStack)
        else currentEntry
        return underlying?.let {
            ModalContext(
                modalEntry = entry,
                originalUnderlyingScreenEntry = it
            )
        }
    }

    private fun findUnderlyingScreenForModal(
        modalEntry: NavigationEntry,
        backStack: List<NavigationEntry>
    ): NavigationEntry? {
        val modalIndex = backStack.indexOf(modalEntry)
        if (modalIndex <= 0) return null

        return backStack.subList(0, modalIndex).lastOrNull {
            precomputedData.allNavigatables[it.path] is Screen
        }
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

}
