package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.dsl.GraphBasedBuilder
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
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
    private val originalGuidedFlowDefinitions: Map<String, GuidedFlowDefinition> = emptyMap(),
    private val screenRetentionDuration: Duration
) : ModuleWithLogic<NavigationState, NavigationAction, NavigationLogic>, CustomTypeRegistrar {
    private val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph, notFoundScreen)
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
     * The full path includes all graph prefixes, e.g., "home/workspace/projects/tools"
     * for a screen with route "tools" nested in graphs home -> workspace -> projects.
     *
     * @param navigatable The screen or modal to get the path for
     * @return The full path, or null if the navigatable is not registered
     */
    fun getFullPath(navigatable: Navigatable): String? {
        return precomputedData.navigatableToFullPath[navigatable]
    }

    /**
     * Get all registered full paths mapped to their navigatables.
     * Useful for debugging or building navigation UIs.
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
                val fallbackScreen = notFoundScreen
                    ?: throw IllegalStateException(
                        "Root graph has no startScreen/startGraph defined and no notFoundScreen is configured. " +
                        "Either define a start destination for the root graph or configure a notFoundScreen."
                    )
                RouteResolution(
                    targetNavigatable = fallbackScreen,
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
            previousEntry = null,
            animationInProgress = false,
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
            transitionState = NavigationTransitionState.IDLE,
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
            guidedFlowModifications = emptyMap(),
            activeGuidedFlowState = null
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
        activeGuidedFlowState: GuidedFlowState? = state.activeGuidedFlowState,
        updateGuidedFlowState: Boolean = false,
        transitionState: NavigationTransitionState = state.transitionState,
        guidedFlowModifications: Map<String, GuidedFlowDefinition>? = null,
        navigationAction: NavigationAction? = null,
        previousEntry: NavigationEntry? = null,
        animationInProgress: Boolean = false
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
            transitionState = transitionState,
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
            guidedFlowModifications = guidedFlowModifications ?: state.guidedFlowModifications,
            activeGuidedFlowState = if (updateGuidedFlowState) activeGuidedFlowState else state.activeGuidedFlowState,
            previousEntry = previousEntry,
            animationInProgress = animationInProgress
        )
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.BatchUpdate -> reduceNavigationStateUpdate(
                state = state,
                currentEntry = action.currentEntry,
                backStack = action.backStack,
                modalContexts = action.modalContexts,
                activeGuidedFlowState = action.activeGuidedFlowState,
                updateGuidedFlowState = true,
                transitionState = action.transitionState,
                guidedFlowModifications = action.guidedFlowModifications,
                navigationAction = action,
                previousEntry = state.currentEntry,
                animationInProgress = true
            )

            is NavigationAction.Back -> reduceNavigationStateUpdate(
                state = state,
                currentEntry = action.currentEntry,
                backStack = action.backStack,
                modalContexts = action.modalContexts,
                transitionState = action.transitionState,
                navigationAction = action,
                previousEntry = state.currentEntry,
                animationInProgress = true
            )

            is NavigationAction.ClearBackstack -> this.reduceNavigationStateUpdate(
                state = state,
                currentEntry = action.currentEntry,
                backStack = action.backStack,
                modalContexts = action.modalContexts,
                transitionState = action.transitionState,
                navigationAction = action,
                previousEntry = state.currentEntry,
                animationInProgress = true
            )

            is NavigationAction.Navigate -> reduceNavigationStateUpdate(
                state = state,
                currentEntry = action.currentEntry,
                backStack = action.backStack,
                modalContexts = action.modalContexts,
                transitionState = action.transitionState,
                navigationAction = action,
                previousEntry = state.currentEntry,
                animationInProgress = true
            )

            is NavigationAction.PopUpTo -> reduceNavigationStateUpdate(
                state = state,
                currentEntry = action.currentEntry,
                backStack = action.backStack,
                modalContexts = action.modalContexts,
                transitionState = action.transitionState,
                navigationAction = action,
                previousEntry = state.currentEntry,
                animationInProgress = true
            )

            is NavigationAction.Replace -> reduceNavigationStateUpdate(
                state = state,
                currentEntry = action.currentEntry,
                backStack = action.backStack,
                modalContexts = action.modalContexts,
                transitionState = action.transitionState,
                navigationAction = action,
                previousEntry = state.currentEntry,
                animationInProgress = true
            )

            is NavigationAction.UpdateTransitionState -> {
                state.copy(transitionState = action.transitionState)
            }

            is NavigationAction.AnimationCompleted -> {
                val isBackNavigation = state.previousEntry?.let { prev ->
                    state.currentEntry.stackPosition < prev.stackPosition
                } ?: false

                state.copy(
                    previousEntry = if (isBackNavigation) null else state.previousEntry,
                    animationInProgress = false
                )
            }
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> NavigationLogic = { storeAccessor ->
        NavigationLogic(
            storeAccessor = storeAccessor,
            precomputedData = precomputedData,
            guidedFlowDefinitions = originalGuidedFlowDefinitions
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
    val notFoundScreen: Screen? = null
) {
    companion object {
        fun create(
            rootGraph: NavigationGraph,
            notFoundScreen: Screen? = null
        ): PrecomputedNavigationData {
            val graphDefinitions = mutableMapOf<String, NavigationGraph>()
            val availableNavigatables = mutableMapOf<String, Navigatable>()
            val allNavigatables = mutableMapOf<String, Navigatable>()
            val navigatableToGraph = mutableMapOf<Navigatable, String>()
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>()

            // Build parent graph lookup first for hierarchy calculations
            val parentGraphLookup = mutableMapOf<String, String>()

            // Collect all graphs and navigatables in one pass
            fun collectGraphs(graph: NavigationGraph) {
                graphDefinitions[graph.route] = graph

                graph.nestedGraphs.forEach { nestedGraph ->
                    parentGraphLookup[nestedGraph.route] = graph.route
                }

                graph.navigatables.forEach { navigatable ->
                    navigatableToGraph[navigatable] = graph.route

                    // Compute full path for all navigatables
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

                    // Register by full path to avoid route collisions
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

                    // Keep availableNavigatables for root-level screens (backward compatibility)
                    if (graph.route == "root") {
                        availableNavigatables[navigatable.route] = navigatable
                    }
                }

                graph.nestedGraphs.forEach { collectGraphs(it) }
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
                notFoundScreen = notFoundScreen
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
    // First try to find the underlying screen from modal contexts
    val modalContext = modalContexts[modalEntry.navigatable.route]
    if (modalContext != null) {
        return modalContext.originalUnderlyingScreenEntry
    }

    val modalIndex = backStack.indexOf(modalEntry)
    if (modalIndex <= 0) return null

    return backStack.subList(0, modalIndex).lastOrNull { it.isScreen }
}

fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}