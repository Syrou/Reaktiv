package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.dsl.DeepLinkAlias
import io.github.syrou.reaktiv.navigation.dsl.GraphBasedBuilder
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationProjection
import io.github.syrou.reaktiv.navigation.model.NavigationEntrySerializer
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.NavigationStackMath
import io.github.syrou.reaktiv.navigation.util.RouteResolver
import io.github.syrou.reaktiv.navigation.util.StackSnapshot
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.contextual
import kotlin.time.Duration

/**
 * The MVLI module that owns the navigation system.
 *
 * `NavigationModule` wires together the static graph definition, the mutable
 * [NavigationState], the pure [reducer], and the side-effecting [NavigationLogic].
 * It is the single source of truth for all navigation-related state in a Reaktiv store.
 *
 * Create an instance using the [createNavigationModule] top-level function or the
 * companion [create] helper:
 *
 * ```kotlin
 * val navModule = createNavigationModule {
 *     graph("root") {
 *         start(HomeScreen)
 *         screen(HomeScreen)
 *         screen(ProfileScreen)
 *         graph("auth") {
 *             start(LoginScreen)
 *             screen(LoginScreen)
 *         }
 *     }
 *     notFoundScreen(NotFoundScreen)
 * }
 * ```
 *
 * @see createNavigationModule
 * @see NavigationLogic
 * @see NavigationState
 */
public class NavigationModule internal constructor(
    private val rootGraph: NavigationGraph,
    private val notFoundScreen: Screen? = null,
    internal val crashScreen: Screen? = null,
    internal val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null,
    private val deepLinkAliases: List<DeepLinkAlias> = emptyList(),
    private val screenRetentionDuration: Duration,
    private val loadingModal: LoadingModal? = null
) : ModuleWithLogic<NavigationState, NavigationAction, NavigationLogic>, CustomTypeRegistrar {
    internal val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph, notFoundScreen, crashScreen, deepLinkAliases, loadingModal)
    }

    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.contextual(
            NavigationEntry::class,
            NavigationEntrySerializer { path ->
                precomputedData.routeToNavigatable[path] ?: precomputedData.notFoundScreen
            }
        )
    }

    /**
     * Internal accessor for graph definitions.
     * Used by rendering components to access static graph configuration.
     */
    internal fun getGraphDefinitions(): Map<String, NavigationGraph> {
        return precomputedData.graphDefinitions
    }

    /**
     * Get the full path for a Navigatable.
     *
     * @param navigatable The screen or modal to get the path for
     * @return The full path, or null if the navigatable is not registered
     */
    public fun getFullPath(navigatable: Navigatable): String? {
        return precomputedData.navigatableToFullPath[navigatable]
    }

    /**
     * Get all registered full paths mapped to their navigatables.
     */
    public fun getAllFullPaths(): Map<Navigatable, String> {
        return precomputedData.navigatableToFullPath
    }

    /**
     * Get the graph ID for a NavigationEntry.
     *
     * @param entry The navigation entry
     * @return The graph ID, or null if the entry's navigatable is not registered in a graph
     */
    public fun getGraphId(entry: NavigationEntry): String? {
        return precomputedData.navigatableToGraph[entry.navigatable]
    }

    /**
     * Returns the [LoadingModal] configured for this module, or `null` if none was provided.
     *
     * Used by [io.github.syrou.reaktiv.navigation.ui.NavigationRender] to render the
     * evaluation overlay directly when [NavigationState.isEvaluatingNavigation] is `true`.
     */
    public fun getLoadingModal(): LoadingModal? = loadingModal

    override val initialState: NavigationState by lazy {
        createInitialState()
    }

    private fun createInitialState(): NavigationState {
        val resolution = when (val dest = rootGraph.startDestination) {
            is StartDestination.DirectScreen -> {
                RouteResolution(
                    targetNavigatable = dest.screen,
                    owningGraphId = rootGraph.route,
                    extractedParams = Params.empty(),
                    requestedGraphId = rootGraph.route
                )
            }

            is StartDestination.GraphReference -> {
                precomputedData.routeResolver.resolve(dest.graphId)
                    ?: run {
                        val referencedGraph = precomputedData.graphDefinitions[dest.graphId]
                        val hasDynamicEntry = referencedGraph != null &&
                            precomputedData.graphEntries[dest.graphId]?.route != null
                        val fallback: Navigatable = loadingModal
                            ?: notFoundScreen
                            ?: if (hasDynamicEntry) {
                                throw IllegalStateException(
                                    "Root graph references '${dest.graphId}' which uses a dynamic start { } " +
                                    "but no loadingModal is defined. Provide a loadingModal() so there is a " +
                                    "screen to show while the entry condition is evaluated at startup."
                                )
                            } else {
                                throw IllegalStateException(
                                    "Could not resolve root graph reference to '${dest.graphId}'. " +
                                    "Ensure the graph is defined as a nested graph with a start destination."
                                )
                            }
                        RouteResolution(
                            targetNavigatable = fallback,
                            owningGraphId = rootGraph.route,
                            extractedParams = Params.empty(),
                            requestedGraphId = rootGraph.route
                        )
                    }
            }

            null -> {
                val fallbackNavigatable: Navigatable = loadingModal
                    ?: notFoundScreen
                    ?: if (rootGraph.entryDefinition != null) {
                        throw IllegalStateException(
                            "Root graph uses a dynamic start { } but no loadingModal is defined. " +
                            "A loadingModal is required as the initial screen while the entry " +
                            "condition is evaluated at startup."
                        )
                    } else {
                        throw IllegalStateException(
                            "Root graph has no startScreen/startGraph defined. " +
                            "Either define a static start destination via start(screen), " +
                            "provide a loadingModal() at the module level, " +
                            "or configure a notFoundScreen."
                        )
                    }
                RouteResolution(
                    targetNavigatable = fallbackNavigatable,
                    owningGraphId = rootGraph.route,
                    extractedParams = Params.empty(),
                    requestedGraphId = rootGraph.route
                )
            }
        }

        val initialPath = precomputedData.navigatableToFullPath[resolution.targetNavigatable]
            ?: resolution.targetNavigatable.route

        val initialEntry = resolution.targetNavigatable.toNavigationEntry(path = initialPath)

        val initialBackStack = listOf(initialEntry)

        val computedState = computeNavigationDerivedState(
            currentEntry = initialEntry,
            backStack = initialBackStack,
            precomputedData = precomputedData,
            existingModalContexts = emptyMap()
        )

        return NavigationState(
            currentEntry = initialEntry,
            backStack = initialBackStack,
            lastNavigationAction = null,
            screenRetentionDuration = screenRetentionDuration,
            derived = computedState,
            activeModalContexts = emptyMap(),
            pendingNavigation = null
        )
    }

    private fun reduceNavigationStateUpdate(
        state: NavigationState,
        currentEntry: NavigationEntry?,
        backStack: List<NavigationEntry>?,
        modalContexts: Map<String, ModalContext>?,
        navigationAction: NavigationAction? = null,
        pendingNavigation: PendingNavigation? = null,
        clearPendingNavigation: Boolean = false
    ): NavigationState {
        val newCurrentEntry = currentEntry ?: state.currentEntry
        val newBackStack = backStack ?: state.backStack
        val newModalContexts = modalContexts ?: state.activeModalContexts

        val computedState = computeNavigationDerivedState(
            currentEntry = newCurrentEntry,
            backStack = newBackStack,
            precomputedData = precomputedData,
            existingModalContexts = newModalContexts
        )

        val newPendingNavigation = when {
            clearPendingNavigation -> null
            pendingNavigation != null -> pendingNavigation
            else -> state.pendingNavigation
        }

        return state.copy(
            currentEntry = newCurrentEntry,
            backStack = newBackStack,
            lastNavigationAction = navigationAction,
            screenRetentionDuration = state.screenRetentionDuration,
            derived = computedState,
            activeModalContexts = newModalContexts,
            pendingNavigation = newPendingNavigation,
            isEvaluatingNavigation = state.isEvaluatingNavigation
        )
    }

    private fun NavigationState.toStackSnapshot(): StackSnapshot =
        StackSnapshot(currentEntry, backStack, activeModalContexts)

    private fun reduceAction(state: NavigationState, action: NavigationAction): NavigationState {
        val reduced = reduceNavigation(state, action)
        return when {
            action is NavigationAction.ScrubUpdate -> reduced
            reduced.activeScrub != null -> reduced.copy(activeScrub = null)
            else -> reduced
        }
    }

    private fun reduceNavigation(state: NavigationState, action: NavigationAction): NavigationState = when (action) {
        is NavigationAction.ScrubUpdate -> state.copy(activeScrub = action.scrub)

        is NavigationAction.ScrubEnd -> state.copy(activeScrub = null)

        is NavigationAction.AtomicBatch -> action.actions.fold(state, ::reduceAction)

        is NavigationAction.Navigate -> {
            val snapshot = NavigationStackMath.applyNavigate(
                state.toStackSnapshot(), action.entry, action.modalContext, action.dismissModals
            )
            reduceNavigationStateUpdate(state, snapshot.currentEntry, snapshot.backStack, snapshot.modalContexts, action)
        }

        is NavigationAction.Replace -> {
            val snapshot = NavigationStackMath.applyReplace(state.toStackSnapshot(), action.entry)
            reduceNavigationStateUpdate(state, snapshot.currentEntry, snapshot.backStack, snapshot.modalContexts, action)
        }

        is NavigationAction.Back -> {
            val expected = action.expectedTopKey
            when {
                state.backStack.size <= 1 -> state
                expected != null && state.currentEntry.stableKey != expected -> state
                else -> {
                    val snapshot = NavigationStackMath.applyBack(state.toStackSnapshot())
                    reduceNavigationStateUpdate(
                        state, snapshot.currentEntry, snapshot.backStack, snapshot.modalContexts, action
                    )
                }
            }
        }

        is NavigationAction.ClearBackstack -> {
            val snapshot = NavigationStackMath.applyClearBackstack(state.toStackSnapshot())
            reduceNavigationStateUpdate(state, snapshot.currentEntry, snapshot.backStack, snapshot.modalContexts, action)
        }

        is NavigationAction.PopUpTo -> {
            val targetIndex = precomputedData.routeResolver.findRouteInBackStack(action.route, state.backStack)
            val original = state.toStackSnapshot()
            val snapshot = NavigationStackMath.applyPopUpTo(original, targetIndex, action.inclusive, action.entryToReAdd)
            if (snapshot == original) {
                state
            } else {
                reduceNavigationStateUpdate(state, snapshot.currentEntry, snapshot.backStack, snapshot.modalContexts, action)
            }
        }

        is NavigationAction.SetPendingNavigation -> state.copy(
            pendingNavigation = action.pending
        )

        is NavigationAction.ClearPendingNavigation -> state.copy(
            pendingNavigation = null
        )

        is NavigationAction.BootstrapComplete -> state.copy(
            isBootstrapping = false
        )

        is NavigationAction.SetEvaluating -> state.copy(isEvaluatingNavigation = action.isEvaluating)
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = ::reduceAction

    override val createLogic: (storeAccessor: StoreAccessor) -> NavigationLogic = { storeAccessor ->
        NavigationLogic(
            storeAccessor = storeAccessor,
            precomputedData = precomputedData,
            onCrash = onCrash
        )
    }

    override val createMiddleware: (() -> Middleware) = {
        middleware@{ action, _, storeAccessor, updatedState ->
            if (action !is NavigationAction) {
                updatedState(action)
                return@middleware
            }
            val result = updatedState(action)
            val isScrub = action is NavigationAction.ScrubUpdate || action is NavigationAction.ScrubEnd
            if (!isScrub && result is NavigationState) {
                storeAccessor.selectLogic<NavigationLogic>().syncLifecycle(result.backStack)
            }
        }
    }

    public companion object {
        public inline fun create(block: GraphBasedBuilder.() -> Unit): NavigationModule {
            return GraphBasedBuilder().apply(block).build()
        }
    }
}

public data class PrecomputedNavigationData(
    val routeResolver: RouteResolver,
    val availableNavigatables: Map<String, Navigatable>,
    val graphDefinitions: Map<String, NavigationGraph>,
    val graphHierarchies: Map<String, List<String>>,
    val navigatableToGraph: Map<Navigatable, String>,
    val routeToNavigatable: Map<String, Navigatable>,
    val navigatableToFullPath: Map<Navigatable, String>,
    val notFoundScreen: Screen? = null,
    val crashScreen: Screen? = null,
    val interceptsByGraphId: Map<String, InterceptDefinition> = emptyMap(),
    val interceptsByPath: Map<String, InterceptDefinition> = emptyMap(),
    val graphEntries: Map<String, EntryDefinition> = emptyMap(),
    val deepLinkAliases: List<DeepLinkAlias> = emptyList(),
    val loadingModal: LoadingModal? = null
) {

    @Deprecated(
        "Identical to routeToNavigatable, which is also keyed by full path.",
        ReplaceWith("routeToNavigatable"),
        DeprecationLevel.WARNING
    )
    val allNavigatables: Map<String, Navigatable> get() = routeToNavigatable

    public companion object {
        public fun create(
            rootGraph: NavigationGraph,
            notFoundScreen: Screen? = null,
            crashScreen: Screen? = null,
            deepLinkAliases: List<DeepLinkAlias> = emptyList(),
            loadingModal: LoadingModal? = null
        ): PrecomputedNavigationData {
            val graphDefinitions = mutableMapOf<String, NavigationGraph>()
            val availableNavigatables = mutableMapOf<String, Navigatable>()
            val navigatableToGraph = mutableMapOf<Navigatable, String>()
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>()
            val interceptsByGraphId = mutableMapOf<String, InterceptDefinition>()
            val interceptsByPath = mutableMapOf<String, InterceptDefinition>()
            val graphEntries = mutableMapOf<String, EntryDefinition>()

            val parentGraphLookup = mutableMapOf<String, String>()

            fun collectGraphs(graph: NavigationGraph, inheritedIntercept: InterceptDefinition? = null) {
                val ownIntercept = graph.interceptDefinition
                val effectiveIntercept = when {
                    ownIntercept != null && inheritedIntercept != null ->
                        ownIntercept.prependOuter(inheritedIntercept)
                    ownIntercept != null -> ownIntercept
                    else -> inheritedIntercept
                }

                graphDefinitions[graph.route] = graph

                graph.entryDefinition?.let { def ->
                    graphEntries[graph.route] = def
                }

                if (effectiveIntercept != null) {
                    interceptsByGraphId[graph.route] = effectiveIntercept
                }

                graph.nestedGraphs.forEach { nestedGraph ->
                    parentGraphLookup[nestedGraph.route] = graph.route
                }

                graph.navigatables.forEach { navigatable ->
                    navigatableToGraph[navigatable] = graph.route

                    val fullPath = if (graph.route == "root") {
                        navigatable.route
                    } else {
                        val graphPath = buildGraphPathToRoot(graph.route, parentGraphLookup)
                        if (graphPath.isEmpty()) {
                            navigatable.route
                        } else {
                            "$graphPath/${navigatable.route}"
                        }
                    }

                    if (routeToNavigatable.containsKey(fullPath)) {
                        val existing = routeToNavigatable[fullPath]
                        throw IllegalStateException(
                            "Route collision detected: '$fullPath' is already registered to " +
                                    "'${existing?.route}'. Each screen must have a unique full path."
                        )
                    }
                    navigatableToFullPath[navigatable] = fullPath
                    routeToNavigatable[fullPath] = navigatable

                    if (graph.route == "root") {
                        availableNavigatables[navigatable.route] = navigatable
                    }

                    if (effectiveIntercept != null) {
                        interceptsByPath[fullPath] = effectiveIntercept
                    }
                }

                graph.navigatableIntercepts.forEach { (navigatable, interceptDef) ->
                    val fullPath = navigatableToFullPath[navigatable] ?: return@forEach
                    interceptsByPath[fullPath] = interceptDef
                }

                graph.nestedGraphs.forEach { collectGraphs(it, effectiveIntercept) }
            }

            collectGraphs(rootGraph)

            val graphHierarchies = mutableMapOf<String, List<String>>()
            for (graphId in graphDefinitions.keys) {
                val hierarchy = mutableListOf<String>()
                var currentGraphId: String? = graphId

                while (currentGraphId != null && currentGraphId != "root") {
                    hierarchy.add(0, currentGraphId)
                    currentGraphId = parentGraphLookup[currentGraphId]
                }

                graphHierarchies[graphId] = hierarchy
            }

            // Register special navigatables not discovered via graph traversal, before the
            // resolver is built from these maps.
            listOfNotNull(notFoundScreen, crashScreen, loadingModal).forEach { navigatable ->
                val path = navigatableToFullPath.getOrPut(navigatable) { navigatable.route }
                routeToNavigatable.getOrPut(path) { navigatable }
            }

            val routeResolver = RouteResolver.create(
                graphDefinitions = graphDefinitions,
                routeToNavigatable = routeToNavigatable.toMap(),
                navigatableToFullPath = navigatableToFullPath.toMap(),
                graphHierarchy = graphHierarchies.toMap(),
                notFoundScreen = notFoundScreen
            )

            deepLinkAliases.forEach { alias ->
                require(routeResolver.isFullPath(alias.targetRoute)) {
                    fullPathMessage(routeResolver, alias.targetRoute, "alias target for '${alias.pattern}'")
                }
            }

            return PrecomputedNavigationData(
                routeResolver = routeResolver,
                availableNavigatables = availableNavigatables,
                graphDefinitions = graphDefinitions.toMap(),
                graphHierarchies = graphHierarchies.toMap(),
                navigatableToGraph = navigatableToGraph.toMap(),
                routeToNavigatable = routeToNavigatable.toMap(),
                navigatableToFullPath = navigatableToFullPath.toMap(),
                notFoundScreen = notFoundScreen,
                crashScreen = crashScreen,
                interceptsByGraphId = interceptsByGraphId,
                interceptsByPath = interceptsByPath,
                graphEntries = graphEntries,
                deepLinkAliases = deepLinkAliases,
                loadingModal = loadingModal
            )
        }

        private fun buildGraphPathToRoot(graphId: String, parentLookup: Map<String, String>): String {
            if (graphId == "root") return ""

            val pathSegments = mutableListOf<String>()
            var currentGraphId: String? = graphId

            while (currentGraphId != null && currentGraphId != "root") {
                pathSegments.add(0, currentGraphId)
                currentGraphId = parentLookup[currentGraphId]
            }

            return pathSegments.joinToString("/")
        }
    }
}

internal fun fullPathMessage(resolver: RouteResolver, route: String, describedAs: String): String {
    val suggestions = resolver.fullPathSuggestions(route)
    val hint = when (suggestions.size) {
        0 -> "No registered path ends with '/$route'."
        1 -> "Did you mean '${suggestions.single()}'?"
        else -> "Candidates: ${suggestions.joinToString(", ")}."
    }
    return "The $describedAs '$route' is not a full path. Deep links must name the full path " +
        "from the root graph, such as 'home/releases/release-info'. $hint"
}

// Internal computation function
private fun computeNavigationDerivedState(
    currentEntry: NavigationEntry,
    backStack: List<NavigationEntry>,
    precomputedData: PrecomputedNavigationData,
    existingModalContexts: Map<String, ModalContext> = emptyMap()
): NavigationProjection {
    val orderedBackStack = backStack.mapIndexed { index, entry ->
        entry.copy(stackPosition = index)
    }

    val visibleLayers = computeVisibleLayers(orderedBackStack, existingModalContexts)

    val currentFullPath = precomputedData.routeResolver.buildFullPathForEntry(currentEntry)

    val currentPathSegments = currentFullPath.split("/").filter { it.isNotEmpty() }

    val currentGraphId = precomputedData.navigatableToGraph[currentEntry.navigatable]
    val currentGraphHierarchy = currentGraphId?.let { precomputedData.graphHierarchies[it] }
        ?: listOf(currentEntry.route)

    val breadcrumbs = buildBreadcrumbs(currentPathSegments, precomputedData.graphDefinitions)

    val isCurrentModal = currentEntry.navigatable is Modal
    val isCurrentScreen = currentEntry.navigatable is Screen
    val hasModalsInStack = backStack.any { it.navigatable is Modal }

    val entriesByLayer = visibleLayers.groupBy { it.navigatable.renderLayer }
    val contentLayerEntries = entriesByLayer[RenderLayer.CONTENT] ?: emptyList()
    val globalOverlayEntries = entriesByLayer[RenderLayer.GLOBAL_OVERLAY] ?: emptyList()
    val systemLayerEntries = entriesByLayer[RenderLayer.SYSTEM] ?: emptyList()

    val underlyingScreen = if (isCurrentModal) {
        findOriginalUnderlyingScreenForModal(currentEntry, orderedBackStack, existingModalContexts)
    } else null
    val modalsInStack = backStack.filter { it.navigatable is Modal }

    val underlyingScreenGraphHierarchy = underlyingScreen?.let { screen ->
        val graphId = precomputedData.navigatableToGraph[screen.navigatable]
        graphId?.let { precomputedData.graphHierarchies[it] } ?: listOf(screen.route)
    }

    val showsNavigationChrome = !isCurrentModal && currentGraphHierarchy.none { graphId ->
        precomputedData.graphDefinitions[graphId]?.declaration?.showsNavigationChrome == false
    }

    return NavigationProjection(
        visibleLayers = visibleLayers,
        currentFullPath = currentFullPath,
        currentGraphHierarchy = currentGraphHierarchy,
        breadcrumbs = breadcrumbs,
        isCurrentModal = isCurrentModal,
        isCurrentScreen = isCurrentScreen,
        hasModalsInStack = hasModalsInStack,
        contentLayerEntries = contentLayerEntries,
        globalOverlayEntries = globalOverlayEntries,
        systemLayerEntries = systemLayerEntries,
        underlyingScreen = underlyingScreen,
        modalsInStack = modalsInStack,
        underlyingScreenGraphHierarchy = underlyingScreenGraphHierarchy,
        showsNavigationChrome = showsNavigationChrome
    )
}

private fun computeVisibleLayers(
    orderedBackStack: List<NavigationEntry>,
    modalContexts: Map<String, ModalContext> = emptyMap()
): List<NavigationEntry> {
    if (orderedBackStack.isEmpty()) return emptyList()

    val currentEntry = orderedBackStack.last()

    if (currentEntry.navigatable is Modal) {
        val underlyingScreen = findOriginalUnderlyingScreenForModal(currentEntry, orderedBackStack, modalContexts)
        val layers = mutableListOf<NavigationEntry>()
        if (underlyingScreen != null) {
            layers.add(underlyingScreen)
        }
        layers.add(currentEntry)
        return layers
    }

    return listOf(currentEntry)
}

private fun buildBreadcrumbs(
    pathSegments: List<String>,
    graphDefinitions: Map<String, NavigationGraph>
): List<NavigationBreadcrumb> {
    val breadcrumbs = mutableListOf<NavigationBreadcrumb>()

    for (i in pathSegments.indices) {
        val segmentPath = pathSegments.take(i + 1).joinToString("/")
        val segment = pathSegments[i]
        val isGraph = graphDefinitions.containsKey(segment)

        breadcrumbs.add(
            NavigationBreadcrumb(
                label = segment.replaceFirstChar { it.uppercase() },
                path = segmentPath,
                isGraph = isGraph
            )
        )
    }

    return breadcrumbs
}

internal fun findOriginalUnderlyingScreenForModal(
    modalEntry: NavigationEntry,
    backStack: List<NavigationEntry>,
    modalContexts: Map<String, ModalContext> = emptyMap()
): NavigationEntry? {
    val modalContext = modalContexts[modalEntry.path]
    if (modalContext != null) {
        return modalContext.originalUnderlyingScreenEntry
    }

    val modalContextByEntry = modalContexts.values.find {
        it.modalEntry.path == modalEntry.path
    }
    if (modalContextByEntry != null) {
        return modalContextByEntry.originalUnderlyingScreenEntry
    }

    val modalIndex = backStack.indexOfFirst { it.path == modalEntry.path }
    if (modalIndex <= 0) return null

    return backStack.subList(0, modalIndex).lastOrNull {
        it.navigatable is Screen
    }
}

/**
 * DSL entry point for creating a [NavigationModule].
 *
 * Equivalent to `NavigationModule.create(block)`. Use this function at the call-site
 * where you assemble your store:
 *
 * ```kotlin
 * val store = createStore {
 *     module(
 *         createNavigationModule {
 *             graph("root") {
 *                 start(HomeScreen)
 *                 screen(HomeScreen)
 *                 screen(SettingsScreen)
 *             }
 *         }
 *     )
 * }
 * ```
 *
 * @param block Configuration lambda applied to a [GraphBasedBuilder].
 * @return A fully configured [NavigationModule] ready to be registered with the store.
 */
public fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}
