package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.CrashListener
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.DispatchResult
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision
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
import io.github.syrou.reaktiv.navigation.model.CacheKeySelector
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
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
import io.github.syrou.reaktiv.navigation.util.traceEntrySelection
import io.github.syrou.reaktiv.navigation.util.traceGuard
import io.github.syrou.reaktiv.navigation.util.traceNavigation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
public class NavigationLogic(
    public val storeAccessor: StoreAccessor,
    private val precomputedData: PrecomputedNavigationData,
    @Suppress("UNUSED_PARAMETER") parameterEncoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder(),
    private val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
) : ModuleLogic() {

    private val logicJob = SupervisorJob(storeAccessor.coroutineContext[Job])
    private val logicScope = CoroutineScope(storeAccessor.coroutineContext + logicJob)
    private val bootstrapCompleted = CompletableDeferred<Unit>()
    private val navigationMutex = Mutex()
    private val deepLinkStartedBeforeBootstrap = MutableStateFlow(false)
    private var bootstrapJob: Job? = null

    private val entryLifecycles = mutableMapOf<String, BackstackLifecycle>()
    private val exitingLifecycles = mutableSetOf<BackstackLifecycle>()

    private data class CachedEvaluation(val key: Any?, val value: Any?)

    private val evaluationCache = mutableMapOf<Any, CachedEvaluation>()
    private var transitionSettleJob: Job? = null

    init {
        registerCrashListenerIfNeeded()
        bootstrapRootEntryIfNeeded()
    }

    private fun isExternallyDriven(): Boolean = (storeAccessor as? Store)?.isExternallyDriven == true

    private fun bootstrapRootEntryIfNeeded() {
        if (isExternallyDriven()) {
            bootstrapCompleted.complete(Unit)
            return
        }

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
            logicScope.launch {
                storeAccessor.dispatchAndAwait(NavigationAction.BootstrapComplete)
                bootstrapCompleted.complete(Unit)
            }
            return
        }

        val bootstrapSelector = bootstrapEntry.route
        bootstrapJob = logicScope.launch {
            navigationMutex.withLock {
              withContext(NavigationLockMarker()) {
                try {
                val selectedNode = evaluateCached(bootstrapSelector, bootstrapEntry.cacheKey) {
                    bootstrapSelector.invoke(storeAccessor)
                }

                if (!deepLinkStartedBeforeBootstrap.value) {
                    val routeBuilder = NavigationBuilder(storeAccessor)
                    routeBuilder.clearBackStack()
                    val resolvedBootstrapNode = resolveEntryChain(selectedNode, bootstrapGraphId ?: "root")

                    val resolvedPath = resolvedBootstrapNode.fullPathOrRoute()
                    val resolvedResolution = precomputedData.routeResolver.resolve(resolvedPath)
                    val bootstrapStep = NavigationStep(NavigationOperation.Navigate)
                    val currentState = getCurrentNavigationState()

                    when (
                        val guard = evaluateGuard(
                            resolvedPath,
                            resolvedResolution,
                            bootstrapStep,
                            stackSurvivingInto(routeBuilder, currentState)
                        )
                    ) {
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
                            else throw IllegalStateException(
                                "A guard rejected the start destination '$resolvedPath' and no " +
                                    "notFoundScreen is configured, so there is nowhere to land. " +
                                    "Configure notFoundScreen(), or have the guard return " +
                                    "RedirectTo or PendAndRedirectTo instead of Reject."
                            )
                        }
                        is GuardEvaluation.Allow, null -> routeBuilder.navigateToNode(resolvedBootstrapNode)
                    }

                    routeBuilder.validate()
                    executeNavigation(routeBuilder) { it + listOf(NavigationAction.BootstrapComplete) }
                }
                } finally {
                    withContext(NonCancellable) {
                        bootstrapCompleted.complete(Unit)
                        if (getCurrentNavigationState().isEvaluatingNavigation) {
                            storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
                        }
                    }
                }
              }
            }
        }
    }

    override suspend fun onExternalControlChanged(externallyDriven: Boolean) {
        if (!externallyDriven) return

        bootstrapJob?.cancelAndJoin()
        bootstrapJob = null
        bootstrapCompleted.complete(Unit)

        val state = getCurrentNavigationState()
        if (state.isEvaluatingNavigation) {
            storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
        }
        if (state.isBootstrapping) {
            storeAccessor.dispatchAndAwait(NavigationAction.BootstrapComplete)
        }
    }

    override suspend fun beforeReset() {
        (entryLifecycles.values + exitingLifecycles).forEach { it.runRemovalHandlers(RemovalReason.RESET) }
        entryLifecycles.clear()
        exitingLifecycles.clear()
        evaluationCache.clear()
        transitionSettleJob = null
        bootstrapJob = null
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
        if (isExternallyDriven()) return NavigationOutcome.Dropped
        val builder = NavigationBuilder(storeAccessor)
        builder.apply { block() }
        builder.validate()
        val primaryStep = builder.operations.firstOrNull {
            it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
        }
        val targetRoute = primaryStep?.let {
            try { it.target?.resolve(precomputedData) } catch (e: Exception) { null }
        }
        val targetResolution = targetRoute?.let {
            precomputedData.routeResolver.resolve(it)
        }
        val isSystemLayer = targetResolution?.targetNavigatable?.renderLayer == RenderLayer.SYSTEM
        if (!isSystemLayer) {
            bootstrapCompleted.await()
        }
        return evaluateAndExecute(builder, targetRoute, targetResolution, bypassLock = isSystemLayer)
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

    /**
     * The full path of the outermost graph protected by [interceptDef], starting the search at
     * [innerGraphId], which is the graph the guarded target belongs to.
     *
     * A redirect lands as if the zone had been entered and the guard had answered at the door,
     * so the entries beneath it are the ones above this path. Synthesizing the zone's own start
     * under the redirect would put the very screen the guard refused one back press away.
     */
    private fun zoneBoundaryPath(innerGraphId: String, interceptDef: InterceptDefinition): String? {
        val chain = precomputedData.graphHierarchies[innerGraphId] ?: return null
        val boundary = chain.firstOrNull { graphId ->
            precomputedData.interceptsByGraphId[graphId] === interceptDef
        } ?: innerGraphId
        return precomputedData.routeResolver.fullPathForGraph(boundary) ?: boundary
    }

    /**
     * The entries of [state]'s back stack that are still standing when [builder]'s navigation
     * reaches its destination.
     *
     * Empty when the builder clears the stack, which is what a deep link does before it lands.
     */
    private fun stackSurvivingInto(
        builder: NavigationBuilder,
        state: NavigationState
    ): List<NavigationEntry> =
        if (builder.clearsBackStack()) emptyList() else state.backStack

    /**
     * Evaluates the intercept guards protecting [targetRoute], or `null` when the route is not
     * inside a protected zone.
     *
     * [stackBeforeNavigation] is the back stack this navigation actually starts from, which is
     * empty when the navigation clears the stack. A guard is skipped only when that stack is
     * already inside the same zone, because passing the guard to get there is what earns the
     * skip. Reading the live stack instead would let a deep link re-enter a zone unguarded on
     * the strength of entries it is about to discard.
     */
    private suspend fun evaluateGuard(
        targetRoute: String,
        targetResolution: RouteResolution?,
        primaryStep: NavigationStep,
        stackBeforeNavigation: List<NavigationEntry>
    ): GuardEvaluation? {
        if (isExternallyDriven()) return GuardEvaluation.Allow
        val pathIntercept = precomputedData.interceptsByPath[targetRoute]
        val graphZoneId = if (pathIntercept != null) null else listOfNotNull(
            precomputedData.routeResolver.canonicalGraphId(targetRoute),
            targetResolution?.requestedGraphId,
            targetResolution?.owningGraphId
        ).firstOrNull { precomputedData.interceptsByGraphId.containsKey(it) }

        val interceptDef = pathIntercept
            ?: graphZoneId?.let { precomputedData.interceptsByGraphId.getValue(it) }
            ?: return null
        val zoneKey = graphZoneId ?: targetRoute
        val zonePath = graphZoneId?.let { zoneBoundaryPath(it, interceptDef) }

        fun GuardResult.toGuardEvaluation(): GuardEvaluation = when (this) {
            is GuardResult.Allow -> GuardEvaluation.Allow
            is GuardResult.Reject -> GuardEvaluation.Reject
            is GuardResult.RedirectTo -> GuardEvaluation.Redirect(route, zonePath)
            is GuardResult.PendAndRedirectTo -> {
                val pending = PendingNavigation(
                    route = targetRoute,
                    params = primaryStep.params,
                    metadata = metadata,
                    displayHint = displayHint
                )
                val redirectResolution = precomputedData.routeResolver.resolve(route)
                val redirectPath = redirectResolution?.targetNavigatable?.let {
                    precomputedData.navigatableToFullPath[it]
                }
                GuardEvaluation.PendAndRedirect(
                    pending = pending,
                    redirectRoute = route,
                    alreadyAtRedirect = redirectPath == stackBeforeNavigation.lastOrNull()?.path,
                    zonePath = zonePath
                )
            }
        }

        val isAlreadyInZone = stackBeforeNavigation.any { entry ->
            precomputedData.interceptsByPath[entry.path] === interceptDef
        }
        if (isAlreadyInZone) return GuardEvaluation.Allow

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

        return evaluateCached(interceptDef.guard, interceptDef.cacheKey) {
            evaluateWithThreshold(interceptDef.loadingThreshold) {
                traceGuard(storeAccessor, "guard($zoneKey)", targetRoute) { interceptDef.guard(storeAccessor) }
            }
        }.toGuardEvaluation()
    }

    private suspend fun resolveEntryChain(
        initialNode: NavigationNode,
        initialRoute: String,
        entryMemo: MutableMap<String, NavigationNode>? = null
    ): NavigationNode {
        if (initialNode is Navigatable) return initialNode
        var resolvedNode: NavigationNode = initialNode
        val visitedRoutes = mutableSetOf(initialRoute)
        while (resolvedNode !is Navigatable) {
            val nextRoute = resolvedNode.route
            if (!visitedRoutes.add(nextRoute)) break
            val next = resolveEntryNavigatable(nextRoute) ?: break
            if (entryMemo != null) {
                precomputedData.routeResolver.canonicalGraphId(nextRoute)?.let { entryMemo[it] = next }
            }
            resolvedNode = next
        }
        return resolvedNode
    }

    private suspend fun resolveEntryNavigatable(targetRoute: String): NavigationNode? {
        val graphId = precomputedData.routeResolver.canonicalGraphId(targetRoute) ?: return null
        val entryDef = precomputedData.graphEntries[graphId] ?: return null
        val selector = entryDef.route ?: return null
        return evaluateCached(selector, entryDef.cacheKey) {
            evaluateWithThreshold(
                loadingThreshold = entryDef.loadingThreshold
            ) {
                traceEntrySelection(storeAccessor, "entry($graphId)", targetRoute) { selector.invoke(storeAccessor) }
            }
        }
    }

    private suspend fun resolveGraphEntryForSynthesis(
        graphPath: String,
        simulatedBackStack: List<NavigationEntry>,
        visited: Set<String> = emptySet(),
        entryMemo: Map<String, NavigationNode> = emptyMap()
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

        val existingInSimulated = simulatedBackStack.firstOrNull { entry ->
            precomputedData.navigatableToGraph[entry.navigatable] == effectiveGraphId
        }
        if (existingInSimulated != null) return existingInSimulated

        if (simulatedBackStack.isNotEmpty() || visited.isNotEmpty()) {
            val currentState = getCurrentNavigationState()
            val existingEntry = currentState.backStack.firstOrNull { entry ->
                precomputedData.navigatableToGraph[entry.navigatable] == effectiveGraphId
            }
            if (existingEntry != null) return existingEntry
        }

        val node = entryMemo[effectiveGraphId] ?: evaluateCached(selector, entryDef.cacheKey) {
            evaluateWithThreshold(entryDef.loadingThreshold) { selector.invoke(storeAccessor) }
        }
        return when {
            node is Navigatable ->
                node.toNavigationEntry(path = node.fullPathOrRoute(), params = Params.empty())
            precomputedData.routeResolver.canonicalGraphId(node.route) != null ->
                resolveGraphEntryForSynthesis(
                    node.route, simulatedBackStack, visited + setOfNotNull(graphPath, graphId), entryMemo
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
        clearsBackStack: Boolean,
        synthesizeBackstack: Boolean,
        zonePath: String?
    ) {
        val builder = NavigationBuilder(storeAccessor)
        if (clearsBackStack) builder.clearBackStack()
        builder.navigateTo(route, synthesizeBackstack = synthesizeBackstack)
        builder.validate()
        executeNavigation(builder, synthesisFloor = zonePath)
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
        primaryStep: NavigationStep
    ): NavigationOutcome? = when (guard) {
        is GuardEvaluation.Reject -> NavigationOutcome.Rejected
        is GuardEvaluation.Redirect -> {
            executeRedirect(
                route = guard.route,
                clearsBackStack = builder.clearsBackStack(),
                synthesizeBackstack = primaryStep.synthesizeBackstack,
                zonePath = guard.zonePath
            )
            NavigationOutcome.Redirected(guard.route)
        }
        is GuardEvaluation.PendAndRedirect -> {
            storeAccessor.dispatchAndAwait(NavigationAction.SetPendingNavigation(guard.pending))
            if (!guard.alreadyAtRedirect) {
                executeRedirect(
                    route = guard.redirectRoute,
                    clearsBackStack = true,
                    synthesizeBackstack = primaryStep.synthesizeBackstack,
                    zonePath = guard.zonePath
                )
            }
            NavigationOutcome.Redirected(guard.redirectRoute)
        }
        is GuardEvaluation.Allow, null -> null
    }

    private fun peerHostsAbove(route: String): List<String> =
        precomputedData.routeResolver.buildPathHierarchy(route).dropLast(1).filter { graphPath ->
            val graphId = precomputedData.routeResolver.canonicalGraphId(graphPath) ?: return@filter false
            precomputedData.graphDefinitions[graphId]?.declaration?.startAnchorsChildren == false
        }

    private suspend fun synthesizeAncestorEntries(
        route: String,
        simulatedBackStack: List<NavigationEntry>,
        seenPaths: MutableSet<String>,
        includeRoot: Boolean,
        entryMemo: Map<String, NavigationNode> = emptyMap(),
        floor: String? = null
    ): List<NavigationEntry> {
        val synthesized = mutableListOf<NavigationEntry>()
        var stack = simulatedBackStack
        val peerHosts = peerHostsAbove(route)
        if (includeRoot) {
            val rootEntry = resolveGraphEntryForSynthesis("root", stack, entryMemo = entryMemo)
            val rootInsidePeerHost = rootEntry != null && peerHosts.any { rootEntry.path.startsWith("$it/") }
            if (rootEntry != null && !rootInsidePeerHost && seenPaths.add(rootEntry.path)) {
                synthesized.add(rootEntry)
                stack = stack + rootEntry
            }
        }
        for (intermediatePath in precomputedData.routeResolver.buildPathHierarchy(route).dropLast(1)) {
            if (floor != null && (intermediatePath == floor || intermediatePath.startsWith("$floor/"))) continue
            if (intermediatePath in peerHosts) continue
            val entry = resolveGraphEntryForSynthesis(intermediatePath, stack, entryMemo = entryMemo) ?: continue
            if (!seenPaths.add(entry.path)) continue
            synthesized.add(entry)
            stack = stack + entry
        }
        return synthesized
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
     * @param bypassLock Runs without waiting for the navigation lock, for navigations that must not
     *   queue behind another. Guards and multi-step blocks still serialise through the store's own
     *   ordered dispatch, so this only skips the evaluation lock, not state consistency.
     */
    private suspend fun evaluateAndExecute(
        builder: NavigationBuilder,
        precomputedTargetRoute: String? = null,
        precomputedTargetResolution: RouteResolution? = null,
        bypassLock: Boolean = false
    ): NavigationOutcome = traceNavigation(
        storeAccessor,
        precomputedTargetRoute ?: builder.describeTarget()
    ) {
        if (bypassLock || currentCoroutineContext()[NavigationLockKey] != null) {
            return@traceNavigation performEvaluateAndExecute(
                builder, precomputedTargetRoute, precomputedTargetResolution
            )
        }
        navigationMutex.lock()
        var settleJob: Job? = null
        val outcome = try {
            val result = CompletableDeferred<NavigationOutcome>()
            val work = CoroutineScope(currentCoroutineContext().minusKey(Job) + logicJob)
                .launch(NavigationLockMarker()) {
                    try {
                        result.complete(
                            performEvaluateAndExecute(builder, precomputedTargetRoute, precomputedTargetResolution)
                        )
                    } catch (e: Throwable) {
                        result.completeExceptionally(e)
                    }
                }
            work.invokeOnCompletion { cause ->
                if (cause != null) result.completeExceptionally(cause)
            }
            withContext(NonCancellable) { result.await() }
        } finally {
            settleJob = transitionSettleJob
            navigationMutex.unlock()
        }
        if (currentCoroutineContext().isActive) {
            settleJob?.join()
        }
        outcome
    }

    private suspend fun performEvaluateAndExecute(
        builder: NavigationBuilder,
        precomputedTargetRoute: String? = null,
        precomputedTargetResolution: RouteResolution? = null
    ): NavigationOutcome {
        return run {
                try {
                    val primaryStep = builder.operations.firstOrNull {
                        it.operation == NavigationOperation.Navigate || it.operation == NavigationOperation.Replace
                    }

                    if (primaryStep == null) {
                        executeNavigation(builder)
                        return@run NavigationOutcome.Success
                    }

                    val targetRoute = precomputedTargetRoute ?: try {
                        primaryStep.target?.resolve(precomputedData)
                    } catch (e: Exception) {
                        null
                    }

                    if (targetRoute == null) {
                        executeNavigation(builder)
                        return@run NavigationOutcome.Success
                    }

                    val targetResolution = precomputedTargetResolution
                        ?: precomputedData.routeResolver.resolve(targetRoute)

                    val currentState = getCurrentNavigationState()

                    val guardStack = stackSurvivingInto(builder, currentState)
                    val initialGuard = evaluateGuard(targetRoute, targetResolution, primaryStep, guardStack)
                    guardOutcome(initialGuard, builder, primaryStep)?.let { return@run it }

                    val owningGraphId = precomputedData.routeResolver.canonicalGraphId(targetRoute)
                    val isDynamicGraphTarget = owningGraphId != null &&
                            precomputedData.graphEntries[owningGraphId]?.route != null
                    val entryNode: NavigationNode? = if (isDynamicGraphTarget) {
                        val existingEntry = currentState.backStack.firstOrNull { entry ->
                            precomputedData.navigatableToGraph[entry.navigatable] == owningGraphId
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
                        val entryMemo = mutableMapOf<String, NavigationNode>()
                        owningGraphId?.let { entryMemo[it] = entryNode }
                        val resolvedNode = resolveEntryChain(entryNode, targetRoute, entryMemo)
                        val resolvedResolution = if (resolvedNode is Navigatable) {
                            RouteResolution(
                                targetNavigatable = resolvedNode,
                                owningGraphId = precomputedData.navigatableToGraph[resolvedNode] ?: "root",
                                extractedParams = Params.empty()
                            )
                        } else {
                            precomputedData.routeResolver.resolve(resolvedNode.route)
                        }

                        if (initialGuard == null) {
                            val resolvedRoute = resolvedNode.fullPathOrRoute()
                            val stateAfterResolution = getCurrentNavigationState()
                            val resolvedGuard = evaluateGuard(
                                resolvedRoute,
                                resolvedResolution,
                                primaryStep,
                                stackSurvivingInto(builder, stateAfterResolution)
                            )
                            guardOutcome(resolvedGuard, builder, primaryStep)?.let { return@run it }
                        }

                        val routeBuilder = NavigationBuilder(storeAccessor)
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
                        executeNavigation(routeBuilder, primaryResolution = resolvedResolution, entryMemo = entryMemo)
                        return@run NavigationOutcome.Success
                    }

                    executeNavigation(builder, primaryResolution = targetResolution)
                    NavigationOutcome.Success
                } finally {
                    withContext(NonCancellable) {
                        if (getCurrentNavigationState().isEvaluatingNavigation) {
                            storeAccessor.dispatchAndAwait(NavigationAction.SetEvaluating(false))
                        }
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
        val state = getCurrentNavigationState()
        val modal = state.backStack.lastOrNull { it.navigatable is Modal } ?: return

        if (modal.stableKey == state.currentEntry.stableKey) {
            navigateBack()
            return
        }

        storeAccessor.dispatchAndAwait(
            NavigationAction.PopUpTo(
                route = modal.path,
                inclusive = true,
                entryToReAdd = state.currentEntry
            )
        )
    }

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
            targetRoute = cleanRoute
            targetParams = Params.fromMap(queryParams) + params
        }
        val notFound = if (precomputedData.routeResolver.isFullPath(targetRoute)) {
            null
        } else {
            val describedAs = if (alias != null) "alias target for '$cleanRoute'" else "deep link"
            val message = fullPathMessage(precomputedData.routeResolver, targetRoute, describedAs)
            val fallback = precomputedData.notFoundScreen ?: throw RouteNotFoundException(message)
            ReaktivDebug.warn("$message Landing on the notFoundScreen '${fallback.route}' instead.")
            fallback
        }

        deepLinkStartedBeforeBootstrap.value = true
        val bootstrapWasComplete = bootstrapCompleted.isCompleted
        if (!bootstrapWasComplete) {
            bootstrapCompleted.await()
        }

        val builder = NavigationBuilder(storeAccessor)
        builder.clearBackStack()
        builder.params(targetParams)
        if (notFound == null) {
            builder.navigateTo(targetRoute, synthesizeBackstack = true)
        } else {
            builder.navigateTo(notFound)
        }
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
        entryMemo: Map<String, NavigationNode> = emptyMap(),
        synthesisFloor: String? = null,
        wrapActions: (List<NavigationAction>) -> List<NavigationAction> = { it }
    ) {
        transitionSettleJob?.join()
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
                        precomputedData.routeResolver.resolve(resolvedRoute)
                            ?: precomputedData.routeResolver.notFoundResolution()
                            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")
                    }

                    if (step.synthesizeBackstack) {
                        val destinationPath = resolution.targetNavigatable.fullPathOrRoute()
                        val seenPaths = (sim.backStack.map { it.path } + destinationPath).toMutableSet()

                        for (entry in synthesizeAncestorEntries(destinationPath, sim.backStack, seenPaths, includeRoot = true, entryMemo, synthesisFloor)) {
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
                        if (sim.backStack.isNotEmpty() && entry.stableKey == sim.currentEntry.stableKey) {
                            ReaktivDebug.nav(
                                "navigateTo(${entry.route}) skipped, already the current entry"
                            )
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
                        precomputedData.routeResolver.resolve(resolvedRoute)
                            ?: precomputedData.routeResolver.notFoundResolution()
                            ?: throw RouteNotFoundException("Route not found: $resolvedRoute")
                    }
                    val entryPath = resolution.targetNavigatable.fullPathOrRoute()
                    val entry = createNavigationEntry(step, resolution, entryPath, sim.backStack.size)
                    batchedActions.add(NavigationAction.Replace(entry))
                    sim = NavigationStackMath.applyReplace(sim, entry)
                    lastNavigatedEntry = entry
                }

                NavigationOperation.Back -> {
                    batchedActions.add(NavigationAction.Back())
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

                    var pendingRoute = pending.route
                    val pendingEntryMemo = mutableMapOf<String, NavigationNode>()
                    var pendingResolution = precomputedData.routeResolver.resolve(pendingRoute)
                    if (pendingResolution == null) {
                        val pendingEntryNode = resolveEntryNavigatable(pendingRoute) ?: continue
                        precomputedData.routeResolver.canonicalGraphId(pendingRoute)?.let {
                            pendingEntryMemo[it] = pendingEntryNode
                        }
                        val resolvedNode = resolveEntryChain(pendingEntryNode, pendingRoute, pendingEntryMemo)
                        pendingRoute = resolvedNode.fullPathOrRoute()
                        pendingResolution = if (resolvedNode is Navigatable) {
                            RouteResolution(
                                targetNavigatable = resolvedNode,
                                owningGraphId = precomputedData.navigatableToGraph[resolvedNode] ?: "root",
                                extractedParams = Params.empty()
                            )
                        } else {
                            precomputedData.routeResolver.resolve(resolvedNode.route)
                        }
                    }
                    if (pendingResolution == null) continue

                    val destinationPath = pendingResolution.targetNavigatable.fullPathOrRoute()
                    val seenPaths = (sim.backStack.map { it.path } + destinationPath).toMutableSet()

                    for (entry in synthesizeAncestorEntries(
                        destinationPath, sim.backStack, seenPaths,
                        includeRoot = sim.backStack.isEmpty(),
                        entryMemo = pendingEntryMemo
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
                            val resolution = precomputedData.routeResolver.resolve(fallbackRoute) ?: throw RouteNotFoundException("Fallback route not found: $fallbackRoute")
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
        if (allActions.isEmpty()) return
        val commit = withContext(NonCancellable) {
            if (allActions.size == 1) storeAccessor.dispatchAndAwait(allActions[0])
            else storeAccessor.dispatchAndAwait(NavigationAction.AtomicBatch(allActions))
        }
        if (commit == DispatchResult.Blocked) currentCoroutineContext().ensureActive()

        val decision = determineAnimationDecision(
            previousEntry = navigationStartEntry,
            currentEntry = sim.currentEntry,
            graphDefinitions = precomputedData.graphDefinitions,
            isExplicitBackNavigation = batchedActions.any { it is NavigationAction.Back }
        )
        val enterMs = if (decision.shouldAnimateEnter) {
            decision.enterTransition.durationMillis.toLong()
        } else 0L
        val exitMs = if (decision.shouldAnimateExit) {
            decision.exitTransition.durationMillis.toLong()
        } else 0L
        val animMs = maxOf(enterMs, exitMs)
        if (animMs > 0L) {
            transitionSettleJob?.cancel()
            transitionSettleJob = logicScope.launch { delay(animMs) }
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
            val navigatable = entry.navigatable
            try {
                val lifecycleScope = CoroutineScope(storeAccessor.coroutineContext + SupervisorJob(logicJob))
                val lifecycle = BackstackLifecycle(entry, navigationStateFlow, storeAccessor, lifecycleScope)
                entryLifecycles[entry.stableKey] = lifecycle
                navigatable.onLifecycleCreated(lifecycle)
            } catch (e: Exception) {
                ReaktivDebug.warn("Warning: onLifecycle failed for ${entry.path}: ${e.message}")
            }
        }

        removedLifecycles.forEach { (key, lifecycle) ->
            entryLifecycles.remove(key)
            val exitMs = popExitSpec(lifecycle.entry.navigatable)?.transition?.durationMillis?.toLong() ?: 0L
            if (exitMs <= 0L) {
                lifecycle.runRemovalHandlers(RemovalReason.NAVIGATION)
                lifecycle.cancel()
            } else {
                exitingLifecycles.add(lifecycle)
                logicScope.launch {
                    delay(exitMs)
                    lifecycle.runRemovalHandlers(RemovalReason.NAVIGATION)
                    lifecycle.cancel()
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
            findOriginalUnderlyingScreenForModal(currentEntry, backStack, activeModalContexts)
        else currentEntry
        return underlying?.let {
            ModalContext(
                modalEntry = entry,
                originalUnderlyingScreenEntry = it
            )
        }
    }

    private suspend fun getCurrentNavigationState(): NavigationState {
        return storeAccessor.selectState<NavigationState>().first()
    }

}
