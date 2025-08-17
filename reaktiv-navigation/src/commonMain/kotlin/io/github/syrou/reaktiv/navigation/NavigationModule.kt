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
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.util.RouteResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.KClass


class NavigationModule internal constructor(
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val rootGraph: NavigationGraph
) : Module<NavigationState, NavigationAction>, CustomTypeRegistrar {
    private val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph)
    }

    override val initialState: NavigationState by lazy {
        createInitialState()
    }

    private fun createInitialState(): NavigationState {
        ReaktivDebug.nav("🚀 Creating  initial navigation state")

        // Resolve the start destination using precomputed resolver
        val resolution = when (val dest = rootGraph.startDestination) {
            is StartDestination.DirectScreen -> {
                RouteResolution(
                    targetNavigatable = dest.screen,
                    targetGraphId = rootGraph.route,
                    extractedParams = emptyMap(),
                    navigationGraphId = rootGraph.route
                )
            }
            is StartDestination.GraphReference -> {
                precomputedData.routeResolver.resolve(dest.graphId)
                    ?: throw IllegalStateException("Could not resolve root graph reference to '${dest.graphId}'")
            }
        }

        val effectiveGraphId = resolution.getEffectiveGraphId()

        // Create initial entry
        val initialEntry = NavigationEntry(
            navigatable = resolution.targetNavigatable,
            params = emptyMap(),
            graphId = effectiveGraphId
        )
        
        val initialBackStack = listOf(initialEntry)
        
        // Compute all derived state
        val computedState = computeNavigationDerivedState(
            currentEntry = initialEntry,
            backStack = initialBackStack,
            precomputedData = precomputedData,
            existingModalContexts = emptyMap()
        )

        return NavigationState(
            currentEntry = initialEntry,
            backStack = initialBackStack,
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
            graphDefinitions = precomputedData.graphDefinitions,
            availableRoutes = precomputedData.routeToNavigatable.keys,
            allAvailableNavigatables = precomputedData.allNavigatables,
            graphHierarchyLookup = precomputedData.graphHierarchies,
            activeModalContexts = emptyMap(),
            guidedFlowDefinitions = emptyMap(),
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
                        ReaktivDebug.warn("Could not register serializer for navigatable ${navigatable.route}: ${e.message}")
                    }
                }
                graph.nestedGraphs.forEach { registerGraphNavigatables(it) }
            }
            registerGraphNavigatables(rootGraph)
        }
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.BatchUpdate -> {
                val newCurrentEntry = action.currentEntry ?: state.currentEntry
                val newBackStack = action.backStack ?: state.backStack
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
                    precomputedData = precomputedData,
                    existingModalContexts = state.activeModalContexts
                )
                
                state.copy(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
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
                    graphDefinitions = precomputedData.graphDefinitions,
                    availableRoutes = precomputedData.routeToNavigatable.keys,
                    allAvailableNavigatables = precomputedData.allNavigatables,
                    graphHierarchyLookup = precomputedData.graphHierarchies,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.Back -> {
                val newCurrentEntry = action.currentEntry ?: state.currentEntry
                val newBackStack = action.backStack ?: state.backStack
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
                    precomputedData = precomputedData,
                    existingModalContexts = state.activeModalContexts
                )
                
                state.copy(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
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
                    graphDefinitions = precomputedData.graphDefinitions,
                    availableRoutes = precomputedData.routeToNavigatable.keys,
                    allAvailableNavigatables = precomputedData.allNavigatables,
                    graphHierarchyLookup = precomputedData.graphHierarchies,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.BatchUpdateWithModalContext -> {
                val newCurrentEntry = action.currentEntry ?: state.currentEntry
                val newBackStack = action.backStack ?: state.backStack
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
                    precomputedData = precomputedData,
                    existingModalContexts = action.modalContexts
                )
                
                state.copy(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
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
                    graphDefinitions = precomputedData.graphDefinitions,
                    availableRoutes = precomputedData.routeToNavigatable.keys,
                    allAvailableNavigatables = precomputedData.allNavigatables,
                    graphHierarchyLookup = precomputedData.graphHierarchies,
                    activeModalContexts = action.modalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.ClearBackstack -> {
                val newCurrentEntry = action.currentEntry ?: state.currentEntry
                val newBackStack = action.backStack ?: state.backStack
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
                    precomputedData = precomputedData,
                    existingModalContexts = state.activeModalContexts
                )
                
                state.copy(
                    currentEntry = newCurrentEntry,
                    backStack = newBackStack,
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
                    graphDefinitions = precomputedData.graphDefinitions,
                    availableRoutes = precomputedData.routeToNavigatable.keys,
                    allAvailableNavigatables = precomputedData.allNavigatables,
                    graphHierarchyLookup = precomputedData.graphHierarchies,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.ClearCurrentScreenParams -> {
                val updatedEntry = state.currentEntry.copy(params = emptyMap())
                val updatedBackStack = state.backStack.dropLast(1) + updatedEntry
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = updatedEntry,
                    backStack = updatedBackStack,
                    precomputedData = precomputedData
                )
                
                state.copy(
                    currentEntry = updatedEntry,
                    backStack = updatedBackStack,
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
                    graphDefinitions = state.graphDefinitions,
                    availableRoutes = state.availableRoutes,
                    allAvailableNavigatables = state.allAvailableNavigatables,
                    graphHierarchyLookup = state.graphHierarchyLookup,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.ClearCurrentScreenParam -> {
                val updatedEntry = state.currentEntry.copy(
                    params = state.currentEntry.params - action.key
                )
                val updatedBackStack = state.backStack.dropLast(1) + updatedEntry
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = updatedEntry,
                    backStack = updatedBackStack,
                    precomputedData = precomputedData
                )
                
                state.copy(
                    currentEntry = updatedEntry,
                    backStack = updatedBackStack,
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
                    graphDefinitions = state.graphDefinitions,
                    availableRoutes = state.availableRoutes,
                    allAvailableNavigatables = state.allAvailableNavigatables,
                    graphHierarchyLookup = state.graphHierarchyLookup,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.ClearScreenParams -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.navigatable.route == action.route) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.navigatable.route == action.route) {
                    state.currentEntry.copy(params = emptyMap())
                } else {
                    state.currentEntry
                }
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack,
                    precomputedData = precomputedData
                )
                
                state.copy(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack,
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
                    graphDefinitions = state.graphDefinitions,
                    availableRoutes = state.availableRoutes,
                    allAvailableNavigatables = state.allAvailableNavigatables,
                    graphHierarchyLookup = state.graphHierarchyLookup,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.ClearScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.navigatable.route == action.route) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.navigatable.route == action.route) {
                    state.currentEntry.copy(params = state.currentEntry.params - action.key)
                } else {
                    state.currentEntry
                }
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack,
                    precomputedData = precomputedData
                )
                
                state.copy(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack,
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
                    graphDefinitions = state.graphDefinitions,
                    availableRoutes = state.availableRoutes,
                    allAvailableNavigatables = state.allAvailableNavigatables,
                    graphHierarchyLookup = state.graphHierarchyLookup,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            // Legacy actions - maintain compatibility
            is NavigationAction.UpdateCurrentEntry -> {
                val updatedBackStack = state.backStack.dropLast(1) + action.entry
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = action.entry,
                    backStack = updatedBackStack,
                    precomputedData = precomputedData
                )
                
                state.copy(
                    currentEntry = action.entry,
                    backStack = updatedBackStack,
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
                    graphDefinitions = state.graphDefinitions,
                    availableRoutes = state.availableRoutes,
                    allAvailableNavigatables = state.allAvailableNavigatables,
                    graphHierarchyLookup = state.graphHierarchyLookup,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            is NavigationAction.UpdateBackStack -> {
                val newCurrentEntry = action.backStack.lastOrNull() ?: state.currentEntry
                
                val computedState = computeNavigationDerivedState(
                    currentEntry = newCurrentEntry,
                    backStack = action.backStack,
                    precomputedData = precomputedData
                )
                
                state.copy(
                    currentEntry = newCurrentEntry,
                    backStack = action.backStack,
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
                    graphDefinitions = state.graphDefinitions,
                    availableRoutes = state.availableRoutes,
                    allAvailableNavigatables = state.allAvailableNavigatables,
                    graphHierarchyLookup = state.graphHierarchyLookup,
                    activeModalContexts = state.activeModalContexts,
                    guidedFlowDefinitions = state.guidedFlowDefinitions,
                    activeGuidedFlowState = state.activeGuidedFlowState
                )
            }

            // GuidedFlow actions
            is NavigationAction.CreateGuidedFlow -> {
                state.copy(
                    guidedFlowDefinitions = state.guidedFlowDefinitions + (action.definition.guidedFlow to action.definition)
                )
            }

            is NavigationAction.UpdateActiveGuidedFlow -> {
                state.copy(activeGuidedFlowState = action.flowState)
            }

            is NavigationAction.ClearActiveGuidedFlow -> {
                state.copy(activeGuidedFlowState = null)
            }

            else -> state
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
        NavigationLogic(storeAccessor = storeAccessor, precomputedData = precomputedData)
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

                // Build parent lookup for nested graphs
                graph.nestedGraphs.forEach { nestedGraph ->
                    parentGraphLookup[nestedGraph.route] = graph.route
                }

                // Root graph navigatables are globally accessible
                if (graph.route == "root") {
                    graph.navigatables.forEach { navigatable ->
                        availableNavigatables[navigatable.route] = navigatable
                        routeToNavigatable[navigatable.route] = navigatable
                        // Root level navigatables have simple paths
                        navigatableToFullPath[navigatable] = navigatable.route
                    }
                }

                // All navigatables go into the comprehensive map
                graph.navigatables.forEach { navigatable ->
                    allNavigatables[navigatable.route] = navigatable
                    navigatableToGraph[navigatable] = graph.route
                    routeToNavigatable[navigatable.route] = navigatable

                    // Build full path for this navigatable
                    if (graph.route != "root") {
                        val graphPath = buildGraphPathToRoot(graph.route, parentGraphLookup)
                        val fullPath = if (graphPath.isEmpty()) {
                            navigatable.route
                        } else {
                            "$graphPath/${navigatable.route}"
                        }
                        navigatableToFullPath[navigatable] = fullPath
                    }
                    // Root navigatables already handled above
                }

                // Process nested graphs
                graph.nestedGraphs.forEach { collectGraphs(it) }
            }

            collectGraphs(rootGraph)

            // Create  route resolver with all precomputed data
            val routeResolver = RouteResolver.create(graphDefinitions)

            // Build graph hierarchies
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

            ReaktivDebug.nav("🎯 Precomputed ${allNavigatables.size} navigatables across ${graphDefinitions.size} graphs")
            ReaktivDebug.nav("📍 Built ${navigatableToFullPath.size} navigatable-to-path mappings for NavigationTarget resolution")

            return PrecomputedNavigationData(
                routeResolver = routeResolver,
                availableNavigatables = availableNavigatables,
                graphDefinitions = graphDefinitions,
                allNavigatables = allNavigatables,
                graphHierarchies = graphHierarchies,
                navigatableToGraph = navigatableToGraph,
                routeToNavigatable = routeToNavigatable,
                navigatableToFullPath = navigatableToFullPath // Now properly populated!
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
    // Compute ordered back stack
    val orderedBackStack = backStack.mapIndexed { index, entry ->
        entry.copy(stackPosition = index)
    }
    
    // Compute visible layers
    val visibleLayers = computeVisibleLayers(orderedBackStack, existingModalContexts)
    
    // Compute current full path
    val currentFullPath = precomputedData.routeResolver.buildFullPathForEntry(currentEntry)
        ?: currentEntry.navigatable.route
        
    // Compute path segments
    val currentPathSegments = currentFullPath.split("/").filter { it.isNotEmpty() }
    
    // Compute current graph hierarchy
    val currentGraphHierarchy = precomputedData.graphHierarchies[currentEntry.graphId] 
        ?: listOf(currentEntry.graphId)
    
    // Compute breadcrumbs
    val breadcrumbs = buildBreadcrumbs(currentPathSegments, precomputedData.graphDefinitions)
    
    // Compute boolean flags
    val canGoBack = backStack.size > 1
    val isCurrentModal = currentEntry.isModal
    val isCurrentScreen = currentEntry.isScreen
    val hasModalsInStack = backStack.any { it.isModal }
    val effectiveDepth = backStack.size
    val navigationDepth = currentPathSegments.size
    
    // Compute layer entries
    val entriesByLayer = visibleLayers.map { it.entry }.groupBy { it.navigatable.renderLayer }
    val contentLayerEntries = entriesByLayer[RenderLayer.CONTENT] ?: emptyList()
    val globalOverlayEntries = entriesByLayer[RenderLayer.GLOBAL_OVERLAY] ?: emptyList()
    val systemLayerEntries = entriesByLayer[RenderLayer.SYSTEM] ?: emptyList()
    val renderableEntries = visibleLayers.map { it.entry }
    
    // Compute modal-specific state  
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
    
    // Fallback to old logic if no context found
    val modalIndex = backStack.indexOf(modalEntry)
    if (modalIndex <= 0) return null
    
    // Look backwards from modal position to find the screen it was opened from
    return backStack.subList(0, modalIndex).lastOrNull { it.isScreen }
}

fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}