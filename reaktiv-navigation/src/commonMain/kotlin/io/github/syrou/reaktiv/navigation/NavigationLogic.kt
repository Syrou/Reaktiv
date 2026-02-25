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
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.RemovalReason
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.dsl.NavigationStep
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

@OptIn(ExperimentalReaktivApi::class)
class NavigationLogic(
    val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder(),
    private val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
) : ModuleLogic<NavigationAction>() {

    private val bootstrapCompleted = CompletableDeferred<Unit>()
    private val navigationMutex = Mutex()
    // When a deep link arrives before bootstrap finishes, this flag tells bootstrapRootEntryIfNeeded
    // to skip its Navigate(result) dispatch. The deep link handler owns the first navigation and
    // BootstrapComplete, preventing the bootstrap destination from flashing before the deep link.
    private val deepLinkStartedBeforeBootstrap = MutableStateFlow(false)

    private val entryLifecycleJobs = mutableMapOf<String, Job>()
    private val entryLifecycles = mutableMapOf<String, BackstackLifecycle>()

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
                    executeNavigation(routeBuilder, suffixActions = listOf(NavigationAction.BootstrapComplete))
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
            val currentState = storeAccessor.selectState<NavigationState>().value
            val crashEntry = NavigationEntry(
                navigatable = crashScreenDef,
                params = crashParams,
                graphId = currentState.currentEntry.graphId
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
     */
    suspend fun navigate(block: suspend NavigationBuilder.() -> Unit) {
        bootstrapCompleted.await()
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.apply { block() }
        builder.validate()
        evaluateAndExecute(builder)
    }

    /**
     * Processes a deep link that arrived before bootstrap completed, as part of the unified
     * bootstrap flow. Runs the guard directly (no loading screen threshold) and, if the guard
     * returns [GuardResult.PendAndRedirectTo] and we are already on that redirect screen (e.g.
     * LoginScreen selected by the root entry lambda), stores [PendingNavigation] without any
     * additional navigation — eliminating the blink.
     */
    private suspend fun processStartupDeepLink(targetRoute: String, targetParams: Params) {
        val targetResolution = precomputedData.routeResolver.resolve(targetRoute)
        val targetGraphId = targetResolution?.navigationGraphId
        val targetActualGraphId = targetResolution?.targetGraphId
        val interceptDef = precomputedData.interceptedRoutes[targetRoute]
            ?: targetGraphId?.let { precomputedData.interceptedRoutes[it] }
            ?: targetActualGraphId?.let { precomputedData.interceptedRoutes[it] }

        if (interceptDef == null) {
            val deepLinkBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
            deepLinkBuilder.params(targetParams)
            deepLinkBuilder.navigateTo(targetRoute, synthesizeBackstack = true)
            deepLinkBuilder.validate()
            executeNavigation(deepLinkBuilder)
            return
        }

        val guardResult = interceptDef.guard(storeAccessor)

        when (guardResult) {
            is GuardResult.Allow -> {
                val deepLinkBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                deepLinkBuilder.params(targetParams)
                deepLinkBuilder.navigateTo(targetRoute, synthesizeBackstack = true)
                deepLinkBuilder.validate()
                executeNavigation(deepLinkBuilder)
            }

            is GuardResult.PendAndRedirectTo -> {
                val pending = PendingNavigation(
                    route = targetRoute,
                    params = targetParams,
                    metadata = guardResult.metadata,
                    displayHint = guardResult.displayHint
                )
                storeAccessor.dispatchAndAwait(NavigationAction.SetPendingNavigation(pending))

                val currentNavigatable = getCurrentNavigationState().currentEntry.navigatable
                val redirectResolution = precomputedData.routeResolver.resolve(
                    guardResult.route, precomputedData.availableNavigatables
                )
                val alreadyAtRedirect = redirectResolution?.targetNavigatable == currentNavigatable

                if (!alreadyAtRedirect) {
                    val redirectBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                    redirectBuilder.navigateTo(guardResult.route)
                    redirectBuilder.validate()
                    executeNavigation(redirectBuilder)
                }
            }

            is GuardResult.RedirectTo -> {
                val redirectBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                redirectBuilder.navigateTo(guardResult.route)
                redirectBuilder.validate()
                executeNavigation(redirectBuilder)
            }

            is GuardResult.Reject -> Unit
        }
    }

    /**
     * Evaluate intercept guards and entry definitions for the given builder, then execute
     * the navigation. Runs inside [NonCancellable] so that guard evaluation and state
     * commits are never partially cancelled.
     */
    private suspend fun evaluateAndExecute(builder: NavigationBuilder) {
        if (!navigationMutex.tryLock()) return
        try {
        withContext(NonCancellable) {
            val primaryStep = builder.operations.firstOrNull {
                it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
            }

            if (primaryStep == null) {
                executeNavigation(builder)
                return@withContext
            }

            val targetRoute = try {
                primaryStep.target?.resolve(precomputedData)
            } catch (e: Exception) {
                null
            }

            if (targetRoute == null) {
                executeNavigation(builder)
                return@withContext
            }

            val targetResolution = precomputedData.routeResolver.resolve(targetRoute)
            val targetGraphId = targetResolution?.navigationGraphId
            val targetActualGraphId = targetResolution?.targetGraphId

            val interceptDef = precomputedData.interceptedRoutes[targetRoute]
                ?: targetGraphId?.let { precomputedData.interceptedRoutes[it] }
                ?: targetActualGraphId?.let { precomputedData.interceptedRoutes[it] }
            if (interceptDef != null) {
                val stateBeforeGuard = getCurrentNavigationState()
                val result = evaluateWithThreshold(
                    loadingScreen = interceptDef.loadingScreen,
                    loadingThreshold = interceptDef.loadingThreshold
                ) { interceptDef.guard(storeAccessor) }


                when (result) {
                    is GuardResult.Allow -> Unit
                    is GuardResult.Reject -> return@withContext
                    is GuardResult.RedirectTo -> {
                        val redirectBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                        redirectBuilder.navigateTo(result.route)
                        redirectBuilder.validate()
                        executeNavigation(redirectBuilder)
                        return@withContext
                    }

                    is GuardResult.PendAndRedirectTo -> {
                        val pending = PendingNavigation(
                            route = targetRoute,
                            params = primaryStep.params,
                            metadata = result.metadata,
                            displayHint = result.displayHint
                        )
                        storeAccessor.dispatchAndAwait(NavigationAction.SetPendingNavigation(pending))
                        val redirectResolution = precomputedData.routeResolver.resolve(
                            result.route, precomputedData.availableNavigatables
                        )
                        val alreadyAtRedirect = redirectResolution?.targetNavigatable ==
                                stateBeforeGuard.currentEntry.navigatable
                        if (!alreadyAtRedirect) {
                            val redirectBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                            redirectBuilder.navigateTo(result.route)
                            redirectBuilder.validate()
                            executeNavigation(redirectBuilder)
                        }
                        return@withContext
                    }
                }
            }

            val graphDef = precomputedData.graphDefinitions[targetRoute]
                ?: targetGraphId?.let { precomputedData.graphDefinitions[it] }
            if (graphDef != null) {
                val entryDef = precomputedData.graphEntries[targetRoute]
                    ?: targetGraphId?.let { precomputedData.graphEntries[it] }
                if (entryDef != null) {
                    if (entryDef.route != null) {
                        val selectedNode = evaluateWithThreshold(
                            loadingScreen = entryDef.loadingScreen,
                            loadingThreshold = entryDef.loadingThreshold
                        ) { entryDef.route.invoke(storeAccessor) }

                        val routeBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                        if (selectedNode is Navigatable) {
                            routeBuilder.navigateTo(selectedNode)
                        } else {
                            routeBuilder.navigateTo(selectedNode.route)
                        }
                        routeBuilder.validate()
                        executeNavigation(routeBuilder)
                        return@withContext
                    }
                }
            }

            executeNavigation(builder)
        }
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Evaluate a suspend block, showing a loading screen if evaluation takes longer than
     * [loadingThreshold].
     */
    private suspend fun <T> evaluateWithThreshold(
        loadingScreen: Screen?,
        loadingThreshold: Duration,
        evaluate: suspend () -> T
    ): T = coroutineScope {
        val deferred = async { evaluate() }
        val quickResult = withTimeoutOrNull(loadingThreshold) { deferred.await() }
        if (quickResult != null) {
            quickResult
        } else {
            if (loadingScreen != null) {
                val lb = NavigationBuilder(storeAccessor, parameterEncoder)
                lb.navigateTo(loadingScreen.route)
                lb.validate()
                executeNavigation(lb)
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
     * Navigate back in the navigation stack.
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

        val bootstrapWasComplete = bootstrapCompleted.isCompleted
        if (!bootstrapWasComplete) {
            // Must be set before awaiting so bootstrap sees the flag after its own suspensions.
            deepLinkStartedBeforeBootstrap.value = true
            bootstrapCompleted.await()
        }

        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
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
            executeNavigation(
                routeBuilder,
                prefixActions = listOf(NavigationAction.ClearPendingNavigation)
            )
        }
    }

    private suspend fun executeNavigation(
        builder: NavigationBuilder,
        prefixActions: List<NavigationAction> = emptyList(),
        suffixActions: List<NavigationAction> = emptyList()
    ) {
        val initialState = getCurrentNavigationState()
        var simulatedCurrentEntry = initialState.currentEntry
        val navigationStartEntry = simulatedCurrentEntry
        var simulatedBackStack = initialState.backStack
        var simulatedModalContexts = initialState.activeModalContexts

        val batchedActions = mutableListOf<NavigationAction>()
        var lastNavigatedEntry: NavigationEntry? = null

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
                        val destinationKey = "${resolution.targetNavigatable.route}@${resolution.getEffectiveGraphId()}"
                        val seenNavigatableKeys = (simulatedBackStack.map { "${it.navigatable.route}@${it.graphId}" } + destinationKey).toMutableSet()
                        for (intermediatePath in pathHierarchy.dropLast(1)) {
                            val res = precomputedData.routeResolver.resolveForBackstackSynthesis(intermediatePath)
                            val resKey = res?.let { "${it.targetNavigatable.route}@${it.getEffectiveGraphId()}" }
                            if (res == null) continue
                            val key = "${res.targetNavigatable.route}@${res.getEffectiveGraphId()}"
                            if (key in seenNavigatableKeys) continue
                            seenNavigatableKeys.add(key)
                            val intermediate = res.targetNavigatable.toNavigationEntry(
                                params = res.extractedParams,
                                graphId = res.getEffectiveGraphId(),
                                stackPosition = 0
                            )
                            batchedActions.add(NavigationAction.Navigate(intermediate))
                            simulatedBackStack = simulatedBackStack + intermediate
                            simulatedCurrentEntry = intermediate
                        }
                        val finalEntry = createNavigationEntry(step, resolution, 0)
                        batchedActions.add(NavigationAction.Navigate(finalEntry, dismissModals = step.shouldDismissModals))
                        simulatedBackStack = simulatedBackStack + finalEntry
                        simulatedCurrentEntry = finalEntry
                        lastNavigatedEntry = finalEntry
                    } else {
                        val entry = createNavigationEntry(step, resolution, 0)
                        if (simulatedBackStack.isNotEmpty() &&
                            entry.navigatable.route == simulatedCurrentEntry.navigatable.route &&
                            entry.graphId == simulatedCurrentEntry.graphId) {
                            continue
                        }
                        val isModal = entry.navigatable is Modal
                        val modalCtx = if (isModal) buildModalContext(
                            entry, simulatedCurrentEntry, simulatedBackStack, simulatedModalContexts
                        ) else null
                        batchedActions.add(NavigationAction.Navigate(entry, modalCtx, step.shouldDismissModals))

                        val baseBackStack = if (step.shouldDismissModals) simulatedBackStack.filter { it.isScreen }
                                           else simulatedBackStack
                        simulatedBackStack = when {
                            isModal -> simulatedBackStack + entry
                            baseBackStack.isEmpty() -> listOf(entry)
                            else -> baseBackStack + entry
                        }
                        simulatedModalContexts = when {
                            step.shouldDismissModals -> emptyMap()
                            isModal && modalCtx != null ->
                                simulatedModalContexts + (entry.navigatable.route to modalCtx)
                            !isModal && !step.shouldDismissModals && simulatedCurrentEntry.isModal && simulatedModalContexts.isNotEmpty() -> {
                                val modalRoute = simulatedCurrentEntry.navigatable.route
                                val ctx = simulatedModalContexts[modalRoute]
                                if (ctx != null) {
                                    val underlying = ctx.originalUnderlyingScreenEntry.navigatable.route
                                    mapOf(underlying to ctx.copy(navigatedAwayToRoute = entry.navigatable.route))
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
                    val entry = createNavigationEntry(step, resolution, simulatedBackStack.size)
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
                    // When a Navigate preceded this PopUpTo, exclude the just-navigated entry
                    // from the search backstack. Without this, findRouteInBackStack (indexOfLast)
                    // would find the freshly-added entry at the tail when the same screen already
                    // exists earlier in the stack, causing popUpTo to silently collapse to a no-op.
                    val popUpToBackStack = if (lastNavigatedEntry != null) {
                        simulatedBackStack.filter { it !== lastNavigatedEntry }
                    } else {
                        simulatedBackStack
                    }
                    val result = applyPopUpToStep(
                        step, simulatedCurrentEntry, popUpToBackStack, simulatedModalContexts,
                        hasNavigationOperations = lastNavigatedEntry != null
                    )

                    val navigated = lastNavigatedEntry
                    val finalCurrentEntry: NavigationEntry
                    val finalBackStack: List<NavigationEntry>

                    if (navigated != null) {
                        val navigatedInTrimmed = result.backStack.any {
                            it.navigatable.route == navigated.navigatable.route &&
                                    it.graphId == navigated.graphId
                        }
                        if (navigatedInTrimmed) {
                            finalCurrentEntry = result.currentEntry
                            finalBackStack = result.backStack
                        } else {
                            val pos = result.backStack.size + 1
                            val repositioned = navigated.copy(stackPosition = pos)
                            finalCurrentEntry = repositioned
                            finalBackStack = result.backStack + repositioned
                        }
                    } else {
                        finalCurrentEntry = result.currentEntry
                        finalBackStack = result.backStack
                    }

                    batchedActions.add(
                        NavigationAction.PopUpTo(finalCurrentEntry, finalBackStack, result.modalContexts)
                    )
                    simulatedCurrentEntry = finalCurrentEntry
                    simulatedBackStack = finalBackStack
                    simulatedModalContexts = result.modalContexts
                    lastNavigatedEntry = null
                }
            }
        }

        val allActions = prefixActions + batchedActions + suffixActions
        when {
            allActions.isEmpty() -> return
            allActions.size == 1 -> storeAccessor.dispatchAndAwait(allActions[0])
            else -> storeAccessor.dispatchAndAwait(NavigationAction.AtomicBatch(allActions))
        }

        val lastNavigatedRoute = batchedActions
            .filterIsInstance<NavigationAction.Navigate>()
            .lastOrNull()?.entry?.navigatable
        val enterMs = lastNavigatedRoute?.enterTransition?.durationMillis?.toLong() ?: 0L
        val exitMs = navigationStartEntry.navigatable.exitTransition.durationMillis.toLong()
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
            try {
                val lifecycleJob = SupervisorJob(storeAccessor.coroutineContext[Job])
                val lifecycleScope = CoroutineScope(storeAccessor.coroutineContext + lifecycleJob)
                entryLifecycleJobs[entry.stableKey] = lifecycleJob

                val lifecycle = BackstackLifecycle(entry, navigationStateFlow, storeAccessor, lifecycleScope)
                entryLifecycles[entry.stableKey] = lifecycle
                entry.navigatable.onLifecycleCreated(lifecycle)
            } catch (e: Exception) {
                ReaktivDebug.warn("Warning: onLifecycle failed for ${entry.navigatable.route}: ${e.message}")
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
        stackPosition: Int
    ): NavigationEntry {
        val encodedParams = step.params
        val mergedParams = resolution.extractedParams + encodedParams

        return resolution.targetNavigatable.toNavigationEntry(
            params = mergedParams,
            graphId = resolution.getEffectiveGraphId(),
            stackPosition = stackPosition
        )
    }

    private fun buildModalContext(
        entry: NavigationEntry,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        activeModalContexts: Map<String, ModalContext>
    ): ModalContext? {
        val underlying = if (currentEntry.isModal)
            activeModalContexts.values.firstOrNull()?.originalUnderlyingScreenEntry
                ?: findUnderlyingScreenForModal(currentEntry, backStack)
        else currentEntry
        return underlying?.let {
            ModalContext(
                modalEntry = entry,
                originalUnderlyingScreenEntry = it,
                createdFromScreenRoute = it.navigatable.route
            )
        }
    }

    private fun findUnderlyingScreenForModal(
        modalEntry: NavigationEntry,
        backStack: List<NavigationEntry>
    ): NavigationEntry? {
        val modalIndex = backStack.indexOf(modalEntry)
        if (modalIndex <= 0) return null

        return backStack.subList(0, modalIndex).lastOrNull { it.isScreen }
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

    private data class NavigationStepResult(
        val currentEntry: NavigationEntry,
        val backStack: List<NavigationEntry>,
        val modalContexts: Map<String, ModalContext>
    )

    private suspend fun applyPopUpToStep(
        step: NavigationStep,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        modalContexts: Map<String, ModalContext>,
        allOperations: List<NavigationStep> = emptyList(),
        hasNavigationOperations: Boolean = allOperations.any {
            it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
        }
    ): NavigationStepResult {
        val popUpToRoute = step.popUpToTarget?.resolve(precomputedData)
            ?: throw IllegalStateException("PopUpTo operation requires a popUpTo target")
        var popIndex = precomputedData.routeResolver.findRouteInBackStack(
            targetRoute = popUpToRoute,
            backStack = backStack
        )

        if (popIndex < 0 && step.popUpToFallback != null) {
            val fallbackRoute = step.popUpToFallback.resolve(precomputedData)
            popIndex = precomputedData.routeResolver.findRouteInBackStack(
                targetRoute = fallbackRoute,
                backStack = backStack
            )

            if (popIndex < 0) {
                val resolution =
                    precomputedData.routeResolver.resolve(fallbackRoute, precomputedData.availableNavigatables)
                        ?: throw RouteNotFoundException("Fallback route not found: $fallbackRoute")

                val newEntry = createNavigationEntry(
                    step.copy(target = step.popUpToFallback),
                    resolution,
                    stackPosition = 1
                )

                return NavigationStepResult(
                    currentEntry = newEntry,
                    backStack = listOf(newEntry),
                    modalContexts = emptyMap()
                )
            }
        }

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
                stackPosition = newBackStackAfterPop.size + 1
            )

            NavigationStepResult(
                currentEntry = newEntry,
                backStack = newBackStackAfterPop + newEntry,
                modalContexts = modalContexts
            )
        } else {
            if (hasNavigationOperations) {
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
                if (newBackStackAfterPop.isEmpty()) {
                    if (step.popUpToInclusive) {
                        if (hasNavigationOperations) {
                            return NavigationStepResult(
                                currentEntry = currentEntry,
                                backStack = emptyList(),
                                modalContexts = emptyMap()
                            )
                        } else {
                            throw IllegalStateException("Cannot pop up to route with inclusive=true that would result in empty back stack")
                        }
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
}
