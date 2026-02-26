package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
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
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.RouteResolver
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.time.Duration


class NavigationModule internal constructor(
    private val rootGraph: NavigationGraph,
    private val notFoundScreen: Screen? = null,
    internal val crashScreen: Screen? = null,
    internal val onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null,
    private val deepLinkAliases: List<DeepLinkAlias> = emptyList(),
    private val screenRetentionDuration: Duration,
    private val loadingModal: LoadingModal? = null
) : ModuleWithLogic<NavigationState, NavigationAction, NavigationLogic>, CustomTypeRegistrar {
    private val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph, notFoundScreen, crashScreen, deepLinkAliases, loadingModal)
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
    fun getFullPath(navigatable: Navigatable): String? {
        return precomputedData.navigatableToFullPath[navigatable]
    }

    /**
     * Get all registered full paths mapped to their navigatables.
     */
    fun getAllFullPaths(): Map<Navigatable, String> {
        return precomputedData.navigatableToFullPath
    }

    override val initialState: NavigationState by lazy {
        createInitialState()
    }

    private fun createInitialState(): NavigationState {
        val resolution = when (val dest = rootGraph.startDestination) {
            is StartDestination.DirectScreen -> {
                RouteResolution(
                    targetNavigatable = dest.screen,
                    targetGraphId = rootGraph.route,
                    extractedParams = Params.empty(),
                    navigationGraphId = rootGraph.route
                )
            }

            is StartDestination.GraphReference -> {
                precomputedData.routeResolver.resolve(dest.graphId)
                    ?: throw IllegalStateException("Could not resolve root graph reference to '${dest.graphId}'")
            }

            null -> {
                val fallbackNavigatable: Navigatable = loadingModal
                    ?: notFoundScreen
                    ?: throw IllegalStateException(
                        "Root graph has no startScreen/startGraph defined. " +
                        "Either define a static start destination via entry(screen), " +
                        "provide a loadingModal() at the module level, " +
                        "or configure a notFoundScreen."
                    )
                RouteResolution(
                    targetNavigatable = fallbackNavigatable,
                    targetGraphId = rootGraph.route,
                    extractedParams = Params.empty(),
                    navigationGraphId = rootGraph.route
                )
            }
        }

        val effectiveGraphId = resolution.getEffectiveGraphId()

        val initialEntry = NavigationEntry(
            navigatable = resolution.targetNavigatable,
            params = Params.empty(),
            graphId = effectiveGraphId
        )

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
            orderedBackStack = computedState.orderedBackStack,
            visibleLayers = computedState.visibleLayers,
            currentFullPath = computedState.currentFullPath,
            currentPathSegments = computedState.currentPathSegments,
            currentGraphHierarchy = computedState.currentGraphHierarchy,
            breadcrumbs = computedState.breadcrumbs,
            canGoBack = computedState.canGoBack,
            isCurrentModal = computedState.isCurrentModal,
            isCurrentScreen = computedState.isCurrentScreen,
            hasModalsInStack = computedState.hasModalsInStack,
            effectiveDepth = computedState.effectiveDepth,
            navigationDepth = computedState.navigationDepth,
            contentLayerEntries = computedState.contentLayerEntries,
            globalOverlayEntries = computedState.globalOverlayEntries,
            systemLayerEntries = computedState.systemLayerEntries,
            renderableEntries = computedState.renderableEntries,
            underlyingScreen = computedState.underlyingScreen,
            modalsInStack = computedState.modalsInStack,
            underlyingScreenGraphHierarchy = computedState.underlyingScreenGraphHierarchy,
            activeModalContexts = emptyMap(),
            pendingNavigation = null
        )
    }

    @OptIn(InternalSerializationApi::class)
    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(Navigatable::class) {
            fun registerGraphNavigatables(graph: NavigationGraph) {
                graph.navigatables.forEach { navigatable ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        subclass(
                            navigatable::class as KClass<Navigatable>,
                            navigatable::class.serializer() as KSerializer<Navigatable>
                        )
                    } catch (e: Exception) {
                    }
                }
                graph.nestedGraphs.forEach { registerGraphNavigatables(it) }
            }
            registerGraphNavigatables(rootGraph)

            notFoundScreen?.let { screen ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        screen::class as KClass<Navigatable>,
                        screen::class.serializer() as KSerializer<Navigatable>
                    )
                } catch (e: Exception) {
                }
            }

            crashScreen?.let { screen ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        screen::class as KClass<Navigatable>,
                        screen::class.serializer() as KSerializer<Navigatable>
                    )
                } catch (e: Exception) {
                }
            }

            loadingModal?.let { modal ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        modal::class as KClass<Navigatable>,
                        modal::class.serializer() as KSerializer<Navigatable>
                    )
                } catch (e: Exception) {
                }
            }
        }

        builder.polymorphic(Screen::class) {
            fun registerScreens(graph: NavigationGraph) {
                graph.navigatables.filterIsInstance<Screen>()
                    .forEach { screen ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            subclass(
                                screen::class as KClass<Screen>,
                                screen::class.serializer() as KSerializer<Screen>
                            )
                        } catch (e: Exception) {
                        }
                    }
                graph.nestedGraphs.forEach { registerScreens(it) }
            }
            registerScreens(rootGraph)

            notFoundScreen?.let { screen ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        screen::class as KClass<Screen>,
                        screen::class.serializer() as KSerializer<Screen>
                    )
                } catch (e: Exception) {
                }
            }

            crashScreen?.let { screen ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        screen::class as KClass<Screen>,
                        screen::class.serializer() as KSerializer<Screen>
                    )
                } catch (e: Exception) {
                }
            }
        }

        builder.polymorphic(Modal::class) {
            fun registerModals(graph: NavigationGraph) {
                graph.navigatables.filterIsInstance<Modal>().forEach { modal ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        subclass(
                            modal::class as KClass<Modal>,
                            modal::class.serializer() as KSerializer<Modal>
                        )
                    } catch (e: Exception) {
                    }
                }
                graph.nestedGraphs.forEach { registerModals(it) }
            }
            registerModals(rootGraph)

            loadingModal?.let { modal ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        modal::class as KClass<Modal>,
                        modal::class.serializer() as KSerializer<Modal>
                    )
                } catch (e: Exception) {
                }
            }
        }

        builder.polymorphic(NavigationGraph::class) {
            fun registerGraphTypes(graph: NavigationGraph) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        graph::class as KClass<NavigationGraph>,
                        graph::class.serializer() as KSerializer<NavigationGraph>
                    )
                } catch (e: Exception) {
                }
                graph.nestedGraphs.forEach { registerGraphTypes(it) }
            }
            registerGraphTypes(rootGraph)
        }
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
            orderedBackStack = computedState.orderedBackStack,
            visibleLayers = computedState.visibleLayers,
            currentFullPath = computedState.currentFullPath,
            currentPathSegments = computedState.currentPathSegments,
            currentGraphHierarchy = computedState.currentGraphHierarchy,
            breadcrumbs = computedState.breadcrumbs,
            canGoBack = computedState.canGoBack,
            isCurrentModal = computedState.isCurrentModal,
            isCurrentScreen = computedState.isCurrentScreen,
            hasModalsInStack = computedState.hasModalsInStack,
            effectiveDepth = computedState.effectiveDepth,
            navigationDepth = computedState.navigationDepth,
            contentLayerEntries = computedState.contentLayerEntries,
            globalOverlayEntries = computedState.globalOverlayEntries,
            systemLayerEntries = computedState.systemLayerEntries,
            renderableEntries = computedState.renderableEntries,
            underlyingScreen = computedState.underlyingScreen,
            modalsInStack = computedState.modalsInStack,
            underlyingScreenGraphHierarchy = computedState.underlyingScreenGraphHierarchy,
            activeModalContexts = newModalContexts,
            pendingNavigation = newPendingNavigation
        )
    }

    private fun reduceAction(state: NavigationState, action: NavigationAction): NavigationState = when (action) {
        is NavigationAction.AtomicBatch -> action.actions.fold(state, ::reduceAction)

        is NavigationAction.Navigate -> {
            val entry = action.entry
            val isModal = entry.isModal
            val isSystemLayer = entry.navigatable.renderLayer == RenderLayer.SYSTEM
            val baseBackStack = if (action.dismissModals) state.backStack.filter { it.isScreen }
                                else state.backStack
            val stackPosition = when {
                isModal -> state.backStack.size + 1
                baseBackStack.isEmpty() -> 1
                else -> baseBackStack.size + 1
            }
            val positionedEntry = entry.copy(stackPosition = stackPosition)

            val newBackStack = if (isSystemLayer) {
                // SYSTEM entries always go to the end so they remain currentEntry
                state.backStack + positionedEntry
            } else {
                // Non-system entries are inserted before any existing SYSTEM entries
                // so the SYSTEM modal stays on top
                val systemTail = baseBackStack.filter { it.navigatable.renderLayer == RenderLayer.SYSTEM }
                val nonSystemBase = baseBackStack.filter { it.navigatable.renderLayer != RenderLayer.SYSTEM }
                when {
                    isModal -> nonSystemBase + positionedEntry + systemTail
                    nonSystemBase.isEmpty() -> listOf(positionedEntry) + systemTail
                    else -> nonSystemBase + positionedEntry + systemTail
                }
            }

            // When a non-system entry is added but SYSTEM entries remain at the tail,
            // keep the SYSTEM entry as currentEntry (invariant: backStack.last() == currentEntry)
            val effectiveCurrentEntry = if (!isSystemLayer &&
                newBackStack.lastOrNull()?.navigatable?.renderLayer == RenderLayer.SYSTEM) {
                newBackStack.last()
            } else positionedEntry

            val modalContexts = when {
                action.dismissModals -> emptyMap()
                isModal && action.modalContext != null ->
                    state.activeModalContexts + (positionedEntry.navigatable.route to action.modalContext)
                !isModal && !action.dismissModals && state.currentEntry.isModal && state.activeModalContexts.isNotEmpty() -> {
                    val modalRoute = state.currentEntry.navigatable.route
                    val ctx = state.activeModalContexts[modalRoute]
                    if (ctx != null) {
                        val underlying = ctx.originalUnderlyingScreenEntry.navigatable.route
                        mapOf(underlying to ctx.copy(navigatedAwayToRoute = positionedEntry.navigatable.route))
                    } else state.activeModalContexts
                }
                else -> state.activeModalContexts
            }
            reduceNavigationStateUpdate(state, effectiveCurrentEntry, newBackStack, modalContexts, action)
        }

        is NavigationAction.Replace -> {
            val positioned = action.entry.copy(
                stackPosition = if (state.backStack.isEmpty()) 1 else state.backStack.size
            )
            val newBackStack = if (state.backStack.isEmpty()) listOf(positioned)
                               else state.backStack.dropLast(1) + positioned
            reduceNavigationStateUpdate(state, positioned, newBackStack, state.activeModalContexts, action)
        }

        is NavigationAction.Back -> {
            if (state.backStack.size <= 1) state
            else {
                val trimmed = state.backStack.dropLast(1)
                val target = trimmed.last().copy(stackPosition = trimmed.size)
                val finalStack = trimmed.dropLast(1) + target
                val modalCtx = state.activeModalContexts[target.navigatable.route]
                if (modalCtx != null) {
                    val modal = modalCtx.modalEntry
                    reduceNavigationStateUpdate(
                        state, modal, finalStack + modal,
                        mapOf(modal.navigatable.route to modalCtx.copy(navigatedAwayToRoute = null)), action
                    )
                } else {
                    val cleaned = state.activeModalContexts.filterKeys {
                        it != state.currentEntry.navigatable.route
                    }
                    reduceNavigationStateUpdate(state, target, finalStack, cleaned, action)
                }
            }
        }

        is NavigationAction.ClearBackstack -> {
            // Preserve SYSTEM layer entries — they float above the navigation hierarchy
            // and should not be cleared by normal backstack resets (e.g. during bootstrap)
            val systemEntries = state.backStack.filter {
                it.navigatable.renderLayer == RenderLayer.SYSTEM
            }
            val effectiveCurrent = if (systemEntries.isNotEmpty()) systemEntries.last()
                                   else state.currentEntry
            reduceNavigationStateUpdate(state, effectiveCurrent, systemEntries, emptyMap(), action)
        }

        is NavigationAction.PopUpTo ->
            reduceNavigationStateUpdate(
                state, action.newCurrentEntry, action.newBackStack, action.newModalContexts, action
            )

        is NavigationAction.SetPendingNavigation -> state.copy(
            pendingNavigation = action.pending
        )

        is NavigationAction.ClearPendingNavigation -> state.copy(
            pendingNavigation = null
        )

        is NavigationAction.BootstrapComplete -> state.copy(
            isBootstrapping = false
        )

        is NavigationAction.RemoveLoadingModals -> {
            val newBackStack = state.backStack.filter { it.navigatable !is LoadingModal }
            if (newBackStack == state.backStack) {
                state
            } else {
                val newCurrentEntry = if (state.currentEntry.navigatable is LoadingModal) {
                    newBackStack.lastOrNull() ?: state.currentEntry
                } else state.currentEntry
                reduceNavigationStateUpdate(state, newCurrentEntry, newBackStack, state.activeModalContexts, action)
            }
        }
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = ::reduceAction

    override val createLogic: (storeAccessor: StoreAccessor) -> NavigationLogic = { storeAccessor ->
        NavigationLogic(
            storeAccessor = storeAccessor,
            precomputedData = precomputedData,
            onCrash = onCrash
        )
    }

    companion object {
        inline fun create(block: GraphBasedBuilder.() -> Unit): NavigationModule {
            return GraphBasedBuilder().apply(block).build()
        }
    }
}

data class PrecomputedNavigationData(
    val routeResolver: RouteResolver,
    val availableNavigatables: Map<String, Navigatable>,
    val graphDefinitions: Map<String, NavigationGraph>,
    val allNavigatables: Map<String, Navigatable>,
    val graphHierarchies: Map<String, List<String>>,
    val navigatableToGraph: Map<Navigatable, String>,
    val routeToNavigatable: Map<String, Navigatable>,
    val navigatableToFullPath: Map<Navigatable, String>,
    val notFoundScreen: Screen? = null,
    val crashScreen: Screen? = null,
    val interceptedRoutes: Map<String, InterceptDefinition> = emptyMap(),
    val graphEntries: Map<String, EntryDefinition> = emptyMap(),
    val deepLinkAliases: List<DeepLinkAlias> = emptyList(),
    val loadingModal: LoadingModal? = null
) {
    companion object {
        fun create(
            rootGraph: NavigationGraph,
            notFoundScreen: Screen? = null,
            crashScreen: Screen? = null,
            deepLinkAliases: List<DeepLinkAlias> = emptyList(),
            loadingModal: LoadingModal? = null
        ): PrecomputedNavigationData {
            val graphDefinitions = mutableMapOf<String, NavigationGraph>()
            val availableNavigatables = mutableMapOf<String, Navigatable>()
            val allNavigatables = mutableMapOf<String, Navigatable>()
            val navigatableToGraph = mutableMapOf<Navigatable, String>()
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>()
            val interceptedRoutes = mutableMapOf<String, InterceptDefinition>()
            val graphEntries = mutableMapOf<String, EntryDefinition>()

            val parentGraphLookup = mutableMapOf<String, String>()

            fun collectGraphs(graph: NavigationGraph, inheritedIntercept: InterceptDefinition? = null) {
                val effectiveIntercept = graph.interceptDefinition ?: inheritedIntercept

                graphDefinitions[graph.route] = graph

                graph.entryDefinition?.let { def ->
                    graphEntries[graph.route] = def
                }

                if (effectiveIntercept != null) {
                    interceptedRoutes[graph.route] = effectiveIntercept
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
                    allNavigatables[fullPath] = navigatable

                    if (graph.route == "root") {
                        availableNavigatables[navigatable.route] = navigatable
                    }

                    if (effectiveIntercept != null) {
                        interceptedRoutes[fullPath] = effectiveIntercept
                    }
                }

                graph.nestedGraphs.forEach { collectGraphs(it, effectiveIntercept) }
            }

            collectGraphs(rootGraph)

            val routeResolver = RouteResolver.create(graphDefinitions, notFoundScreen)
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


            return PrecomputedNavigationData(
                routeResolver = routeResolver,
                availableNavigatables = availableNavigatables,
                graphDefinitions = graphDefinitions,
                allNavigatables = allNavigatables,
                graphHierarchies = graphHierarchies,
                navigatableToGraph = navigatableToGraph,
                routeToNavigatable = routeToNavigatable,
                navigatableToFullPath = navigatableToFullPath,
                notFoundScreen = notFoundScreen,
                crashScreen = crashScreen,
                interceptedRoutes = interceptedRoutes,
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

// Internal data class for computed navigation state
private data class ComputedNavigationState(
    val orderedBackStack: List<NavigationEntry>,
    val visibleLayers: List<NavigationLayer>,
    val currentFullPath: String,
    val currentPathSegments: List<String>,
    val currentGraphHierarchy: List<String>,
    val breadcrumbs: List<NavigationBreadcrumb>,
    val canGoBack: Boolean,
    val isCurrentModal: Boolean,
    val isCurrentScreen: Boolean,
    val hasModalsInStack: Boolean,
    val effectiveDepth: Int,
    val navigationDepth: Int,
    val contentLayerEntries: List<NavigationEntry>,
    val globalOverlayEntries: List<NavigationEntry>,
    val systemLayerEntries: List<NavigationEntry>,
    val renderableEntries: List<NavigationEntry>,
    val underlyingScreen: NavigationEntry?,
    val modalsInStack: List<NavigationEntry>,
    val underlyingScreenGraphHierarchy: List<String>?
)

// Internal computation function
private fun computeNavigationDerivedState(
    currentEntry: NavigationEntry,
    backStack: List<NavigationEntry>,
    precomputedData: PrecomputedNavigationData,
    existingModalContexts: Map<String, ModalContext> = emptyMap()
): ComputedNavigationState {
    val orderedBackStack = backStack.mapIndexed { index, entry ->
        entry.copy(stackPosition = index)
    }

    val visibleLayers = computeVisibleLayers(orderedBackStack, existingModalContexts)

    val currentFullPath = precomputedData.routeResolver.buildFullPathForEntry(currentEntry)
        ?: currentEntry.navigatable.route

    val currentPathSegments = currentFullPath.split("/").filter { it.isNotEmpty() }

    val currentGraphHierarchy = precomputedData.graphHierarchies[currentEntry.graphId]
        ?: listOf(currentEntry.graphId)

    val breadcrumbs = buildBreadcrumbs(currentPathSegments, precomputedData.graphDefinitions)

    val canGoBack = backStack.size > 1
    val isCurrentModal = currentEntry.isModal
    val isCurrentScreen = currentEntry.isScreen
    val hasModalsInStack = backStack.any { it.isModal }
    val effectiveDepth = backStack.size
    val navigationDepth = currentPathSegments.size

    val entriesByLayer = visibleLayers.map { it.entry }.groupBy { it.navigatable.renderLayer }
    val contentLayerEntries = entriesByLayer[RenderLayer.CONTENT] ?: emptyList()
    val globalOverlayEntries = entriesByLayer[RenderLayer.GLOBAL_OVERLAY] ?: emptyList()
    val systemLayerEntries = entriesByLayer[RenderLayer.SYSTEM] ?: emptyList()
    val renderableEntries = visibleLayers.map { it.entry }

    val underlyingScreen = if (isCurrentModal) {
        findOriginalUnderlyingScreenForModal(currentEntry, orderedBackStack, existingModalContexts)
    } else null
    val modalsInStack = backStack.filter { it.isModal }

    val underlyingScreenGraphHierarchy = underlyingScreen?.let { screen ->
        precomputedData.graphHierarchies[screen.graphId] ?: listOf(screen.graphId)
    }

    return ComputedNavigationState(
        orderedBackStack = orderedBackStack,
        visibleLayers = visibleLayers,
        currentFullPath = currentFullPath,
        currentPathSegments = currentPathSegments,
        currentGraphHierarchy = currentGraphHierarchy,
        breadcrumbs = breadcrumbs,
        canGoBack = canGoBack,
        isCurrentModal = isCurrentModal,
        isCurrentScreen = isCurrentScreen,
        hasModalsInStack = hasModalsInStack,
        effectiveDepth = effectiveDepth,
        navigationDepth = navigationDepth,
        contentLayerEntries = contentLayerEntries,
        globalOverlayEntries = globalOverlayEntries,
        systemLayerEntries = systemLayerEntries,
        renderableEntries = renderableEntries,
        underlyingScreen = underlyingScreen,
        modalsInStack = modalsInStack,
        underlyingScreenGraphHierarchy = underlyingScreenGraphHierarchy
    )
}

private fun computeVisibleLayers(
    orderedBackStack: List<NavigationEntry>,
    modalContexts: Map<String, ModalContext> = emptyMap()
): List<NavigationLayer> {
    if (orderedBackStack.isEmpty()) return emptyList()

    val layers = mutableListOf<NavigationLayer>()
    val currentEntry = orderedBackStack.last()

    if (currentEntry.isModal) {
        val modal = currentEntry.navigatable as Modal
        val underlyingScreen = findOriginalUnderlyingScreenForModal(currentEntry, orderedBackStack, modalContexts)

        if (underlyingScreen != null) {
            layers.add(
                NavigationLayer(
                    entry = underlyingScreen,
                    zIndex = underlyingScreen.zIndex,
                    isVisible = true,
                    shouldDim = modal.shouldDimBackground,
                    dimAlpha = if (modal.shouldDimBackground) modal.backgroundDimAlpha else 0f
                )
            )
        }
        layers.add(
            NavigationLayer(
                entry = currentEntry,
                zIndex = currentEntry.zIndex,
                isVisible = true,
                shouldDim = false
            )
        )

        return layers.sortedBy { it.zIndex }
    }
    layers.add(
        NavigationLayer(
            entry = currentEntry,
            zIndex = currentEntry.zIndex,
            isVisible = true,
            shouldDim = false
        )
    )

    return layers
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

private fun findOriginalUnderlyingScreenForModal(
    modalEntry: NavigationEntry,
    backStack: List<NavigationEntry>,
    modalContexts: Map<String, ModalContext> = emptyMap()
): NavigationEntry? {
    val modalContext = modalContexts[modalEntry.navigatable.route]
    if (modalContext != null) {
        return modalContext.originalUnderlyingScreenEntry
    }

    // The context may have been re-keyed to the underlying screen's route when navigating
    // away from a modal. Search by matching the stored modalEntry's route.
    val modalContextByEntry = modalContexts.values.find {
        it.modalEntry.navigatable.route == modalEntry.navigatable.route
    }
    if (modalContextByEntry != null) {
        return modalContextByEntry.originalUnderlyingScreenEntry
    }

    // Fall back to scanning the backStack by route/graphId (ignores stackPosition differences).
    val modalIndex = backStack.indexOfFirst {
        it.navigatable.route == modalEntry.navigatable.route && it.graphId == modalEntry.graphId
    }
    if (modalIndex <= 0) return null

    return backStack.subList(0, modalIndex).lastOrNull { it.isScreen }
}

fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}
