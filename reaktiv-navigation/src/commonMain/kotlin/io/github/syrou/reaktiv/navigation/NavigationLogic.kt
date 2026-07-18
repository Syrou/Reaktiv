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
import io.github.syrou.reaktiv.navigation.definition.StartDestination
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
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.popExitSpec
import io.github.syrou.reaktiv.navigation.util.NavigationStackMath
import io.github.syrou.reaktiv.navigation.util.StackSnapshot
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

private object NavigationLockKey : CoroutineContext.Key<NavigationLockMarker>

private class NavigationLockMarker : AbstractCoroutineContextElement(NavigationLockKey)

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
public class NavigationLogic(
    public val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder(),
    private val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
) : ModuleLogic() {

    private val bootstrapCompleted = CompletableDeferred<Unit>()
    private val navigationMutex = Mutex()
    private val deepLinkStartedBeforeBootstrap = MutableStateFlow(false)

    private val entryLifecycleJobs = mutableMapOf<String, Job>()
    private val entryLifecycles = mutableMapOf<String, BackstackLifecycle>()

    init {
        registerCrashListenerIfNeeded()
        bootstrapRootEntryIfNeeded()
    }

    private fun bootstrapRootEntryIfNeeded() {
        val rootEntryDef = precomputedData.graphEntries["root"]

        val rootStartDest = precomputedData.graphDefinitions["root"]?.startDestination
        val graphRefEntryDef = if (rootEntryDef == null && rootStartDest is StartDestination.GraphReference) {
            precomputedData.graphEntries[rootStartDest.graphId]?.takeIf { it.route != null }
        } else null
        val graphRefId = if (graphRefEntryDef != null) {
            (rootStartDest as StartDestination.GraphReference).graphId
        } else null

        val bootstrapEntry = rootEntryDef ?: graphRefEntryDef
        val bootstrapGraphId = if (rootEntryDef != null) "root" else graphRefId

        if (bootstrapEntry?.route == null) {
            storeAccessor.launch {
                storeAccessor.dispatchAndAwait(NavigationAction.BootstrapComplete)
                bootstrapCompleted.complete(Unit)
            }
            return
        }

        storeAccessor.launch {
            try {
                val selectedNode = bootstrapEntry.route.invoke(storeAccessor)

                if (!deepLinkStartedBeforeBootstrap.value) {
                    val routeBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                    routeBuilder.clearBackStack()
                    val resolvedBootstrapNode = resolveEntryChain(selectedNode, bootstrapGraphId ?: "root")

                    val resolvedPath = resolvedBootstrapNode.fullPathOrRoute()
                    val resolvedResolution = precomputedData.routeResolver.resolve(resolvedPath)
                    val bootstrapStep = NavigationStep(NavigationOperation.Navigate)
                    val currentState = getCurrentNavigationState()

                    when (val guard = evaluateGuard(resolvedPath, resolvedResolution, bootstrapStep, currentState)) {
                        is GuardEvaluation.PendAndRedirect -> {
                            storeAccessor.dispatchAndAwait(NavigationAction.SetPendingNavigation(guard.pending))
                            routeBuilder.navigateTo(guard.redirectRoute)
                        }
                        is GuardEvaluation.Redirect -> {
                            routeBuilder.navigateTo(guard.route)
                        }
                        is GuardEvaluation.Reject -> {
                            val fallback = precomputedData.notFoundScreen
                            if (fallback != null) routeBuilder.navigateTo(fallback)
                            else routeBuilder.navigateToNode(resolvedBootstrapNode)
                        }
                        is GuardEvaluation.Allow, null -> routeBuilder.navigateToNode(resolvedBootstrapNode)
                    }

                    routeBuilder.validate()
                    executeNavigation(routeBuilder) { it + listOf(NavigationAction.BootstrapComplete) }
                }
            } finally {
                bootstrapCompleted.complete(Unit)
                if (getCurrentNavigationState().isEvaluatingNavigation) {
                    storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
                }
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
            val crashEntry = crashScreenDef.toNavigationEntry(
                path = crashScreenDef.fullPathOrRoute(),
                params = crashParams
            )
            storeAccessor.dispatch(
                NavigationAction.Navigate(
                    entry = crashEntry,
                    modalContext = null,
                    dismissModals = false
                )
            )
        } catch (e: Exception) {
            ReaktivDebug.error("NavigationLogic: Failed to navigate to crash screen - ${e.message}", e)
        }
    }

    internal suspend fun syncLifecycle(newBackStack: List<NavigationEntry>) {
        invokeLifecycleCallbacks(newBackStack)
    }

    public suspend fun adoptCurrentBackstack() {
        syncLifecycle(storeAccessor.selectState<NavigationState>().first().backStack)
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
    public suspend fun navigate(block: suspend NavigationBuilder.() -> Unit): NavigationOutcome {
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.apply { block() }
        builder.validate()
        val primaryStep = builder.operations.firstOrNull {
            it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
        }
        val targetRoute = primaryStep?.let {
            try { it.target?.resolve(precomputedData) } catch (e: Exception) { null }
        }
        val targetResolution = targetRoute?.let {
            precomputedData.routeResolver.resolve(it, precomputedData.availableNavigatables)
        }
        val isSystemLayer = targetResolution?.targetNavigatable?.renderLayer == RenderLayer.SYSTEM
        if (!isSystemLayer) {
            bootstrapCompleted.await()
        }
        return evaluateAndExecute(builder, targetRoute, targetResolution)
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
        targetResolution: RouteResolution?,
        primaryStep: NavigationStep,
        currentState: NavigationState
    ): GuardEvaluation? {
        val targetGraphId = targetResolution?.navigationGraphId
        val targetActualGraphId = targetResolution?.targetGraphId
        val interceptDef = precomputedData.interceptedRoutes[targetRoute]
            ?: targetGraphId?.let { precomputedData.interceptedRoutes[it] }
            ?: targetActualGraphId?.let { precomputedData.interceptedRoutes[it] }
            ?: return null

        fun GuardResult.toGuardEvaluation(): GuardEvaluation = when (this) {
            is GuardResult.Allow -> GuardEvaluation.Allow
            is GuardResult.Reject -> GuardEvaluation.Reject
            is GuardResult.RedirectTo -> GuardEvaluation.Redirect(route)
            is GuardResult.PendAndRedirectTo -> {
                val pending = PendingNavigation(
                    route = targetRoute,
                    params = primaryStep.params,
                    metadata = metadata,
                    displayHint = displayHint
                )
                val redirectResolution = precomputedData.routeResolver.resolve(
                    route, precomputedData.availableNavigatables
                )
                val redirectPath = redirectResolution?.targetNavigatable?.let {
                    precomputedData.navigatableToFullPath[it]
                }
                GuardEvaluation.PendAndRedirect(
                    pending = pending,
                    redirectRoute = route,
                    alreadyAtRedirect = redirectPath == currentState.currentEntry.path
                )
            }
        }

        val isAlreadyInZone = currentState.backStack.any { entry ->
            precomputedData.interceptedRoutes[entry.path] === interceptDef
        }
        if (isAlreadyInZone) return GuardEvaluation.Allow

        for ((outerGuard, outerThreshold) in interceptDef.outerGuards) {
            val result = evaluateWithThreshold(outerThreshold) { outerGuard(storeAccessor) }
            val evaluation = result.toGuardEvaluation()
            if (evaluation != GuardEvaluation.Allow) return evaluation
        }

        return evaluateWithThreshold(interceptDef.loadingThreshold) {
            interceptDef.guard(storeAccessor)
        }.toGuardEvaluation()
    }

    private suspend fun resolveEntryChain(initialNode: NavigationNode, initialRoute: String): NavigationNode {
        if (initialNode is Navigatable) return initialNode
        var resolvedNode: NavigationNode = initialNode
        val visitedRoutes = mutableSetOf(initialRoute)
        while (resolvedNode !is Navigatable) {
            val nextRoute = resolvedNode.route
            if (!visitedRoutes.add(nextRoute)) break
            resolvedNode = resolveEntryNavigatable(nextRoute) ?: break
        }
        return resolvedNode
    }

    private suspend fun resolveEntryNavigatable(targetRoute: String): NavigationNode? {
        precomputedData.graphDefinitions[targetRoute] ?: return null
        val entryDef = precomputedData.graphEntries[targetRoute] ?: return null
        if (entryDef.route == null) return null
        return evaluateWithThreshold(
            loadingThreshold = entryDef.loadingThreshold
        ) { entryDef.route.invoke(storeAccessor) }
    }

    private suspend fun resolveGraphEntryForSynthesis(
        graphPath: String,
        simulatedBackStack: List<NavigationEntry>,
        visited: Set<String> = emptySet()
    ): NavigationEntry? {
        if (graphPath in visited) return null

        val static = precomputedData.routeResolver.resolveForBackstackSynthesis(graphPath)
        if (static != null) {
            return static.targetNavigatable.toNavigationEntry(
                path = static.targetNavigatable.fullPathOrRoute(),
                params = static.extractedParams
            )
        }

        val directEntryDef = precomputedData.graphEntries[graphPath]
        val entryDef: EntryDefinition
        val effectiveGraphPath: String
        if (directEntryDef == null) {
            val startDest = precomputedData.graphDefinitions[graphPath]?.startDestination
            if (startDest is StartDestination.GraphReference) {
                effectiveGraphPath = startDest.graphId
                entryDef = precomputedData.graphEntries[startDest.graphId] ?: return null
            } else {
                return null
            }
        } else {
            effectiveGraphPath = graphPath
            entryDef = directEntryDef
        }

        if (entryDef.route == null) return null

        val existingInSimulated = simulatedBackStack.firstOrNull { entry ->
            precomputedData.navigatableToGraph[entry.navigatable] == effectiveGraphPath
        }
        if (existingInSimulated != null) return existingInSimulated

        if (simulatedBackStack.isNotEmpty() || visited.isNotEmpty()) {
            val currentState = getCurrentNavigationState()
            val existingEntry = currentState.backStack.firstOrNull { entry ->
                precomputedData.navigatableToGraph[entry.navigatable] == effectiveGraphPath
            }
            if (existingEntry != null) return existingEntry
        }

        val node = evaluateWithThreshold(entryDef.loadingThreshold) { entryDef.route.invoke(storeAccessor) }
        return when {
            node is Navigatable ->
                node.toNavigationEntry(path = node.fullPathOrRoute(), params = Params.empty())
            precomputedData.graphDefinitions.containsKey(node.route) ->
                resolveGraphEntryForSynthesis(node.route, simulatedBackStack, visited + graphPath)
            else -> {
                val resolution = precomputedData.routeResolver.resolve(node.route) ?: return null
                resolution.targetNavigatable.toNavigationEntry(
                    path = resolution.targetNavigatable.fullPathOrRoute(),
                    params = resolution.extractedParams
                )
            }
        }
    }

    private suspend fun navigateDirect(route: String) {
        val builder = NavigationBuilder(storeAccessor, parameterEncoder)
        builder.navigateTo(route)
        builder.validate()
        executeNavigation(builder)
    }

    private fun NavigationNode.fullPathOrRoute(): String =
        if (this is Navigatable) precomputedData.navigatableToFullPath[this] ?: route else route

    private fun NavigationBuilder.navigateToNode(node: NavigationNode) {
        if (node is Navigatable) navigateTo(node) else navigateTo(node.route)
    }

    private suspend fun guardOutcome(guard: GuardEvaluation?): NavigationOutcome? = when (guard) {
        is GuardEvaluation.Reject -> NavigationOutcome.Rejected
        is GuardEvaluation.Redirect -> {
            navigateDirect(guard.route)
            NavigationOutcome.Redirected(guard.route)
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
            NavigationOutcome.Redirected(guard.redirectRoute)
        }
        is GuardEvaluation.Allow, null -> null
    }

    private suspend fun synthesizeAncestorEntries(
        route: String,
        simulatedBackStack: List<NavigationEntry>,
        seenPaths: MutableSet<String>,
        includeRoot: Boolean
    ): List<NavigationEntry> {
        val synthesized = mutableListOf<NavigationEntry>()
        var stack = simulatedBackStack
        if (includeRoot) {
            val rootEntry = resolveGraphEntryForSynthesis("root", stack)
            if (rootEntry != null && seenPaths.add(rootEntry.path)) {
                synthesized.add(rootEntry)
                stack = stack + rootEntry
            }
        }
        for (intermediatePath in precomputedData.routeResolver.buildPathHierarchy(route).dropLast(1)) {
            val entry = resolveGraphEntryForSynthesis(intermediatePath, stack) ?: continue
            if (!seenPaths.add(entry.path)) continue
            synthesized.add(entry)
            stack = stack + entry
        }
        return synthesized
    }

    /**
     * Evaluate intercept guards and entry definitions for the given builder, then execute
     * the navigation. Runs inside [NonCancellable] so that guard evaluation and state
     * commits are never partially cancelled.
     *
     * Navigations are serialized: a call issued while another navigation is in progress
     * suspends until the in-flight one completes, then executes. Re-entrant calls made
     * from inside an in-flight navigation (e.g. a guard navigating) execute inline.
     */
    private suspend fun evaluateAndExecute(
        builder: NavigationBuilder,
        precomputedTargetRoute: String? = null,
        precomputedTargetResolution: RouteResolution? = null
    ): NavigationOutcome {
        if (currentCoroutineContext()[NavigationLockKey] != null) {
            return performEvaluateAndExecute(builder, precomputedTargetRoute, precomputedTargetResolution)
        }
        navigationMutex.lock()
        try {
            return withContext(NavigationLockMarker()) {
                performEvaluateAndExecute(builder, precomputedTargetRoute, precomputedTargetResolution)
            }
        } finally {
            navigationMutex.unlock()
        }
    }

    private suspend fun performEvaluateAndExecute(
        builder: NavigationBuilder,
        precomputedTargetRoute: String? = null,
        precomputedTargetResolution: RouteResolution? = null
    ): NavigationOutcome {
        return withContext(NonCancellable) {
                try {
                    val primaryStep = builder.operations.firstOrNull {
                        it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
                    }

                    if (primaryStep == null) {
                        executeNavigation(builder)
                        return@withContext NavigationOutcome.Success
                    }

                    val targetRoute = precomputedTargetRoute ?: try {
                        primaryStep.target?.resolve(precomputedData)
                    } catch (e: Exception) {
                        null
                    }

                    if (targetRoute == null) {
                        executeNavigation(builder)
                        return@withContext NavigationOutcome.Success
                    }

                    val targetResolution = precomputedTargetResolution
                        ?: precomputedData.routeResolver.resolve(targetRoute)

                    val currentState = getCurrentNavigationState()

                    val initialGuard = evaluateGuard(targetRoute, targetResolution, primaryStep, currentState)
                    guardOutcome(initialGuard)?.let { return@withContext it }

                    val isDynamicGraphTarget = precomputedData.graphEntries[targetRoute]?.route != null
                    val entryNode: NavigationNode? = if (isDynamicGraphTarget) {
                        val existingEntry = currentState.backStack.firstOrNull { entry ->
                            precomputedData.navigatableToGraph[entry.navigatable] == targetRoute
                        }
                        if (existingEntry != null) {
                            existingEntry.navigatable
                        } else {
                            resolveEntryNavigatable(targetRoute)
                        }
                    } else {
                        resolveEntryNavigatable(targetRoute)
                    }
                    if (entryNode != null) {
                        val resolvedNode = resolveEntryChain(entryNode, targetRoute)
                        val resolvedResolution = if (resolvedNode is Navigatable) {
                            RouteResolution(
                                targetNavigatable = resolvedNode,
                                targetGraphId = precomputedData.navigatableToGraph[resolvedNode] ?: "root",
                                extractedParams = Params.empty()
                            )
                        } else {
                            precomputedData.routeResolver.resolve(
                                resolvedNode.route, precomputedData.availableNavigatables
                            )
                        }

                        if (initialGuard == null) {
                            val resolvedRoute = resolvedNode.fullPathOrRoute()
                            val stateAfterResolution = getCurrentNavigationState()
                            val resolvedGuard = evaluateGuard(resolvedRoute, resolvedResolution, primaryStep, stateAfterResolution)
                            guardOutcome(resolvedGuard)?.let { return@withContext it }
                        }

                        val routeBuilder = NavigationBuilder(storeAccessor, parameterEncoder)
                        val primaryStepIndex = builder.operations.indexOf(primaryStep)
                        builder.operations.subList(0, primaryStepIndex)
                            .forEach { routeBuilder.operations.add(it) }
                        if (primaryStep.params.isNotEmpty()) routeBuilder.params(primaryStep.params)
                        routeBuilder.navigateToNode(resolvedNode)
                        val lastIdx = routeBuilder.operations.lastIndex
                        routeBuilder.operations[lastIdx] = routeBuilder.operations[lastIdx].copy(
                            shouldDismissModals = primaryStep.shouldDismissModals,
                            synthesizeBackstack = primaryStep.synthesizeBackstack
                        )
                        builder.operations.subList(primaryStepIndex + 1, builder.operations.size)
                            .forEach { routeBuilder.operations.add(it) }
                        routeBuilder.validate()
                        executeNavigation(routeBuilder, primaryResolution = resolvedResolution)
                        return@withContext NavigationOutcome.Success
                    }

                    executeNavigation(builder, primaryResolution = targetResolution)
                    NavigationOutcome.Success
                } finally {
                    if (getCurrentNavigationState().isEvaluatingNavigation) {
                        storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
                    }
                }
        }
    }

    /**
     * Evaluate a suspend block, showing the global [LoadingModal] as a boolean overlay if
     * evaluation takes longer than [loadingThreshold].
     *
     * Sets [NavigationState.isEvaluatingNavigation] to `true` rather than pushing a
     * backstack entry. Cleanup is handled by the [evaluateAndExecute] finally block via
     * [NavigationAction.SetEvaluating].
     */
    private suspend fun <T> evaluateWithThreshold(
        loadingThreshold: Duration,
        evaluate: suspend () -> T
    ): T = coroutineScope {
        val deferred = async { evaluate() }
        val completedInTime = withTimeoutOrNull(loadingThreshold) {
            deferred.await()
            true
        } ?: false
        if (!completedInTime) {
            if (precomputedData.loadingModal != null) {
                storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(true))
            }
        }
        deferred.await()
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
    public suspend fun navigate(
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
     * No-op while [NavigationState.isEvaluatingNavigation] is true or a [LoadingModal] is
     * the current entry, because back navigation during async guard/entry evaluation would
     * corrupt state: this call bypasses the navigation mutex and the in-flight evaluation
     * has already captured a state snapshot it is about to commit against.
     *
     * Dispatches [NavigationAction.Back] directly, bypassing the navigation mutex.
     * This is intentional: a back/dismiss requires no guard evaluation, and the mutex
     * may be held while a loading modal is showing (e.g. during guard evaluation).
     * Routing through [evaluateAndExecute] would needlessly serialize the dismiss
     * behind the in-flight evaluation.
     */
    public suspend fun navigateBack() {
        val currentState = getCurrentNavigationState()
        if (!currentState.canGoBack) return
        if (currentState.isEvaluatingNavigation) return
        if (currentState.currentEntry.navigatable is LoadingModal) return
        storeAccessor.dispatchAndAwait(NavigationAction.Back)
    }

    /**
     * Pop up to a specific route in the backstack.
     *
     * @param route Target route to pop back to
     * @param inclusive If true, also removes the target route from backstack
     * @param fallback Optional fallback route if the target route is not found
     */
    public suspend fun popUpTo(route: String, inclusive: Boolean = false, fallback: String? = null) {
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
    public suspend fun navigateDeepLink(route: String, params: Params = Params.empty()) {
        val (cleanRoute, queryParams) = parseUrlWithQueryParams(route)

        var pathParams = Params.empty()
        val alias = precomputedData.deepLinkAliases.firstOrNull { alias ->
            alias.matchAndExtract(cleanRoute)?.also { pathParams = it } != null
        }

        val targetRoute: String
        val targetParams: Params
        if (alias != null) {
            targetRoute = alias.targetRoute
            targetParams = alias.paramsMapping(Params.fromMap(queryParams) + pathParams + params)
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
    public suspend fun clearBackStack(newRoute: String? = null, params: Params = Params.empty()) {
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

    private suspend fun executeNavigation(
        builder: NavigationBuilder,
        primaryResolution: RouteResolution? = null,
        wrapActions: (List<NavigationAction>) -> List<NavigationAction> = { it }
    ) {
        val initialState = getCurrentNavigationState()
        var sim = StackSnapshot(
            currentEntry = initialState.currentEntry,
            backStack = initialState.backStack,
            modalContexts = initialState.activeModalContexts
        )
        val navigationStartEntry = sim.currentEntry
        var lastNavigatedEntry: NavigationEntry? = null

        val batchedActions = mutableListOf<NavigationAction>()
        var primaryResolutionConsumed = false

        for (step in builder.operations) {
            when (step.operation) {
                NavigationOperation.Navigate -> {
                    val resolvedRoute = step.target?.resolve(precomputedData)
                        ?: throw IllegalStateException("Navigate requires a target")
                    val resolution = if (!primaryResolutionConsumed && primaryResolution != null) {
                        primaryResolutionConsumed = true
                        primaryResolution
                    } else {
                        precomputedData.routeResolver.resolve(
                            resolvedRoute, precomputedData.availableNavigatables
                        ) ?: throw RouteNotFoundException("Route not found: $resolvedRoute")
                    }

                    if (step.synthesizeBackstack) {
                        val destinationPath = resolution.targetNavigatable.fullPathOrRoute()
                        val seenPaths = (sim.backStack.map { it.path } + destinationPath).toMutableSet()

                        for (entry in synthesizeAncestorEntries(resolvedRoute, sim.backStack, seenPaths, includeRoot = true)) {
                            batchedActions.add(NavigationAction.Navigate(entry))
                            sim = NavigationStackMath.applyNavigate(sim, entry, null, false)
                            lastNavigatedEntry = entry
                        }

                        val finalEntry = createNavigationEntry(step, resolution, destinationPath, 0)
                        batchedActions.add(NavigationAction.Navigate(finalEntry, dismissModals = step.shouldDismissModals))
                        sim = NavigationStackMath.applyNavigate(sim, finalEntry, null, step.shouldDismissModals)
                        lastNavigatedEntry = finalEntry
                    } else {
                        val entryPath = resolution.targetNavigatable.fullPathOrRoute()
                        val entry = createNavigationEntry(step, resolution, entryPath, 0)
                        if (sim.backStack.isNotEmpty() && entry.path == sim.currentEntry.path) {
                            continue
                        }
                        val isModal = entry.navigatable is Modal
                        val modalCtx = if (isModal) buildModalContext(
                            entry, sim.currentEntry, sim.backStack, sim.modalContexts
                        ) else null
                        batchedActions.add(NavigationAction.Navigate(entry, modalCtx, step.shouldDismissModals))
                        sim = NavigationStackMath.applyNavigate(sim, entry, modalCtx, step.shouldDismissModals)
                        lastNavigatedEntry = entry
                    }
                }

                NavigationOperation.Replace -> {
                    val resolvedRoute = step.target?.resolve(precomputedData)
                        ?: throw IllegalStateException("Replace requires a target")
                    val resolution = if (!primaryResolutionConsumed && primaryResolution != null) {
                        primaryResolutionConsumed = true
                        primaryResolution
                    } else {
                        precomputedData.routeResolver.resolve(
                            resolvedRoute, precomputedData.availableNavigatables
                        ) ?: throw RouteNotFoundException("Route not found: $resolvedRoute")
                    }
                    val entryPath = resolution.targetNavigatable.fullPathOrRoute()
                    val entry = createNavigationEntry(step, resolution, entryPath, sim.backStack.size)
                    batchedActions.add(NavigationAction.Replace(entry))
                    sim = NavigationStackMath.applyReplace(sim, entry)
                    lastNavigatedEntry = entry
                }

                NavigationOperation.Back -> {
                    batchedActions.add(NavigationAction.Back)
                    sim = NavigationStackMath.applyBack(sim)
                    lastNavigatedEntry = null
                }

                NavigationOperation.ClearBackStack -> {
                    batchedActions.add(NavigationAction.ClearBackstack)
                    sim = NavigationStackMath.applyClearBackstack(sim)
                    lastNavigatedEntry = null
                }

                NavigationOperation.ResumePending -> {
                    val pending = initialState.pendingNavigation ?: continue
                    batchedActions.add(NavigationAction.ClearPendingNavigation)

                    val pendingResolution = precomputedData.routeResolver.resolve(
                        pending.route, precomputedData.availableNavigatables
                    ) ?: continue

                    val destinationPath = pendingResolution.targetNavigatable.fullPathOrRoute()
                    val seenPaths = (sim.backStack.map { it.path } + destinationPath).toMutableSet()

                    for (entry in synthesizeAncestorEntries(
                        pending.route, sim.backStack, seenPaths,
                        includeRoot = sim.backStack.isEmpty()
                    )) {
                        batchedActions.add(NavigationAction.Navigate(entry))
                        sim = NavigationStackMath.applyNavigate(sim, entry, null, false)
                        lastNavigatedEntry = entry
                    }

                    val finalEntry = pendingResolution.targetNavigatable.toNavigationEntry(
                        path = destinationPath,
                        params = pendingResolution.extractedParams + pending.params
                    )
                    batchedActions.add(NavigationAction.Navigate(finalEntry))
                    sim = NavigationStackMath.applyNavigate(sim, finalEntry, null, false)
                    lastNavigatedEntry = finalEntry
                }

                NavigationOperation.PopUpTo -> {
                    val resolvedRoute = step.popUpToTarget?.resolve(precomputedData)
                        ?: throw IllegalStateException("PopUpTo operation requires a popUpTo target")

                    val targetIndex = precomputedData.routeResolver.findRouteInBackStack(
                        resolvedRoute, sim.backStack
                    )

                    if (targetIndex < 0) {
                        if (step.popUpToFallback != null) {
                            val fallbackRoute = step.popUpToFallback.resolve(precomputedData)
                            val resolution = precomputedData.routeResolver.resolve(
                                fallbackRoute, precomputedData.availableNavigatables
                            ) ?: throw RouteNotFoundException("Fallback route not found: $fallbackRoute")
                            val fallbackPath = resolution.targetNavigatable.fullPathOrRoute()
                            val newEntry = createNavigationEntry(
                                step.copy(target = step.popUpToFallback),
                                resolution,
                                fallbackPath,
                                stackPosition = 1
                            )
                            batchedActions.add(NavigationAction.ClearBackstack)
                            sim = NavigationStackMath.applyClearBackstack(sim)
                            batchedActions.add(NavigationAction.Navigate(newEntry))
                            sim = NavigationStackMath.applyNavigate(sim, newEntry, null, false)
                            lastNavigatedEntry = newEntry
                        } else {
                            throw RouteNotFoundException("No match found for route $resolvedRoute")
                        }
                    } else {
                        val trimmedBackStack = if (step.popUpToInclusive) {
                            sim.backStack.take(targetIndex)
                        } else {
                            sim.backStack.take(targetIndex + 1)
                        }

                        val toReAdd = lastNavigatedEntry
                        val entryToReAdd = if (toReAdd != null &&
                            trimmedBackStack.none { it.path == toReAdd.path }) {
                            toReAdd
                        } else null

                        val wouldBeEmpty = trimmedBackStack.isEmpty() && entryToReAdd == null
                        if (wouldBeEmpty) {
                            throw IllegalStateException(
                                "PopUpTo with inclusive=true on route '$resolvedRoute' would result in an empty back stack. " +
                                "Either use inclusive=false, or navigate to a new destination before calling popUpTo."
                            )
                        }

                        batchedActions.add(NavigationAction.PopUpTo(resolvedRoute, step.popUpToInclusive, entryToReAdd))
                        sim = NavigationStackMath.applyPopUpTo(sim, targetIndex, step.popUpToInclusive, entryToReAdd)
                        lastNavigatedEntry = null
                    }
                }
            }
        }

        val allActions = wrapActions(batchedActions)
        when {
            allActions.isEmpty() -> return
            allActions.size == 1 -> storeAccessor.dispatchAndAwait(allActions[0])
            else -> storeAccessor.dispatchAndAwait(NavigationAction.AtomicBatch(allActions))
        }

        val lastNavigatedNavEntry = batchedActions
            .filterIsInstance<NavigationAction.Navigate>()
            .lastOrNull()?.entry
        val lastNavigatedNavigatable = lastNavigatedNavEntry?.navigatable
        val enterMs = lastNavigatedNavigatable?.enterTransition?.durationMillis?.toLong() ?: 0L
        val exitMs = navigationStartEntry.navigatable.exitTransition.durationMillis.toLong()
        val animMs = maxOf(enterMs, exitMs)
        if (animMs > 0L) delay(animMs)
    }

    /**
     * Invokes lifecycle callbacks for entries that were added or removed from the backstack.
     */
    private suspend fun invokeLifecycleCallbacks(newBackStack: List<NavigationEntry>) {
        val newKeys = newBackStack.map { it.stableKey }.toSet()

        val addedEntries = newBackStack.filter { it.stableKey !in entryLifecycles }
        val removedLifecycles = entryLifecycles.filterKeys { it !in newKeys }

        val navigationStateFlow = storeAccessor.selectState<NavigationState>()

        addedEntries.forEach { entry ->
            val navigatable = entry.navigatable
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

        removedLifecycles.forEach { (key, lifecycle) ->
            entryLifecycles.remove(key)
            val lifecycleJob = entryLifecycleJobs.remove(key)
            val exitMs = popExitSpec(lifecycle.entry.navigatable)?.transition?.durationMillis?.toLong() ?: 0L
            if (exitMs <= 0L) {
                lifecycle.runRemovalHandlers(RemovalReason.NAVIGATION)
                lifecycleJob?.cancel()
            } else {
                storeAccessor.launch {
                    delay(exitMs)
                    lifecycle.runRemovalHandlers(RemovalReason.NAVIGATION)
                    lifecycleJob?.cancel()
                }
            }
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
        return resolution.targetNavigatable.toNavigationEntry(
            path = path,
            params = resolution.extractedParams + step.params,
            stackPosition = stackPosition
        )
    }

    private fun buildModalContext(
        entry: NavigationEntry,
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        activeModalContexts: Map<String, ModalContext>
    ): ModalContext? {
        val underlying = if (currentEntry.navigatable is Modal)
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
            it.navigatable is Screen
        }
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

}
