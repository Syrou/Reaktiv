package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.dsl.GraphBasedBuilder
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.ClearModificationBehavior
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.model.applyModification
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
    private val originalGuidedFlowDefinitions: Map<String, GuidedFlowDefinition> = emptyMap(),
    private val screenRetentionDuration: Duration
) : Module<NavigationState, NavigationAction>, CustomTypeRegistrar {
    private val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph)
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
            transitionState = NavigationTransitionState.IDLE,
            effectiveDepth = computedState.effectiveDepth,
            navigationDepth = computedState.navigationDepth,
            contentLayerEntries = computedState.contentLayerEntries,
            globalOverlayEntries = computedState.globalOverlayEntries,
            systemLayerEntries = computedState.systemLayerEntries,
            renderableEntries = computedState.renderableEntries,
            underlyingScreen = computedState.underlyingScreen,
            modalsInStack = computedState.modalsInStack,
            graphDefinitions = precomputedData.graphDefinitions,
            availableRoutes = precomputedData.routeToNavigatable.keys,
            allAvailableNavigatables = precomputedData.allNavigatables,
            graphHierarchyLookup = precomputedData.graphHierarchies,
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
        navigationAction: NavigationAction? = null
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
            graphDefinitions = precomputedData.graphDefinitions,
            availableRoutes = precomputedData.routeToNavigatable.keys,
            allAvailableNavigatables = precomputedData.allNavigatables,
            graphHierarchyLookup = precomputedData.graphHierarchies,
            activeModalContexts = newModalContexts,
            guidedFlowModifications = guidedFlowModifications ?: state.guidedFlowModifications,
            activeGuidedFlowState = if (updateGuidedFlowState) activeGuidedFlowState else state.activeGuidedFlowState
        )
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.BatchUpdate -> reduceNavigationStateUpdate(
                state, action.currentEntry, action.backStack, action.modalContexts,
                action.activeGuidedFlowState, updateGuidedFlowState = true, transitionState = action.transitionState,
                guidedFlowModifications = action.guidedFlowModifications,
                navigationAction = action
            )
            is NavigationAction.Back -> reduceNavigationStateUpdate(
                state, action.currentEntry, action.backStack, action.modalContexts, transitionState = action.transitionState,
                navigationAction = action
            )
            is NavigationAction.ClearBackstack -> reduceNavigationStateUpdate(
                state, action.currentEntry, action.backStack, action.modalContexts, transitionState = action.transitionState,
                navigationAction = action
            )
            is NavigationAction.Navigate -> reduceNavigationStateUpdate(
                state, action.currentEntry, action.backStack, action.modalContexts, transitionState = action.transitionState,
                navigationAction = action
            )
            is NavigationAction.PopUpTo -> reduceNavigationStateUpdate(
                state, action.currentEntry, action.backStack, action.modalContexts, transitionState = action.transitionState,
                navigationAction = action
            )
            is NavigationAction.Replace -> reduceNavigationStateUpdate(
                state, action.currentEntry, action.backStack, action.modalContexts, transitionState = action.transitionState,
                navigationAction = action
            )

            is NavigationAction.UpdateTransitionState -> {
                state.copy(transitionState = action.transitionState)
            }

        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
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
) {
    companion object {
        fun create(rootGraph: NavigationGraph): PrecomputedNavigationData {
            val graphDefinitions = mutableMapOf<String, NavigationGraph>()
            val availableNavigatables = mutableMapOf<String, Navigatable>()
            val allNavigatables = mutableMapOf<String, Navigatable>()
            val navigatableToGraph = mutableMapOf<Navigatable, String>()
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>() // CRITICAL: Build the missing lookup table

            // Build parent graph lookup first for hierarchy calculations
            val parentGraphLookup = mutableMapOf<String, String>()

            // Collect all graphs and navigatables in one pass
            fun collectGraphs(graph: NavigationGraph) {
                graphDefinitions[graph.route] = graph

                graph.nestedGraphs.forEach { nestedGraph ->
                    parentGraphLookup[nestedGraph.route] = graph.route
                }

                if (graph.route == "root") {
                    graph.navigatables.forEach { navigatable ->
                        availableNavigatables[navigatable.route] = navigatable
                        routeToNavigatable[navigatable.route] = navigatable
                        navigatableToFullPath[navigatable] = navigatable.route
                    }
                }

                graph.navigatables.forEach { navigatable ->
                    allNavigatables[navigatable.route] = navigatable
                    navigatableToGraph[navigatable] = graph.route
                    routeToNavigatable[navigatable.route] = navigatable

                    if (graph.route != "root") {
                        val graphPath = buildGraphPathToRoot(graph.route, parentGraphLookup)
                        val fullPath = if (graphPath.isEmpty()) {
                            navigatable.route
                        } else {
                            "$graphPath/${navigatable.route}"
                        }
                        navigatableToFullPath[navigatable] = fullPath
                    }
                }

                graph.nestedGraphs.forEach { collectGraphs(it) }
            }

            collectGraphs(rootGraph)

            val routeResolver = RouteResolver.create(graphDefinitions)
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
                navigatableToFullPath = navigatableToFullPath
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
    val modalsInStack: List<NavigationEntry>
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
        modalsInStack = modalsInStack
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