package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.CrashListener
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.DispatchResult
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.HydrateSource
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.history.BrowserHistorySetup
import io.github.syrou.reaktiv.navigation.history.ExternalLocation
import io.github.syrou.reaktiv.navigation.history.ExternalOutcome
import io.github.syrou.reaktiv.navigation.history.HistorySync
import io.github.syrou.reaktiv.navigation.history.LocationCodec
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision
import io.github.syrou.reaktiv.navigation.util.impliesBackNavigation
import io.github.syrou.reaktiv.navigation.util.lastStackChange
import io.github.syrou.reaktiv.navigation.util.animatesInto
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.definition.RemovalReason
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationOperation
import io.github.syrou.reaktiv.navigation.dsl.NavigationStep
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.MissingPathParamsException
import io.github.syrou.reaktiv.navigation.exception.PopUpToTargetNotInBackStackException
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.CacheKeySelector
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.model.StartFailure
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.RouteSelector
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.modalExitSpec
import io.github.syrou.reaktiv.navigation.transition.popExitSpec
import io.github.syrou.reaktiv.navigation.util.NavigationStackMath
import io.github.syrou.reaktiv.navigation.util.RouteTemplate
import io.github.syrou.reaktiv.navigation.util.StackSnapshot
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import io.github.syrou.reaktiv.navigation.util.traceEntrySelection
import io.github.syrou.reaktiv.navigation.util.traceGuard
import io.github.syrou.reaktiv.navigation.util.traceNavigation
import io.github.syrou.reaktiv.navigation.util.traceTraverse
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import io.github.syrou.reaktiv.navigation.util.ROOT_GRAPH
import io.github.syrou.reaktiv.navigation.util.normalizePath

private object NavigationLockKey : CoroutineContext.Key<NavigationLockMarker>

private class NavigationLockMarker : AbstractCoroutineContextElement(NavigationLockKey)

private object EvaluationOverlayKey : CoroutineContext.Key<EvaluationOverlay>

private class EvaluationOverlay : AbstractCoroutineContextElement(EvaluationOverlayKey) {
    var raised: Boolean = false
}

/**
 * Side-effecting logic for the navigation system.
 *
 * `NavigationLogic` orchestrates all navigation operations: guard evaluation, entry-point
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
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

public class NavigationLogic internal constructor(
    public val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    private val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)?,
    browserHistory: BrowserHistorySetup?
) : ModuleLogic() {

    public constructor(
        storeAccessor: StoreAccessor,
        precomputedData: PrecomputedNavigationData,
        @Suppress("UNUSED_PARAMETER") parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder(),
        onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
    ) : this(storeAccessor, precomputedData, onCrash, null)

    private val logicJob = SupervisorJob(storeAccessor.coroutineContext[Job])
    private val logicScope = CoroutineScope(storeAccessor.coroutineContext + logicJob)
    private val bootstrapCompleted = CompletableDeferred<Unit>()
    private val navigationMutex = Mutex()
    private val startClaimedByLink = MutableStateFlow(false)
    private var bootstrapJob: Job? = null

    private val entryLifecycles = mutableMapOf<String, BackstackLifecycle>()
    private val exitingLifecycles = mutableSetOf<BackstackLifecycle>()

    private data class CachedEvaluation(val key: Any?, val value: Any?)

    private val evaluationCache = mutableMapOf<Any, CachedEvaluation>()
    private val transitionSettleJob = AtomicReference<Job?>(null)
    private val startSelections = mutableMapOf<String, NavigationNode>()
    private var crashListener: CrashListener? = null
    private val overlayOwners = MutableStateFlow(0)

    internal val locationCodec: LocationCodec by lazy {
        LocationCodec(precomputedData, reaktivJson(storeAccessor.serializersModule))
    }

    private val opensOnPlaceholder: Boolean = browserHistory?.isAvailable == true

    private val webBase: String? = browserHistory?.webLocation?.basePath

    private val historySync: HistorySync? = if (browserHistory == null || isExternallyDriven()) {
        null
    } else {
        HistorySync.attach(browserHistory, this, precomputedData, storeAccessor, logicScope)
    }

    init {
        registerCrashListenerIfNeeded()
        bootstrapRootEntryIfNeeded(historySync?.coldLocation)
    }

    private fun isExternallyDriven(): Boolean = storeAccessor.externalState()?.isUnderControl == true

    private fun bootstrapRootEntryIfNeeded(coldLocation: ExternalLocation?) {
        if (isExternallyDriven()) {
            bootstrapCompleted.complete(Unit)
            return
        }

        if (coldLocation != null) {
            bootstrapJob = logicScope.launch { resolveColdLocation(coldLocation) }
            return
        }

        val plan = bootstrapPlan()
        if (plan == null) {
            logicScope.launch { finishBootstrap() }
            return
        }

        launchStart(plan)
    }

    private fun launchStart(plan: BootstrapPlan): Job =
        logicScope.launch { attemptBootstrap(plan) }.also { bootstrapJob = it }

    private class BootstrapPlan(val selector: RouteSelector, val cacheKey: CacheKeySelector?, val graphId: String?)

    private fun bootstrapPlan(): BootstrapPlan? {
        val dynamicGraph = dynamicStartGraph(ROOT_GRAPH)
        val entryDef = dynamicGraph?.let { precomputedData.graphEntries[it] }
        val selector = entryDef?.route
        if (selector != null) {
            return BootstrapPlan(selector, entryDef.cacheKey, dynamicGraph)
        }
        val staticStart = precomputedData.staticRootStart
            ?.takeIf { opensOnPlaceholder || precomputedData.staticRootStartIsGuarded }
            ?: return null
        return BootstrapPlan({ staticStart }, null, ROOT_GRAPH)
    }

    private suspend fun resolveColdLocation(location: ExternalLocation) {
        val outcome = try {
            bootstrapPlan()?.let { plan ->
                evaluateCached(plan.selector, plan.cacheKey) { plan.selector.invoke(storeAccessor) }
            }
            follow(location)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            ReaktivDebug.warn("NavigationLogic: the startup location could not be applied - ${failure.message}")
            ExternalOutcome.Unresolvable(failure.message.orEmpty())
        }
        val landed = outcome !is ExternalOutcome.Rejected && outcome !is ExternalOutcome.Unresolvable
        val plan = bootstrapPlan()
        if (!landed && plan != null) {
            attemptBootstrap(plan)
            return
        }
        finishBootstrap()
    }

    private suspend fun runDefaultBootstrap() {
        val plan = bootstrapPlan() ?: return
        startClaimedByLink.value = false
        launchStart(plan).join()
    }

    public suspend fun retryStart() {
        if (getCurrentNavigationState().startFailure == null) return
        val plan = bootstrapPlan() ?: return
        storeAccessor.dispatchAndAwait(NavigationAction.SetStartFailure(null))
        startClaimedByLink.value = false
        launchStart(plan).join()
    }

    private suspend fun attemptBootstrap(plan: BootstrapPlan) {
        try {
            runBootstrapNavigation(plan.selector, plan.cacheKey, plan.graphId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            handleBootstrapFailure(failure)
        }
    }

    private suspend fun runBootstrapNavigation(
        bootstrapSelector: RouteSelector,
        cacheKey: CacheKeySelector?,
        bootstrapGraphId: String?
    ): Boolean {
        var resolved = false
        val overlay = EvaluationOverlay()
        navigationMutex.withLock {
            withContext(NavigationLockMarker() + overlay) {
                try {
                    val selectedNode = evaluateCached(bootstrapSelector, cacheKey) {
                        bootstrapSelector.invoke(storeAccessor)
                    }
                    startSelections[bootstrapGraphId ?: ROOT_GRAPH] = selectedNode

                    if (!startClaimedByLink.value) {
                        val routeBuilder = NavigationBuilder(storeAccessor)
                        routeBuilder.clearBackStack()
                        routeBuilder.navigateToNode(resolveEntryChain(selectedNode, bootstrapGraphId ?: ROOT_GRAPH))
                        routeBuilder.validate()
                        val pass = Pass(
                            StepGuard(guardVantage(routeBuilder, getCurrentNavigationState())),
                            closing = listOf(NavigationAction.BootstrapComplete)
                        )
                        val outcome = outcomeOf(executeNavigation(routeBuilder, pass), routeBuilder, pass)
                        if (outcome == NavigationOutcome.Rejected) landAfterRejectedStart(routeBuilder, pass.closing)
                    }
                    resolved = true
                } finally {
                    withContext(NonCancellable) {
                        if (resolved) bootstrapCompleted.complete(Unit)
                    }
                    lowerOverlay(overlay)
                }
            }
        }
        return resolved
    }

    private suspend fun landAfterRejectedStart(start: NavigationBuilder, closing: List<NavigationAction>) {
        val fallback = precomputedData.notFoundScreen ?: throw IllegalStateException(
            "A guard rejected the start destination '${start.primaryRoute()}' and no " +
                "notFoundScreen is configured, so there is nowhere to land. " +
                "Configure notFoundScreen(), or have the guard return " +
                "RedirectTo or PendAndRedirectTo instead of Reject."
        )
        val builder = NavigationBuilder(storeAccessor)
        builder.clearBackStack()
        builder.navigateTo(fallback)
        builder.validate()
        executeNavigation(builder, Pass(guard = null, closing = closing))
    }

    /**
     * Reports a start destination lambda that threw.
     *
     * The start always finishes, so navigation never waits on a start that cannot resolve, and
     * [NavigationState.startFailure] records what went wrong until [retryStart] runs it again.
     * When a crash screen is configured the failure then goes through the store's crash handling,
     * matching how every other logic crash is surfaced. Without one there is nowhere correct to
     * land, so navigation stays on the loading modal rather than sending the user to a destination
     * the app never asked for.
     */
    private suspend fun handleBootstrapFailure(failure: Throwable) {
        storeAccessor.dispatchAndAwait(
            NavigationAction.SetStartFailure(
                StartFailure(failure::class.simpleName ?: "Throwable", failure.message.orEmpty())
            )
        )
        finishBootstrap()
        if (precomputedData.crashScreen != null) throw failure
        ReaktivDebug.error(
            "NavigationLogic: the start destination failed, so navigation stays on the loading modal " +
                "with NavigationState.startFailure set. Call retryStart() to run it again, configure " +
                "crashScreen() to land there instead, or have the lambda return a destination of its " +
                "own when it cannot resolve one.",
            failure
        )
    }

    private suspend fun finishBootstrap() {
        if (getCurrentNavigationState().isBootstrapping) {
            storeAccessor.dispatchAndAwait(NavigationAction.BootstrapComplete)
        }
        bootstrapCompleted.complete(Unit)
    }

    override suspend fun onExternalControlChanged(externallyDriven: Boolean) {
        if (!externallyDriven) return
        historySync?.goPassive()

        bootstrapJob?.cancelAndJoin()
        bootstrapJob = null

        if (getCurrentNavigationState().isEvaluatingNavigation) {
            storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
        }
        finishBootstrap()
    }

    override suspend fun beforeReset() {
        (entryLifecycles.values + exitingLifecycles).forEach { it.runRemovalHandlers(RemovalReason.RESET) }
        entryLifecycles.clear()
        exitingLifecycles.clear()
        evaluationCache.clear()
        transitionSettleJob.store(null)
        crashListener?.let { storeAccessor.removeCrashListener(it) }
        crashListener = null
        bootstrapJob = null
    }

    private fun registerCrashListenerIfNeeded() {
        val crashScreenDef = precomputedData.crashScreen ?: return
        val listener = object : CrashListener {
            override suspend fun onLogicCrash(exception: Throwable, action: ModuleAction?): CrashRecovery {
                val recovery = onCrash?.invoke(exception, action)
                    ?: CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
                if (recovery == CrashRecovery.NAVIGATE_TO_CRASH_SCREEN) {
                    navigateToCrashScreen(exception, action, crashScreenDef)
                }
                return recovery
            }
        }
        crashListener = listener
        storeAccessor.addCrashListener(listener)
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
                    dismissModals = false
                )
            )
        } catch (e: Exception) {
            ReaktivDebug.error("NavigationLogic: Failed to navigate to crash screen - ${e.message}", e)
        }
    }

    internal suspend fun onCommitted(action: NavigationAction, state: NavigationState) {
        if (action is NavigationAction.ScrubUpdate || action is NavigationAction.ScrubEnd) return
        invokeLifecycleCallbacks(state.backStack)
        historySync?.onCommitted(action, state)
    }

    @Deprecated(
        "Store.loadState() now adopts the restored back stack itself, so this call is no longer needed.",
        level = DeprecationLevel.WARNING
    )
    public suspend fun adoptCurrentBackstack() {
        adoptBackstack()
    }

    override suspend fun onHydrated(source: HydrateSource) {
        if (source == HydrateSource.Restore) adoptBackstack()
    }

    private suspend fun adoptBackstack() {
        invokeLifecycleCallbacks(storeAccessor.selectState<NavigationState>().first().backStack)
        historySync?.adopt()
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
        if (isExternallyDriven()) return NavigationOutcome.Dropped
        val builder = NavigationBuilder(storeAccessor)
        builder.apply { block() }
        builder.validate()
        if (builder.hasNothingToDo) return NavigationOutcome.Success
        runOnItsOwn(builder.operations.singleOrNull())?.let { return it }
        if (!opensSystemLayer(builder.primaryRoute()) && currentCoroutineContext()[NavigationLockKey] == null) {
            bootstrapCompleted.await()
        }
        return evaluateAndExecute(builder)
    }

    private fun NavigationBuilder.primaryRoute(): String? =
        operations.firstOrNull {
            it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
        }?.target?.resolve(precomputedData)

    private fun opensSystemLayer(route: String?): Boolean =
        route != null && precomputedData.routeResolver.resolve(route)?.targetNavigatable?.renderLayer == RenderLayer.SYSTEM

    private suspend fun runOnItsOwn(step: NavigationStep?): NavigationOutcome? = when (step?.operation) {
        NavigationOperation.Back -> {
            navigateBack(step.expectedTopKey)
            NavigationOutcome.Success
        }
        NavigationOperation.DismissModal -> {
            dismissModal()
            NavigationOutcome.Success
        }
        NavigationOperation.DeepLink -> {
            navigateDeepLink((step.target as NavigationTarget.Path).path, step.params)
            NavigationOutcome.Success
        }
        else -> null
    }

    private sealed class GuardEvaluation {
        object Allow : GuardEvaluation()
        object Reject : GuardEvaluation()
        data class Redirect(val route: String, val zonePath: String?) : GuardEvaluation()
        data class PendAndRedirect(
            val pending: PendingNavigation,
            val redirectRoute: String,
            val alreadyAtRedirect: Boolean,
            val zonePath: String?
        ) : GuardEvaluation()
    }

    private class Zone(val intercept: InterceptDefinition, val graphId: String?)

    private fun zoneFor(route: String, resolution: RouteResolution?): Zone? {
        val (intercept, anchor) = resolution?.let { landing ->
            precomputedData.interceptsByPath[landing.entryPath()]?.let { it to landing.owningGraphId }
        } ?: precomputedData.routeResolver.canonicalGraphId(route)?.let { graphId ->
            precomputedData.interceptsByGraphId[graphId]?.let { it to graphId }
        } ?: return null
        val outermost = precomputedData.graphIndex.chain(anchor).firstOrNull {
            precomputedData.interceptsByGraphId[it] == intercept
        }
        return Zone(intercept, outermost)
    }

    /**
     * The full path of the outermost graph carrying [zone], or `null` when the zone guards single
     * screens rather than a graph.
     *
     * A redirect lands as if the zone had been entered and the guard had answered at the door,
     * so the entries beneath it are the ones above this path. Synthesizing the zone's own start
     * under the redirect would put the very screen the guard refused one back press away.
     */
    private fun zoneBoundaryPath(zone: Zone): String? =
        zone.graphId?.let { precomputedData.routeResolver.fullPathForGraph(it) ?: it }

    /**
     * The two readings of the back stack a guard decision needs.
     *
     * [passed] is the stack this navigation may claim as already guarded: every entry in it got
     * there by satisfying whatever guards stood in the way. It is empty for a navigation asked
     * for from outside the app, which makes its own case rather than inheriting the app's, and
     * empty until the first navigation lands, because the stack the state was constructed with
     * is a placeholder nobody navigated to and no guard was ever asked about.
     *
     * [surviving] is what is still standing once this navigation lands, so it is empty when the
     * builder clears the stack.
     */
    private class GuardVantage(
        val passed: List<NavigationEntry>,
        val surviving: List<NavigationEntry>
    )

    private fun guardVantage(builder: NavigationBuilder, state: NavigationState): GuardVantage =
        GuardVantage(
            passed = if (builder.isExternallyRequested || state.lastNavigationAction == null) {
                emptyList()
            } else {
                state.backStack
            },
            surviving = if (builder.clearsBackStack()) emptyList() else state.backStack
        )

    /**
     * Evaluates the intercept guards protecting [targetRoute], or `null` when the route is not
     * inside a protected zone.
     *
     * A guard is skipped only when [GuardVantage.passed] is already inside the same zone,
     * because passing the guard to get there is what earns the skip. Clearing the back stack
     * does not revoke it: what a clear discards is history, not the position the app already
     * holds. A deep link, bootstrap and the placeholder stack all start from an empty vantage,
     * so none of them can enter a zone on a claim that was never theirs.
     */
    private sealed class Execution {
        object Committed : Execution()
        object Blocked : Execution()
        class Stopped(val evaluation: GuardEvaluation, val step: NavigationStep) : Execution()
    }

    private class Pass(
        val guard: StepGuard?,
        val floor: String? = null,
        val closing: List<NavigationAction> = emptyList()
    ) {
        val selections = mutableMapOf<String, NavigationNode>()
    }

    private inner class StepGuard(private val vantage: GuardVantage) {
        private val cleared = mutableListOf<InterceptDefinition>()

        private fun zoneOf(path: String): InterceptDefinition? = precomputedData.interceptsByPath[path]

        private fun passedThrough(zone: InterceptDefinition): Boolean =
            zone in cleared || vantage.passed.any { zoneOf(it.path) == zone }

        fun admits(ancestor: NavigationEntry, destination: NavigationEntry): Boolean {
            val zone = zoneOf(ancestor.path) ?: return true
            return zone == zoneOf(destination.path) || passedThrough(zone)
        }

        suspend fun check(route: String, resolution: RouteResolution?, step: NavigationStep): Execution.Stopped? =
            evaluate(route, resolution, step)
                ?.takeIf { it != GuardEvaluation.Allow }
                ?.let { Execution.Stopped(it, step) }

        suspend fun evaluate(
            targetRoute: String,
            targetResolution: RouteResolution?,
            step: NavigationStep
        ): GuardEvaluation? {
            if (isExternallyDriven()) return GuardEvaluation.Allow
            val zone = zoneFor(targetRoute, targetResolution) ?: return null
            val interceptDef = zone.intercept
            if (passedThrough(interceptDef)) return GuardEvaluation.Allow
            val zoneKey = zone.graphId ?: targetRoute
            val zonePath = zoneBoundaryPath(zone)

            fun GuardResult.toGuardEvaluation(): GuardEvaluation = when (this) {
                is GuardResult.Allow -> GuardEvaluation.Allow
                is GuardResult.Reject -> GuardEvaluation.Reject
                is GuardResult.RedirectTo -> GuardEvaluation.Redirect(redirectRoute(target), zonePath)
                is GuardResult.PendAndRedirectTo -> {
                    val pending = PendingNavigation(
                        route = targetRoute,
                        params = step.params,
                        metadata = metadata,
                        displayHint = displayHint
                    )
                    val redirectTarget = redirectRoute(target)
                    val redirectPath = precomputedData.routeResolver.resolve(redirectTarget)?.entryPath()
                    GuardEvaluation.PendAndRedirect(
                        pending = pending,
                        redirectRoute = redirectTarget,
                        alreadyAtRedirect = redirectPath == vantage.surviving.lastOrNull()?.path,
                        zonePath = zonePath
                    )
                }
            }

            for ((index, outerEntry) in interceptDef.outerGuards.withIndex()) {
                val result = evaluateCached(outerEntry.guard, outerEntry.cacheKey) {
                    evaluateWithThreshold(outerEntry.loadingThreshold) {
                        traceGuard(
                            storeAccessor,
                            "outerGuard[$index]($zoneKey)",
                            targetRoute
                        ) { outerEntry.guard(storeAccessor) }
                    }
                }
                val evaluation = result.toGuardEvaluation()
                if (evaluation != GuardEvaluation.Allow) return evaluation
            }

            val evaluation = evaluateCached(interceptDef.guard, interceptDef.cacheKey) {
                evaluateWithThreshold(interceptDef.loadingThreshold) {
                    traceGuard(storeAccessor, "guard($zoneKey)", targetRoute) { interceptDef.guard(storeAccessor) }
                }
            }.toGuardEvaluation()
            if (evaluation == GuardEvaluation.Allow) cleared.add(interceptDef)
            return evaluation
        }
    }

    private fun redirectRoute(target: NavigationTarget): String = when (target) {
        is NavigationTarget.Path -> target.path
        is NavigationTarget.NavigatableObject -> precomputedData.navigatableToFullPath[target.navigatable] ?: target.navigatable.route
        is NavigationTarget.NavigatableObjectWithGraph ->
            precomputedData.navigatableToFullPath[target.navigatable] ?: target.navigatable.route
    }

    private suspend fun resolveEntryChain(
        initialNode: NavigationNode,
        initialRoute: String,
        selections: MutableMap<String, NavigationNode>? = null
    ): NavigationNode {
        if (initialNode is Navigatable) return initialNode
        var resolvedNode: NavigationNode = initialNode
        val visitedRoutes = mutableSetOf(initialRoute)
        while (resolvedNode !is Navigatable) {
            val nextRoute = resolvedNode.route
            if (!visitedRoutes.add(nextRoute)) break
            val graphId = dynamicStartGraph(nextRoute) ?: break
            val next = selectStart(graphId, nextRoute) ?: break
            selections?.set(graphId, next)
            resolvedNode = next
        }
        return resolvedNode
    }

    private fun dynamicStartGraph(route: String): String? {
        var graphId = precomputedData.routeResolver.canonicalGraphId(route) ?: return null
        val visited = mutableSetOf<String>()
        while (visited.add(graphId)) {
            if (precomputedData.graphEntries[graphId]?.route != null) return graphId
            val start = precomputedData.graphDefinitions[graphId]?.startDestination
            graphId = (start as? StartDestination.GraphReference)?.graphId ?: return null
        }
        return null
    }

    private fun occupies(graphId: String, backStack: List<NavigationEntry>): Boolean =
        backStack.any { it.graphId == graphId }

    private suspend fun selectStart(graphId: String, route: String): NavigationNode? {
        val entryDef = precomputedData.graphEntries[graphId] ?: return null
        val selector = entryDef.route ?: return null
        val node = evaluateCached(selector, entryDef.cacheKey) {
            evaluateWithThreshold(
                loadingThreshold = entryDef.loadingThreshold
            ) {
                traceEntrySelection(storeAccessor, "entry($graphId)", route) { selector.invoke(storeAccessor) }
            }
        }
        startSelections[graphId] = node
        return node
    }

    private suspend fun resolveGraphEntryForSynthesis(
        graphPath: String,
        simulatedBackStack: List<NavigationEntry>,
        pass: Pass,
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

        val graphId = precomputedData.routeResolver.canonicalGraphId(graphPath)
        if (graphId != null && graphId in visited) return null
        val directEntryDef = graphId?.let { precomputedData.graphEntries[it] }
        val entryDef: EntryDefinition
        val effectiveGraphId: String
        if (directEntryDef == null) {
            val startDest = graphId?.let { precomputedData.graphDefinitions[it]?.startDestination }
            if (startDest is StartDestination.GraphReference) {
                effectiveGraphId = startDest.graphId
                entryDef = precomputedData.graphEntries[startDest.graphId] ?: return null
            } else {
                return null
            }
        } else {
            effectiveGraphId = graphId
            entryDef = directEntryDef
        }

        val selector = entryDef.route ?: return null

        val occupied = occupies(effectiveGraphId, simulatedBackStack) ||
            ((simulatedBackStack.isNotEmpty() || visited.isNotEmpty()) &&
                occupies(effectiveGraphId, getCurrentNavigationState().backStack))

        val node = pass.selections[effectiveGraphId]
            ?: startSelections[effectiveGraphId]?.takeIf { occupied }
            ?: evaluateCached(selector, entryDef.cacheKey) {
                evaluateWithThreshold(entryDef.loadingThreshold) { selector.invoke(storeAccessor) }
            }.also { startSelections[effectiveGraphId] = it }
        return when {
            node is Navigatable ->
                node.toNavigationEntry(path = node.fullPathOrRoute(), params = Params.empty())
            precomputedData.routeResolver.canonicalGraphId(node.route) != null ->
                resolveGraphEntryForSynthesis(
                    node.route, simulatedBackStack, pass, visited + setOfNotNull(graphPath, graphId)
                )
            else -> {
                val resolution = precomputedData.routeResolver.resolve(node.route) ?: return null
                resolution.targetNavigatable.toNavigationEntry(
                    path = resolution.targetNavigatable.fullPathOrRoute(),
                    params = resolution.extractedParams
                )
            }
        }
    }

    /**
     * Executes the navigation a guard substituted for the one it intercepted.
     *
     * The redirect keeps the stack semantics of the navigation it replaces: it clears when that
     * one cleared and synthesizes ancestors when that one did, so a deep link that gets
     * redirected still lands on a coherent stack rather than on top of whatever was showing.
     * Synthesis stops at [zonePath], see [zoneBoundaryPath].
     */
    private suspend fun executeRedirect(
        route: String,
        replaced: NavigationStep,
        clearsBackStack: Boolean,
        zonePath: String?,
        closing: List<NavigationAction>
    ) {
        val builder = NavigationBuilder(storeAccessor)
        if (clearsBackStack) builder.clearBackStack()
        builder.navigateTo(
            route,
            replaceCurrent = replaced.operation == NavigationOperation.Replace && !clearsBackStack,
            synthesizeBackstack = replaced.synthesizeBackstack
        )
        builder.validate()
        executeNavigation(builder, Pass(guard = null, floor = zonePath, closing = closing))
    }

    private fun NavigationNode.fullPathOrRoute(): String =
        if (this is Navigatable) precomputedData.navigatableToFullPath[this] ?: route
        else precomputedData.routeResolver.fullPathForGraph(route) ?: route

    private fun NavigationBuilder.navigateToNode(node: NavigationNode) {
        if (node is Navigatable) navigateTo(node) else navigateTo(node.fullPathOrRoute())
    }

    private suspend fun guardOutcome(
        guard: GuardEvaluation?,
        builder: NavigationBuilder,
        step: NavigationStep,
        closing: List<NavigationAction> = emptyList()
    ): NavigationOutcome? = when (guard) {
        is GuardEvaluation.Reject -> NavigationOutcome.Rejected
        is GuardEvaluation.Redirect -> {
            executeRedirect(guard.route, step, builder.clearsBackStack(), guard.zonePath, closing)
            NavigationOutcome.Redirected(guard.route)
        }
        is GuardEvaluation.PendAndRedirect -> {
            storeAccessor.dispatchAndAwait(NavigationAction.SetPendingNavigation(guard.pending))
            if (!guard.alreadyAtRedirect) {
                executeRedirect(guard.redirectRoute, step, clearsBackStack = true, guard.zonePath, closing)
            }
            NavigationOutcome.Redirected(guard.redirectRoute)
        }
        is GuardEvaluation.Allow, null -> null
    }

    private suspend fun outcomeOf(result: Execution, builder: NavigationBuilder, pass: Pass): NavigationOutcome =
        when (result) {
            Execution.Committed -> NavigationOutcome.Success
            Execution.Blocked -> NavigationOutcome.Dropped
            is Execution.Stopped ->
                guardOutcome(result.evaluation, builder, result.step, pass.closing) ?: NavigationOutcome.Success
        }

    private fun peerHostsAbove(route: String): List<String> =
        precomputedData.routeResolver.buildPathHierarchy(route).dropLast(1).filter { graphPath ->
            val graphId = precomputedData.routeResolver.canonicalGraphId(graphPath) ?: return@filter false
            precomputedData.graphDefinitions[graphId]?.declaration?.startAnchorsChildren == false
        }

    private suspend fun synthesizeAncestorEntries(
        destination: NavigationEntry,
        simulatedBackStack: List<NavigationEntry>,
        pass: Pass
    ): List<NavigationEntry> {
        val route = destination.path
        val floor = pass.floor
        val seenPaths = (simulatedBackStack.map { it.path } + route).toMutableSet()
        val locationSegments = RouteTemplate.splitPath(destination.location)
        val synthesized = mutableListOf<NavigationEntry>()
        var stack = simulatedBackStack
        val peerHosts = peerHostsAbove(route)
        val rootEntry = if (stack.all { it.navigatable.renderLayer == RenderLayer.SYSTEM }) {
            resolveGraphEntryForSynthesis(ROOT_GRAPH, stack, pass)
        } else {
            null
        }
        val rootInsidePeerHost = rootEntry != null && peerHosts.any { rootEntry.path.startsWith("$it/") }
        if (rootEntry != null && !rootInsidePeerHost && seenPaths.add(rootEntry.path)) {
            synthesized.add(rootEntry)
            stack = stack + rootEntry
        }
        for ((depth, intermediatePath) in precomputedData.routeResolver.buildPathHierarchy(route).dropLast(1).withIndex()) {
            if (floor != null && (intermediatePath == floor || intermediatePath.startsWith("$floor/"))) continue
            if (intermediatePath in peerHosts) continue
            val resolved = resolveGraphEntryForSynthesis(intermediatePath, stack, pass) ?: continue
            if (!seenPaths.add(resolved.path)) continue
            val entry = withParamsFromLocation(resolved, intermediatePath, locationSegments.take(depth + 1))
            synthesized.add(entry)
            stack = stack + entry
        }
        return synthesized
    }

    private fun withParamsFromLocation(
        entry: NavigationEntry,
        templatePrefix: String,
        locationPrefix: List<String>
    ): NavigationEntry {
        if (entry.path != templatePrefix) return entry
        val template = RouteTemplate.parse(entry.path)
        if (!template.isParameterized) return entry
        val values = template.match(locationPrefix.joinToString("/")) ?: return entry
        return entry.copy(params = Params.fromMap(values) + entry.params)
    }

    /**
     * Evaluate intercept guards and entry definitions for the given builder, then execute
     * the navigation.
     *
     * The work runs on this logic's own job with the caller's context, and the caller awaits it
     * without observing its own cancellation. A caller that goes away, such as a composable
     * leaving composition, therefore never leaves a navigation half applied, while a store reset
     * cancels the work and the caller sees that cancellation. The outcome travels through its own
     * deferred rather than the job, so work that ran to completion reports its outcome even when
     * a reset cancelled the job meanwhile, and a navigation that committed is never reported as
     * cancelled.
     *
     * Navigations are serialized: a call issued while another navigation is in progress
     * suspends until the in-flight one completes, then executes. Re-entrant calls made
     * from inside an in-flight navigation (e.g. a guard navigating) execute inline.
     *
     * A navigation to a [RenderLayer.SYSTEM] destination does not wait for the navigation lock,
     * because it must not queue behind another. Guards and multi-step blocks still serialise
     * through the store's own ordered dispatch, so this only skips the evaluation lock, not state
     * consistency.
     */
    private suspend fun evaluateAndExecute(builder: NavigationBuilder): NavigationOutcome {
        val primaryRoute = builder.primaryRoute()
        return traceNavigation(storeAccessor, primaryRoute ?: builder.describeTarget()) {
            serialized(bypassLock = opensSystemLayer(primaryRoute)) {
                val currentState = getCurrentNavigationState()
                startSelections.keys.retainAll { occupies(it, currentState.backStack) }
                val pass = Pass(StepGuard(guardVantage(builder, currentState)))
                outcomeOf(executeNavigation(builder, pass), builder, pass)
            }
        }
    }

    private suspend fun <T> serialized(bypassLock: Boolean = false, work: suspend () -> T): T {
        if (currentCoroutineContext()[NavigationLockKey] != null) return work()
        if (bypassLock) {
            val overlay = EvaluationOverlay()
            return try {
                withContext(overlay) { work() }
            } finally {
                lowerOverlay(overlay)
            }
        }
        navigationMutex.lock()
        var settleJob: Job? = null
        val outcome = try {
            val result = CompletableDeferred<T>()
            val overlay = EvaluationOverlay()
            val job = CoroutineScope(currentCoroutineContext().minusKey(Job) + logicJob)
                .launch(NavigationLockMarker() + overlay) {
                    try {
                        result.complete(work())
                    } catch (e: Throwable) {
                        result.completeExceptionally(e)
                    } finally {
                        lowerOverlay(overlay)
                    }
                }
            job.invokeOnCompletion { cause ->
                if (cause != null) result.completeExceptionally(cause)
            }
            withContext(NonCancellable) { result.await() }
        } finally {
            settleJob = transitionSettleJob.load()
            navigationMutex.unlock()
        }
        if (currentCoroutineContext().isActive) {
            settleJob?.join()
        }
        return outcome
    }

    private suspend fun lowerOverlay(overlay: EvaluationOverlay) {
        if (!overlay.raised) return
        withContext(NonCancellable) {
            val stillRaised = overlayOwners.updateAndGet { it - 1 } > 0
            if (!stillRaised && getCurrentNavigationState().isEvaluatingNavigation) {
                storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
            }
        }
    }

    private suspend fun <T> evaluateCached(
        owner: Any,
        cacheKey: CacheKeySelector?,
        evaluate: suspend () -> T
    ): T {
        if (cacheKey == null) return evaluate()
        val key = cacheKey(storeAccessor)
        val cached = evaluationCache[owner]
        if (cached != null && cached.key == key) {
            @Suppress("UNCHECKED_CAST")
            return cached.value as T
        }
        val value = evaluate()
        evaluationCache[owner] = CachedEvaluation(key, value)
        return value
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
        val overlay = currentCoroutineContext()[EvaluationOverlayKey]
        if (!completedInTime && overlay != null && precomputedData.loadingModal != null) {
            if (!overlay.raised) {
                overlay.raised = true
                overlayOwners.update { it + 1 }
            }
            storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(true))
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
     * No-op unless the state can currently accept a back, which is the same question the
     * gesture and platform-back paths ask through `canHandleBack`. Back navigation is refused
     * while a [LoadingModal] is the current entry, and while bootstrap is unresolved or an async
     * guard or entry evaluation is in flight, unless the current entry is a [RenderLayer.SYSTEM]
     * entry raised over that work. The evaluation commits deltas against the stack as it is when
     * it lands, so an alert leaving from on top of it is harmless, whereas the screens beneath
     * are what it is about to replace and must stay put.
     *
     * Dispatches [NavigationAction.Back] directly, bypassing the navigation mutex.
     * This is intentional: a back/dismiss requires no guard evaluation, and the mutex
     * may be held while a loading modal is showing (e.g. during guard evaluation).
     * Routing through [evaluateAndExecute] would needlessly serialize the dismiss
     * behind the in-flight evaluation.
     */
    public suspend fun navigateBack(expectedTopKey: String? = null) {
        val currentState = getCurrentNavigationState()
        if (!canHandleBack(currentState)) return
        storeAccessor.dispatchAndAwait(NavigationAction.Back(expectedTopKey))
    }

    /**
     * Pop up to a specific route in the backstack.
     *
     * @param route Target route to pop back to
     * @param inclusive If true, also removes the target route from backstack
     * @param fallback Optional fallback route if the target route is not found
     */
    public suspend fun dismissModal() {
        serialized { dismissTopModal() }
    }

    private suspend fun dismissTopModal() {
        val state = getCurrentNavigationState()
        val modal = state.backStack.lastOrNull { it.navigatable is Modal } ?: return

        if (modal.stableKey == state.currentEntry.stableKey) {
            navigateBack(expectedTopKey = modal.stableKey)
            val after = getCurrentNavigationState()
            if (after.currentEntry.stableKey == modal.stableKey ||
                after.backStack.none { it.stableKey == modal.stableKey }
            ) {
                return
            }
            removeModalBeneathTop(modal, after.currentEntry)
            return
        }

        removeModalBeneathTop(modal, state.currentEntry)
    }

    private suspend fun removeModalBeneathTop(modal: NavigationEntry, top: NavigationEntry) {
        val state = getCurrentNavigationState()
        storeAccessor.dispatchAndAwait(removal(withoutEntry(state.backStack, modal), top))
    }

    private fun withoutEntry(backStack: List<NavigationEntry>, removed: NavigationEntry): List<NavigationEntry> =
        backStack.filter { it.stableKey != removed.stableKey && it.navigatable.renderLayer != RenderLayer.SYSTEM }

    private fun removal(remaining: List<NavigationEntry>, top: NavigationEntry): NavigationAction.Traverse =
        NavigationAction.Traverse(
            entries = remaining,
            direction = TraverseDirection.Back,
            presentation = TraversePresentation.AlreadyPresented,
            expectedTopKey = top.stableKey
        )

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
        val outcome = applyExternalLocation(ExternalLocation.Url(route, params = params))
        if (outcome is ExternalOutcome.Unresolvable) throw RouteNotFoundException(outcome.reason)
    }

    internal suspend fun openLink(link: String, params: Map<String, String>): LinkOutcome {
        val href = link.takeIf { SCHEME.containsMatchIn(it) }
        val (path, query) = parseUrlWithQueryParams(href?.let(::pathOfUrl) ?: link)
        val template = RouteTemplate.parse(path.trimStart('/'))
        val location = when (val filled = template.fill { params[it] }) {
            is RouteTemplate.Fill.Missing -> return LinkOutcome.MissingParams(filled.names)
            is RouteTemplate.Fill.Filled -> if (template.isParameterized) filled.location else path
        }
        val extra = params.filterKeys { it !in template.paramNames } + query
        val outcome = try {
            applyExternalLocation(ExternalLocation.Url(location, href = href, params = Params.fromMap(extra)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (missing: MissingPathParamsException) {
            return LinkOutcome.MissingParams(missing.missingParams)
        } catch (notFound: RouteNotFoundException) {
            return LinkOutcome.NotFound(notFound.message.orEmpty())
        }
        return when (outcome) {
            is ExternalOutcome.Landed -> LinkOutcome.Landed(getCurrentNavigationState().currentEntry.location)
            is ExternalOutcome.LandedOnNotFound -> LinkOutcome.NotFound(outcome.reason)
            is ExternalOutcome.Unresolvable -> LinkOutcome.NotFound(outcome.reason)
            is ExternalOutcome.Redirected -> LinkOutcome.Redirected(outcome.to)
            is ExternalOutcome.Rejected -> LinkOutcome.Rejected
            is ExternalOutcome.Stale -> LinkOutcome.Ignored("A newer navigation took over before the link landed")
            is ExternalOutcome.Dropped -> LinkOutcome.Ignored("The store follows a DevTools publisher")
        }
    }

    private fun pathOfUrl(url: String): String {
        val afterHost = url.substringAfter("://").substringAfter('/', "")
        val fragment = afterHost.substringAfter('#', "")
        if (afterHost.substringBefore('#').substringBefore('?').isEmpty() && fragment.startsWith("/")) {
            return fragment.removePrefix("/")
        }
        val base = webBase
        return if (!base.isNullOrEmpty() && afterHost.startsWith("$base/")) afterHost.removePrefix("$base/") else afterHost
    }

    internal suspend fun applyExternalLocation(location: ExternalLocation): ExternalOutcome {
        if (isExternallyDriven()) return ExternalOutcome.Dropped
        if (location is ExternalLocation.Url) {
            unresolvable(linkTarget(location))?.let { return it }
        }

        startClaimedByLink.value = true
        val coldStart = !bootstrapCompleted.isCompleted
        if (coldStart) {
            bootstrapCompleted.await()
        }

        val outcome = try {
            follow(location)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (coldStart) {
                runDefaultBootstrap()
                finishBootstrap()
            }
            throw failure
        }
        if (coldStart && (outcome is ExternalOutcome.Rejected || outcome is ExternalOutcome.Unresolvable)) {
            runDefaultBootstrap()
        }

        if (coldStart) finishBootstrap()
        return outcome
    }

    private suspend fun follow(location: ExternalLocation): ExternalOutcome = when (location) {
        is ExternalLocation.Url -> followUrl(location)
        is ExternalLocation.Snapshot -> restore(location)
    }

    private class LinkTarget(val route: String, val params: Params, val describedAs: String)

    private fun linkTarget(url: ExternalLocation.Url): LinkTarget {
        val (path, queryParams) = parseUrlWithQueryParams(url.path)
        val cleanRoute = normalizePath(path).ifEmpty { ROOT_GRAPH }
        val query = Params.fromMap(queryParams)
        val candidates = listOfNotNull(cleanRoute, url.href?.let { parseUrlWithQueryParams(it).first })
        for (candidate in candidates) {
            for (alias in precomputedData.deepLinkAliases) {
                val pathParams = alias.matchAndExtract(candidate) ?: continue
                return LinkTarget(
                    alias.targetRoute,
                    alias.paramsMapping(query + url.params + pathParams),
                    "alias target for '$candidate'"
                )
            }
        }
        return LinkTarget(cleanRoute, query + url.params, "deep link")
    }

    private fun unresolvable(target: LinkTarget): ExternalOutcome.Unresolvable? =
        if (precomputedData.notFoundScreen != null || precomputedData.routeResolver.isFullPath(target.route)) {
            null
        } else {
            ExternalOutcome.Unresolvable(fullPathMessage(precomputedData.routeResolver, target.route, target.describedAs))
        }

    private suspend fun followUrl(url: ExternalLocation.Url): ExternalOutcome {
        val target = linkTarget(url)
        unresolvable(target)?.let { return it }
        val builder = NavigationBuilder(storeAccessor)
        builder.markExternallyRequested()
        builder.clearBackStack()
        builder.params(target.params)
        val notFound = precomputedData.notFoundScreen
        val fallback = if (!precomputedData.routeResolver.isFullPath(target.route) && notFound != null) {
            val message = fullPathMessage(precomputedData.routeResolver, target.route, target.describedAs)
            ReaktivDebug.warn("$message Landing on the notFoundScreen '${notFound.route}' instead.")
            builder.navigateTo(notFound)
            message
        } else {
            builder.navigateTo(target.route, synthesizeBackstack = true)
            null
        }
        builder.validate()
        val outcome = evaluateAndExecute(builder).toExternalOutcome()
        return if (fallback != null && outcome == ExternalOutcome.Landed) ExternalOutcome.LandedOnNotFound(fallback) else outcome
    }

    private fun NavigationOutcome.toExternalOutcome(): ExternalOutcome = when (this) {
        is NavigationOutcome.Success -> ExternalOutcome.Landed
        is NavigationOutcome.Dropped -> ExternalOutcome.Dropped
        is NavigationOutcome.Rejected -> ExternalOutcome.Rejected
        is NavigationOutcome.Redirected -> ExternalOutcome.Redirected(to)
    }

    private suspend fun restore(location: ExternalLocation.Snapshot): ExternalOutcome {
        val state = getCurrentNavigationState()
        val entries = locationCodec.entriesOf(location.snapshot, state.backStack)
        if (entries.isNullOrEmpty()) return followUrl(location.url)
        val live = state.backStack.filter(locationCodec::isAddressable)
        val shared = entries.zip(live).takeWhile { (restored, current) -> restored.stableKey == current.stableKey }.size
        if (shared == entries.size && shared == live.size) return ExternalOutcome.Landed

        val topKey = state.currentEntry.stableKey
        return traceTraverse(storeAccessor, entries.last().location) {
            if (shared == entries.size) {
                traverseTo(entries, location, topKey)
            } else {
                serialized {
                    restoreGuarded(entries, shared, location, topKey)
                }
            }
        }
    }

    private suspend fun restoreGuarded(
        entries: List<NavigationEntry>,
        shared: Int,
        location: ExternalLocation.Snapshot,
        topKey: String
    ): ExternalOutcome {
        if (shared == 0 && !startsAtRootStart(entries.first())) return followUrl(location.url)
        var passed = entries.take(shared)
        for (entry in entries.drop(shared)) {
            val step = NavigationStep(
                NavigationOperation.Navigate,
                params = entry.params,
                synthesizeBackstack = passed.isEmpty()
            )
            val guard = StepGuard(GuardVantage(passed = passed, surviving = passed)).evaluate(
                entry.location,
                precomputedData.routeResolver.resolve(entry.location),
                step
            )
            if (guard != null && guard != GuardEvaluation.Allow) {
                val top = entries.last()
                val resumeAtTop = if (guard is GuardEvaluation.PendAndRedirect) {
                    guard.copy(pending = guard.pending.copy(route = top.location, params = top.params))
                } else {
                    guard
                }
                return landInstead(resumeAtTop, passed, step, location, topKey)
            }
            passed = passed + entry
        }
        return traverseTo(entries, location, topKey)
    }

    private suspend fun startsAtRootStart(bottom: NavigationEntry): Boolean {
        val plan = bootstrapPlan() ?: return true
        val selected = evaluateCached(plan.selector, plan.cacheKey) { plan.selector.invoke(storeAccessor) }
        return resolveEntryChain(selected, plan.graphId ?: ROOT_GRAPH) == bottom.navigatable
    }

    private suspend fun landInstead(
        guard: GuardEvaluation,
        passed: List<NavigationEntry>,
        step: NavigationStep,
        location: ExternalLocation.Snapshot,
        topKey: String
    ): ExternalOutcome {
        if (guard == GuardEvaluation.Reject) return ExternalOutcome.Rejected
        if (!location.isCurrent()) return ExternalOutcome.Stale
        val clearsAnyway = guard is GuardEvaluation.PendAndRedirect && !guard.alreadyAtRedirect
        if (passed.isNotEmpty() && !clearsAnyway) {
            val live = getCurrentNavigationState().backStack.filter(locationCodec::isAddressable)
            if (passed.map { it.stableKey } != live.map { it.stableKey } &&
                !commitTraverse(passed, location.direction, location.presentation, topKey)
            ) {
                return ExternalOutcome.Stale
            }
        }
        val builder = NavigationBuilder(storeAccessor)
        if (passed.isEmpty()) builder.clearBackStack()
        return guardOutcome(guard, builder, step)?.toExternalOutcome() ?: ExternalOutcome.Landed
    }

    private suspend fun traverseTo(
        entries: List<NavigationEntry>,
        location: ExternalLocation.Snapshot,
        topKey: String
    ): ExternalOutcome =
        if (location.isCurrent() && commitTraverse(entries, location.direction, location.presentation, topKey)) {
            ExternalOutcome.Landed
        } else {
            ExternalOutcome.Stale
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
                clearBackStack()
                params(params)
                navigateTo(newRoute)
            }
        } else {
            navigate {
                clearBackStack()
            }
        }
    }

    private class ResolvedTarget(val route: String, val resolution: RouteResolution)

    private fun NavigationStep.resuming(pending: PendingNavigation?): NavigationStep =
        if (operation == NavigationOperation.ResumePending && pending != null) {
            copy(target = NavigationTarget.Path(pending.route), params = pending.params, synthesizeBackstack = true)
        } else {
            this
        }

    private fun targetRoute(step: NavigationStep): String? = when (step.operation) {
        NavigationOperation.Navigate, NavigationOperation.Replace ->
            step.target?.resolve(precomputedData) ?: throw IllegalStateException("${step.operation} requires a target")
        NavigationOperation.ResumePending -> step.target?.resolve(precomputedData)?.takeIf {
            dynamicStartGraph(it) != null || precomputedData.routeResolver.resolve(it) != null
        }
        else -> null
    }

    private suspend fun resolveDestination(route: String, pass: Pass): ResolvedTarget {
        val landing = dynamicStartGraph(route)?.let { graphId ->
            val start = startSelections[graphId] ?: selectStart(graphId, route) ?: return@let null
            pass.selections[graphId] = start
            resolveEntryChain(start, route, pass.selections)
        }
        if (landing is Navigatable) {
            return ResolvedTarget(
                NavigationTarget.NavigatableObject(landing).resolve(precomputedData),
                RouteResolution(
                    targetNavigatable = landing,
                    owningGraphId = precomputedData.navigatableToGraph[landing] ?: ROOT_GRAPH,
                    extractedParams = Params.empty()
                )
            )
        }
        val landingRoute = landing?.fullPathOrRoute() ?: route
        val resolution = landing?.let { precomputedData.routeResolver.resolve(it.route) }
            ?: precomputedData.routeResolver.resolve(landingRoute)
            ?: precomputedData.routeResolver.notFoundResolution()
            ?: throw RouteNotFoundException("Route not found: $landingRoute")
        return ResolvedTarget(landingRoute, resolution)
    }

    private fun RouteResolution.entryPath(): String = path ?: targetNavigatable.fullPathOrRoute()

    private suspend fun executeNavigation(builder: NavigationBuilder, pass: Pass): Execution {
        val guard = pass.guard
        val pending = getCurrentNavigationState().pendingNavigation
        val steps = builder.operations.map { it.resuming(pending) }
        val targets = LinkedHashMap<Int, ResolvedTarget>()
        for ((index, step) in steps.withIndex()) {
            val route = targetRoute(step) ?: continue
            guard?.check(route, precomputedData.routeResolver.resolve(route), step)?.let { return it }
            val target = resolveDestination(route, pass)
            guard?.check(target.route, target.resolution, step)?.let { return it }
            targets[index] = target
        }
        transitionSettleJob.load()?.join()
        val initialState = getCurrentNavigationState()
        var sim = StackSnapshot(
            currentEntry = initialState.currentEntry,
            backStack = initialState.backStack
        )
        val navigationStartEntry = sim.currentEntry
        var lastNavigatedEntry: NavigationEntry? = null

        val batchedActions = mutableListOf<NavigationAction>()
        var clearedFrom: List<NavigationEntry>? = null

        fun popUpToIndex(route: String, targetIndex: Int, inclusive: Boolean) {
            val trimmedBackStack = if (inclusive) {
                sim.backStack.take(targetIndex)
            } else {
                sim.backStack.take(targetIndex + 1)
            }

            val toReAdd = lastNavigatedEntry
            val entryToReAdd = if (toReAdd != null &&
                trimmedBackStack.none { it.stableKey == toReAdd.stableKey }) {
                toReAdd
            } else null

            if (trimmedBackStack.isEmpty() && entryToReAdd == null) {
                throw IllegalStateException(
                    "PopUpTo with inclusive=true on route '$route' would result in an empty back stack. " +
                    "Either use inclusive=false, or navigate to a new destination before calling popUpTo."
                )
            }

            batchedActions.add(NavigationAction.PopUpTo(route, inclusive, entryToReAdd, sim.backStack[targetIndex].stableKey))
            sim = NavigationStackMath.applyPopUpTo(sim, targetIndex, inclusive, entryToReAdd)
            lastNavigatedEntry = null
        }

        for ((index, step) in steps.withIndex()) {
            when (step.operation) {
                NavigationOperation.Navigate, NavigationOperation.ResumePending -> {
                    if (step.operation == NavigationOperation.ResumePending) {
                        if (step.target == null) continue
                        batchedActions.add(NavigationAction.ClearPendingNavigation)
                    }
                    val resolution = targets[index]?.resolution ?: continue

                    if (step.synthesizeBackstack) {
                        val destinationPath = resolution.entryPath()
                        val finalEntry = createNavigationEntry(step, resolution, destinationPath, 0)
                        val ancestors = synthesizeAncestorEntries(finalEntry, sim.backStack, pass)
                            .filter { guard == null || guard.admits(it, finalEntry) }

                        for (entry in ancestors) {
                            batchedActions.add(NavigationAction.Navigate(entry))
                            sim = NavigationStackMath.applyNavigate(sim, entry, false)
                            lastNavigatedEntry = entry
                        }

                        batchedActions.add(NavigationAction.Navigate(finalEntry, dismissModals = step.shouldDismissModals))
                        sim = NavigationStackMath.applyNavigate(sim, finalEntry, step.shouldDismissModals)
                        lastNavigatedEntry = finalEntry
                    } else {
                        val entryPath = resolution.entryPath()
                        val entry = createNavigationEntry(step, resolution, entryPath, 0)
                        val beneathModals = if (step.shouldDismissModals) {
                            sim.backStack.lastOrNull { it.navigatable !is Modal }
                        } else {
                            null
                        }
                        if (beneathModals != null && beneathModals.stableKey == entry.stableKey &&
                            beneathModals.stableKey != sim.currentEntry.stableKey
                        ) {
                            popUpToIndex(
                                beneathModals.location,
                                sim.backStack.indexOfLast { it.stableKey == beneathModals.stableKey },
                                inclusive = false
                            )
                            continue
                        }
                        if (sim.backStack.isNotEmpty() && entry.stableKey == sim.currentEntry.stableKey) {
                            ReaktivDebug.nav(
                                "navigateTo(${entry.route}) skipped, already the current entry"
                            )
                            continue
                        }
                        batchedActions.add(NavigationAction.Navigate(entry, dismissModals = step.shouldDismissModals))
                        sim = NavigationStackMath.applyNavigate(sim, entry, step.shouldDismissModals)
                        lastNavigatedEntry = entry
                    }
                }

                NavigationOperation.Replace -> {
                    val resolution = targets.getValue(index).resolution
                    val entryPath = resolution.entryPath()
                    val entry = createNavigationEntry(step, resolution, entryPath, sim.backStack.size)
                    batchedActions.add(NavigationAction.Replace(entry))
                    sim = NavigationStackMath.applyReplace(sim, entry)
                    lastNavigatedEntry = entry
                }

                NavigationOperation.Back -> {
                    batchedActions.add(NavigationAction.Back(step.expectedTopKey))
                    sim = NavigationStackMath.applyBack(sim)
                    lastNavigatedEntry = null
                }

                NavigationOperation.ClearBackStack -> {
                    clearedFrom = sim.backStack
                    batchedActions.add(NavigationAction.ClearBackstack)
                    sim = NavigationStackMath.applyClearBackstack(sim)
                    lastNavigatedEntry = null
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
                            val resolution = precomputedData.routeResolver.resolve(fallbackRoute) ?: throw RouteNotFoundException("Fallback route not found: $fallbackRoute")
                            val fallbackStep = step.copy(target = step.popUpToFallback)
                            guard?.check(fallbackRoute, resolution, fallbackStep)?.let { return it }
                            val newEntry = createNavigationEntry(
                                fallbackStep,
                                resolution,
                                resolution.entryPath(),
                                stackPosition = 1
                            )
                            batchedActions.add(NavigationAction.ClearBackstack)
                            sim = NavigationStackMath.applyClearBackstack(sim)
                            batchedActions.add(NavigationAction.Navigate(newEntry))
                            sim = NavigationStackMath.applyNavigate(sim, newEntry, false)
                            lastNavigatedEntry = newEntry
                        } else if (precomputedData.routeResolver.resolve(resolvedRoute) == null) {
                            throw RouteNotFoundException("popUpTo target '$resolvedRoute' is not a route in any graph")
                        } else {
                            throw PopUpToTargetNotInBackStackException(
                                targetRoute = resolvedRoute,
                                backStackPaths = sim.backStack.map { it.path }
                            )
                        }
                    } else {
                        popUpToIndex(resolvedRoute, targetIndex, step.popUpToInclusive)
                    }
                }

                NavigationOperation.DismissModal -> {
                    val modal = sim.backStack.lastOrNull { it.navigatable is Modal } ?: continue
                    if (modal.stableKey == sim.currentEntry.stableKey) {
                        batchedActions.add(NavigationAction.Back())
                        sim = NavigationStackMath.applyBack(sim)
                    } else {
                        val remaining = withoutEntry(sim.backStack, modal)
                        batchedActions.add(removal(remaining, sim.currentEntry))
                        sim = NavigationStackMath.applyTraverse(sim, remaining)
                    }
                    lastNavigatedEntry = null
                }

                NavigationOperation.ClearModals -> {
                    val lastScreen = sim.backStack.lastOrNull { it.navigatable is Screen } ?: continue
                    popUpToIndex(
                        lastScreen.location,
                        sim.backStack.indexOfLast { it.stableKey == lastScreen.stableKey },
                        inclusive = false
                    )
                }

                NavigationOperation.DeepLink -> throw IllegalStateException(
                    "navigateDeepLink runs as the only operation of its block and never reaches the batch"
                )
            }
        }

        val cleared = clearedFrom
        if (cleared != null && sim.backStack.all { it.navigatable.renderLayer == RenderLayer.SYSTEM }) {
            val keepFrom = cleared.indexOfLast { it.navigatable is Screen && it.navigatable.renderLayer != RenderLayer.SYSTEM }
            if (keepFrom >= 0) {
                for (entry in cleared.drop(keepFrom).filter { it.navigatable.renderLayer != RenderLayer.SYSTEM }) {
                    batchedActions.add(NavigationAction.Navigate(entry))
                    sim = NavigationStackMath.applyNavigate(sim, entry, false)
                }
            }
        }

        val allActions = batchedActions + pass.closing
        if (allActions.isEmpty()) return Execution.Committed
        val (commit, landed) = withContext(NonCancellable) {
            val result = if (allActions.size == 1) storeAccessor.dispatchAndAwait(allActions[0])
            else storeAccessor.dispatchAndAwait(NavigationAction.AtomicBatch(allActions))
            result to getCurrentNavigationState()
        }
        if (commit != DispatchResult.Processed) {
            currentCoroutineContext().ensureActive()
            return Execution.Blocked
        }

        if (batchedActions.lastStackChange() != null) scheduleTransitionSettle(navigationStartEntry, landed)
        return Execution.Committed
    }

    internal suspend fun commitTraverse(
        entries: List<NavigationEntry>,
        direction: TraverseDirection,
        presentation: TraversePresentation,
        expectedTopKey: String?
    ): Boolean {
        transitionSettleJob.load()?.join()
        val before = getCurrentNavigationState().currentEntry
        val action = NavigationAction.Traverse(entries, direction, presentation, expectedTopKey)
        withContext(NonCancellable) { storeAccessor.dispatchAndAwait(action) }
        val after = getCurrentNavigationState()
        if (after.lastNavigationAction !== action) return false
        scheduleTransitionSettle(before, after)
        return true
    }

    private fun scheduleTransitionSettle(previous: NavigationEntry, landed: NavigationState) {
        if (!landed.animatesInto(landed.currentEntry)) return
        val decision = determineAnimationDecision(
            previousEntry = previous,
            currentEntry = landed.currentEntry,
            graphDefinitions = precomputedData.graphDefinitions,
            isExplicitBackNavigation = landed.lastNavigationAction.impliesBackNavigation()
        )
        val animMs = decision.durationMillis.toLong()
        if (animMs > 0L) {
            transitionSettleJob.exchange(logicScope.launch { delay(animMs) })?.cancel()
        }
    }

    /**
     * Invokes lifecycle callbacks for entries that were added or removed from the backstack.
     */
    private suspend fun invokeLifecycleCallbacks(newBackStack: List<NavigationEntry>) {
        val newKeys = newBackStack.map { it.stableKey }.toSet()

        exitingLifecycles.removeAll { it.isRemoved }
        val addedEntries = newBackStack.filter { it.stableKey !in entryLifecycles }
        val removedLifecycles = entryLifecycles.filterKeys { it !in newKeys }

        val navigationStateFlow = storeAccessor.selectState<NavigationState>()

        addedEntries.forEach { entry ->
            exitingLifecycles.filter { it.entry.stableKey == entry.stableKey }.forEach { leaving ->
                exitingLifecycles.remove(leaving)
                endLifecycle(leaving)
            }
            val navigatable = entry.navigatable
            val lifecycleScope = CoroutineScope(storeAccessor.coroutineContext + SupervisorJob(logicJob))
            val lifecycle = BackstackLifecycle(entry, navigationStateFlow, storeAccessor, lifecycleScope)
            entryLifecycles[entry.stableKey] = lifecycle
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    navigatable.onLifecycleCreated(lifecycle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReaktivDebug.warn("Warning: onLifecycle failed for ${entry.path}: ${e.message}")
                }
            }
        }

        removedLifecycles.forEach { (key, lifecycle) ->
            entryLifecycles.remove(key)
            val navigatable = lifecycle.entry.navigatable
            val exitSpec = if (navigatable is Modal) modalExitSpec(navigatable) else popExitSpec(navigatable)
            val exitMs = exitSpec?.transition?.durationMillis?.toLong() ?: 0L
            if (exitMs <= 0L) {
                endLifecycle(lifecycle)
            } else {
                exitingLifecycles.add(lifecycle)
                logicScope.launch {
                    delay(exitMs)
                    endLifecycle(lifecycle)
                }
            }
        }
    }

    private fun endLifecycle(lifecycle: BackstackLifecycle) {
        try {
            lifecycle.runRemovalHandlers(RemovalReason.NAVIGATION)
        } finally {
            lifecycle.cancel()
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
            params = step.params + resolution.extractedParams,
            stackPosition = stackPosition
        ).let(::withTextPathParams)
    }

    private fun withTextPathParams(entry: NavigationEntry): NavigationEntry {
        val template = RouteTemplate.parse(entry.path)
        val missing = template.missing(entry.params::getString)
        if (missing.isNotEmpty()) throw MissingPathParamsException(entry.path, missing)
        val params = template.paramNames.fold(entry.params) { params, name ->
            if (params[name] is String) params else params.with(name, params.getString(name).orEmpty())
        }
        return if (params == entry.params) entry else entry.copy(params = params)
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

}
