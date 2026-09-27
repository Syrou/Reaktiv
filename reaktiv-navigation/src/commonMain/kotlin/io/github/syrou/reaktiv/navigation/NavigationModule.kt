package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.definition.ColdStartPlaceholder
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.dsl.DeepLinkAlias
import io.github.syrou.reaktiv.navigation.dsl.GraphBasedBuilder
import io.github.syrou.reaktiv.navigation.exception.MissingPathParamsException
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.history.BrowserHistorySetup
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationProjection
import io.github.syrou.reaktiv.navigation.model.NavigationEntrySerializer
import io.github.syrou.reaktiv.navigation.model.toNavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.NavigationStackMath
import io.github.syrou.reaktiv.navigation.util.RouteResolver
import io.github.syrou.reaktiv.navigation.util.StackSnapshot
import io.github.syrou.reaktiv.navigation.util.buildLinkMap
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.contextual
import kotlin.time.Duration
import io.github.syrou.reaktiv.navigation.util.GraphIndex
import io.github.syrou.reaktiv.navigation.util.ROOT_GRAPH
import io.github.syrou.reaktiv.navigation.util.normalizePath
import io.github.syrou.reaktiv.navigation.util.RouteTemplate

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
    private val loadingModal: LoadingModal? = null,
    private val browserHistory: BrowserHistorySetup? = null
) : ModuleWithLogic<NavigationState, NavigationAction, NavigationLogic>, CustomTypeRegistrar {
    internal val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph, notFoundScreen, crashScreen, deepLinkAliases, loadingModal)
    }

    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.contextual(
            NavigationEntry::class,
            NavigationEntrySerializer { path ->
                precomputedData.routeToNavigatable[path]
                    ?: ColdStartPlaceholder.takeIf { path == it.route }
                    ?: precomputedData.notFoundScreen
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

    public fun locationOf(navigatable: Navigatable, vararg params: Pair<String, Any>): String =
        locationOf(navigatable, Params.fromMap(params.toMap()))

    public fun locationOf(navigatable: Navigatable, params: Params): String {
        val path = precomputedData.navigatableToFullPath[navigatable]
            ?: throw RouteNotFoundException("'${navigatable.route}' is not registered in any navigation graph")
        return when (val filled = RouteTemplate.parse(path).fill(params::getString)) {
            is RouteTemplate.Fill.Filled -> filled.location
            is RouteTemplate.Fill.Missing -> throw MissingPathParamsException(path, filled.names)
        }
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

    public fun linkMap(): NavigationLinkMap = linkMap

    private val linkMap: NavigationLinkMap by lazy {
        buildLinkMap(precomputedData, browserHistory?.webLocation?.prefix)
    }

    internal val documentTitle: ((String?) -> String?)?
        get() = browserHistory?.takeIf { it.isAvailable }?.documentTitle

    override val initialState: NavigationState by lazy {
        createInitialState()
    }

    private fun createInitialState(): NavigationState {
        val staticStart = precomputedData.staticRootStart
        val initial: Navigatable = when {
            staticStart != null &&
                (browserHistory?.isAvailable == true || precomputedData.staticRootStartIsGuarded) -> ColdStartPlaceholder
            staticStart != null -> staticStart
            else -> loadingModal ?: notFoundScreen ?: throw IllegalStateException(missingStartMessage())
        }

        val initialPath = precomputedData.navigatableToFullPath[initial] ?: initial.route

        val initialEntry = initial.toNavigationEntry(path = initialPath)

        val initialBackStack = listOf(initialEntry)

        val computedState = computeNavigationDerivedState(
            currentEntry = initialEntry,
            backStack = initialBackStack,
            precomputedData = precomputedData
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

    private fun missingStartMessage(): String = when (val dest = rootGraph.startDestination) {
        is StartDestination.GraphReference -> if (precomputedData.graphEntries[dest.graphId]?.route != null) {
            "Root graph references '${dest.graphId}' which uses a dynamic start { } " +
                "but no loadingModal is defined. Provide a loadingModal() so there is a " +
                "screen to show while the entry condition is evaluated at startup."
        } else {
            "Could not resolve root graph reference to '${dest.graphId}'. " +
                "Ensure the graph is defined as a nested graph with a start destination."
        }
        else -> if (rootGraph.entryDefinition != null) {
            "Root graph uses a dynamic start { } but no loadingModal is defined. " +
                "A loadingModal is required as the initial screen while the entry " +
                "condition is evaluated at startup."
        } else {
            "Root graph has no startScreen/startGraph defined. " +
                "Either define a static start destination via start(screen), " +
                "provide a loadingModal() at the module level, " +
                "or configure a notFoundScreen."
        }
    }

    private fun reduceNavigationStateUpdate(
        state: NavigationState,
        backStack: List<NavigationEntry>,
        navigationAction: NavigationAction
    ): NavigationState {
        val currentEntry = backStack.lastOrNull() ?: state.currentEntry
        return state.copy(
            currentEntry = currentEntry,
            backStack = backStack,
            lastNavigationAction = navigationAction,
            derived = computeNavigationDerivedState(currentEntry, backStack, precomputedData),
            activeModalContexts = NavigationStackMath.deriveModalContexts(backStack)
        )
    }

    private fun NavigationState.toStackSnapshot(): StackSnapshot =
        StackSnapshot(currentEntry, backStack)

    private fun reduceAction(state: NavigationState, action: NavigationAction): NavigationState {
        val reduced = reduceNavigation(state, action)
        val scrub = state.activeScrub
        return when {
            action is NavigationAction.ScrubUpdate -> reduced
            scrub != null -> reduced.copy(
                activeScrub = null,
                lastNavigationAction = reduced.presentedBy(scrub, state) ?: reduced.lastNavigationAction
            )
            else -> reduced
        }
    }

    private fun NavigationState.presentedBy(scrub: ScrubState, before: NavigationState): NavigationAction? {
        val change = lastNavigationAction?.takeIf { it !== before.lastNavigationAction } ?: return null
        if (before.currentEntry.stableKey != scrub.topKey) return null
        val landed = scrub.revealedKey?.let { currentEntry.stableKey == it }
            ?: backStack.none { it.stableKey == scrub.topKey }
        if (!landed) return null
        return when (change) {
            is NavigationAction.Back -> change.copy(presentation = TraversePresentation.AlreadyPresented)
            is NavigationAction.PopUpTo -> change.copy(presentation = TraversePresentation.AlreadyPresented)
            else -> null
        }
    }

    private fun reduceNavigation(state: NavigationState, action: NavigationAction): NavigationState = when (action) {
        is NavigationAction.ScrubUpdate -> state.copy(activeScrub = action.scrub)

        is NavigationAction.ScrubEnd -> state.copy(activeScrub = null)

        is NavigationAction.AtomicBatch -> action.actions.fold(state, ::reduceAction)

        is NavigationAction.Navigate -> {
            val snapshot = NavigationStackMath.applyNavigate(
                state.toStackSnapshot(), action.entry, action.dismissModals
            )
            reduceNavigationStateUpdate(state, snapshot.backStack, action)
        }

        is NavigationAction.Replace -> {
            val snapshot = NavigationStackMath.applyReplace(state.toStackSnapshot(), action.entry)
            reduceNavigationStateUpdate(state, snapshot.backStack, action)
        }

        is NavigationAction.Back -> {
            val expected = action.expectedTopKey
            when {
                state.backStack.size <= 1 -> state
                expected != null && state.currentEntry.stableKey != expected -> state
                else -> {
                    val snapshot = NavigationStackMath.applyBack(state.toStackSnapshot())
                    reduceNavigationStateUpdate(state, snapshot.backStack, action)
                }
            }
        }

        is NavigationAction.ClearBackstack -> {
            val snapshot = NavigationStackMath.applyClearBackstack(state.toStackSnapshot())
            reduceNavigationStateUpdate(state, snapshot.backStack, action)
        }

        is NavigationAction.PopUpTo -> {
            val targetIndex = action.targetKey?.let { key -> state.backStack.indexOfLast { it.stableKey == key } }
                ?: precomputedData.routeResolver.findRouteInBackStack(action.route, state.backStack)
            val original = state.toStackSnapshot()
            val snapshot = NavigationStackMath.applyPopUpTo(original, targetIndex, action.inclusive, action.entryToReAdd)
            if (snapshot == original) {
                state
            } else {
                reduceNavigationStateUpdate(state, snapshot.backStack, action)
            }
        }

        is NavigationAction.Traverse -> {
            val expected = action.expectedTopKey
            if (action.entries.isEmpty() || (expected != null && state.currentEntry.stableKey != expected)) {
                state
            } else {
                val snapshot = NavigationStackMath.applyTraverse(state.toStackSnapshot(), action.entries)
                reduceNavigationStateUpdate(state, snapshot.backStack, action)
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

        is NavigationAction.SetStartFailure -> state.copy(startFailure = action.failure)
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = ::reduceAction

    override val createLogic: (storeAccessor: StoreAccessor) -> NavigationLogic = { storeAccessor ->
        NavigationLogic(storeAccessor, precomputedData, onCrash, browserHistory)
    }

    override val createMiddleware: (() -> Middleware) = {
        middleware@{ action, _, storeAccessor, updatedState ->
            if (action !is NavigationAction) {
                updatedState(action)
                return@middleware
            }
            val result = updatedState(action)
            if (result is NavigationState) {
                storeAccessor.selectLogic<NavigationLogic>().onCommitted(action, result)
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
    @Deprecated(
        "Never read. Root navigatables are in routeToNavigatable under their route, which is their full path.",
        ReplaceWith("routeToNavigatable"),
        DeprecationLevel.WARNING
    )
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

    internal val graphIndex: GraphIndex get() = routeResolver.graphIndex

    internal fun isAddressable(navigatable: Navigatable): Boolean =
        navigatable.renderLayer != RenderLayer.SYSTEM &&
            navigatable !is LoadingModal &&
            navigatable != crashScreen

    internal val staticRootStart: Navigatable? by lazy {
        when (val dest = graphDefinitions[ROOT_GRAPH]?.startDestination) {
            is StartDestination.DirectScreen -> dest.screen
            is StartDestination.GraphReference -> routeResolver.resolve(dest.graphId)?.targetNavigatable
            null -> null
        }
    }

    internal val staticRootStartIsGuarded: Boolean by lazy {
        val path = when (val dest = graphDefinitions[ROOT_GRAPH]?.startDestination) {
            is StartDestination.DirectScreen -> navigatableToFullPath[dest.screen] ?: dest.screen.route
            is StartDestination.GraphReference -> routeResolver.resolve(dest.graphId)?.let {
                it.path ?: navigatableToFullPath[it.targetNavigatable]
            }
            null -> null
        }
        path != null && interceptsByPath[path] != null
    }

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

            fun collectGraphs(graph: NavigationGraph, graphPath: String, inheritedIntercept: InterceptDefinition?) {
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

                graph.navigatables.forEach { navigatable ->
                    navigatableToGraph[navigatable] = graph.route

                    val fullPath = if (graphPath.isEmpty()) navigatable.route else "$graphPath/${navigatable.route}"

                    if (routeToNavigatable.containsKey(fullPath)) {
                        val existing = routeToNavigatable[fullPath]
                        throw IllegalStateException(
                            "Route collision detected: '$fullPath' is already registered to " +
                                    "'${existing?.route}'. Each screen must have a unique full path."
                        )
                    }
                    navigatableToFullPath[navigatable] = fullPath
                    routeToNavigatable[fullPath] = navigatable

                    if (graph.route == ROOT_GRAPH) {
                        availableNavigatables[navigatable.route] = navigatable
                    }

                    val screenIntercept = graph.navigatableIntercepts[navigatable]
                    val pathIntercept = if (screenIntercept != null && effectiveIntercept != null) {
                        screenIntercept.prependOuter(effectiveIntercept)
                    } else {
                        screenIntercept ?: effectiveIntercept
                    }
                    if (pathIntercept != null) {
                        interceptsByPath[fullPath] = pathIntercept
                    }
                }

                graph.nestedGraphs.forEach { nested ->
                    val nestedPath = if (graphPath.isEmpty()) nested.route else "$graphPath/${nested.route}"
                    collectGraphs(nested, nestedPath, effectiveIntercept)
                }
            }

            collectGraphs(rootGraph, "", null)

            val graphs = graphDefinitions.toMap()
            val graphIndex = GraphIndex.of(graphs)

            // Register special navigatables not discovered via graph traversal, before the
            // resolver is built from these maps.
            listOfNotNull(notFoundScreen, crashScreen, loadingModal).forEach { navigatable ->
                val path = navigatableToFullPath.getOrPut(navigatable) { navigatable.route }
                routeToNavigatable.getOrPut(path) { navigatable }
            }

            val routes = routeToNavigatable.toMap()
            val fullPaths = navigatableToFullPath.toMap()
            val routeResolver = RouteResolver.create(
                graphDefinitions = graphs,
                routeToNavigatable = routes,
                navigatableToFullPath = fullPaths,
                graphIndex = graphIndex,
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
                graphDefinitions = graphs,
                graphHierarchies = graphIndex.chains,
                navigatableToGraph = navigatableToGraph.toMap(),
                routeToNavigatable = routes,
                navigatableToFullPath = fullPaths,
                notFoundScreen = notFoundScreen,
                crashScreen = crashScreen,
                interceptsByGraphId = interceptsByGraphId,
                interceptsByPath = interceptsByPath,
                graphEntries = graphEntries,
                deepLinkAliases = deepLinkAliases,
                loadingModal = loadingModal
            )
        }
    }
}

internal fun fullPathMessage(resolver: RouteResolver, route: String, describedAs: String): String {
    val clean = normalizePath(route)
    val suggestions = resolver.fullPathSuggestions(route)
    val hint = when (suggestions.size) {
        0 -> "No registered path ends with '/$clean'."
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
    precomputedData: PrecomputedNavigationData
): NavigationProjection {
    val orderedBackStack = backStack.mapIndexed { index, entry ->
        entry.copy(stackPosition = index)
    }

    val visibleLayers = computeVisibleLayers(orderedBackStack)

    val currentFullPath = precomputedData.routeResolver.buildFullPathForEntry(currentEntry)

    val currentPathSegments = currentFullPath.split("/").filter { it.isNotEmpty() }

    val currentGraphId = precomputedData.navigatableToGraph[currentEntry.navigatable]
    val currentGraphHierarchy = currentGraphId?.let { precomputedData.graphIndex.chain(it) }
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
        findOriginalUnderlyingScreenForModal(currentEntry, orderedBackStack)
    } else null
    val modalsInStack = backStack.filter { it.navigatable is Modal }

    val underlyingScreenGraphHierarchy = underlyingScreen?.let { screen ->
        val graphId = precomputedData.navigatableToGraph[screen.navigatable]
        graphId?.let { precomputedData.graphIndex.chain(it) } ?: listOf(screen.route)
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
): List<NavigationEntry> {
    if (orderedBackStack.isEmpty()) return emptyList()

    val systemTail = orderedBackStack.takeLastWhile { it.navigatable.renderLayer == RenderLayer.SYSTEM }
    val content = orderedBackStack.dropLast(systemTail.size)
    val currentEntry = content.lastOrNull() ?: return listOf(orderedBackStack.last())

    val layers = mutableListOf<NavigationEntry>()
    if (currentEntry.navigatable is Modal) {
        findOriginalUnderlyingScreenForModal(currentEntry, content)?.let { layers.add(it) }
    }
    layers.add(currentEntry)
    return layers + systemTail
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
    backStack: List<NavigationEntry>
): NavigationEntry? {
    val modalIndex = backStack.indexOfLast { it.stableKey == modalEntry.stableKey }
    if (modalIndex <= 0) return null
    return NavigationStackMath.underlyingScreen(backStack, modalIndex)
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
