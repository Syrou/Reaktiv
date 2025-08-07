package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class NavigationState(
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,
    @Transient
    val precomputedData: PrecomputedNavigationData? = null
) : ModuleState {
    @Transient
    private val _visibleLayers: List<NavigationLayer> by lazy { computeVisibleLayers() }

    @Transient
    private val _orderedBackStack: List<NavigationEntry> by lazy { computeOrderedBackStack() }

    @Transient
    private val _currentFullPath: String by lazy { computeCurrentFullPath() }

    @Transient
    private val _currentGraphHierarchy: List<String> by lazy { computeCurrentGraphHierarchy() }

    val availableNavigatables: Map<String, Navigatable>
        get() = precomputedData?.availableNavigatables ?: emptyMap()

    val allAvailableNavigatables: Map<String, Navigatable>
        get() = precomputedData?.allNavigatables ?: emptyMap()

    val graphDefinitions: Map<String, NavigationGraph>
        get() = precomputedData?.graphDefinitions ?: emptyMap()

    val canGoBack: Boolean
        get() = backStack.size > 1

    fun hasRoute(route: String): Boolean {
        return precomputedData?.routeToNavigatable?.containsKey(route) ?: false
    }

    val orderedBackStack: List<NavigationEntry>
        get() = _orderedBackStack

    private fun computeOrderedBackStack(): List<NavigationEntry> {
        return backStack.mapIndexed { index, entry ->
            entry.copy(stackPosition = index)
        }
    }

    val visibleLayers: List<NavigationLayer>
        get() = _visibleLayers

    private fun computeVisibleLayers(): List<NavigationLayer> {
        if (orderedBackStack.isEmpty()) return emptyList()

        val layers = mutableListOf<NavigationLayer>()
        val currentEntry = orderedBackStack.last()

        if (currentEntry.isModal) {
            val modal = currentEntry.navigatable as Modal
            val underlyingScreen = orderedBackStack.asReversed().drop(1).firstOrNull { it.isScreen }

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

    /**
     * Groups visible entries by their render layer
     */
    @Transient
    val entriesByLayer: Map<RenderLayer, List<NavigationEntry>> by lazy {
        visibleLayers
            .map { it.entry }
            .groupBy { entry ->
                entry.navigatable.renderLayer
            }
    }

    /**
     * Content layer entries (normal screens in graph hierarchy)
     */
    val contentLayerEntries: List<NavigationEntry>
        get() = entriesByLayer[RenderLayer.CONTENT] ?: emptyList()

    /**
     * Global overlay entries (full-screen modals)
     */
    val globalOverlayEntries: List<NavigationEntry>
        get() = entriesByLayer[RenderLayer.GLOBAL_OVERLAY] ?: emptyList()

    /**
     * System layer entries (toasts, notifications)
     */
    val systemLayerEntries: List<NavigationEntry>
        get() = entriesByLayer[RenderLayer.SYSTEM] ?: emptyList()

    val renderableEntries: List<NavigationEntry>
        get() = visibleLayers.map { it.entry }

    val isCurrentModal: Boolean
        get() = currentEntry.isModal

    val isCurrentScreen: Boolean
        get() = currentEntry.isScreen

    val underlyingScreen: NavigationEntry?
        get() = if (isCurrentModal) {
            orderedBackStack.asReversed().drop(1).firstOrNull { it.isScreen }
        } else null

    val effectiveDepth: Int
        get() = backStack.size

    val hasModalsInStack: Boolean
        get() = backStack.any { it.isModal }

    val modalsInStack: List<NavigationEntry>
        get() = backStack.filter { it.isModal }

    val currentFullPath: String
        get() = _currentFullPath

    private fun computeCurrentFullPath(): String {
        return precomputedData?.routeResolver?.buildFullPathForEntry(currentEntry) ?: currentEntry.navigatable.route
    }

    val currentPathSegments: List<String>
        get() = currentFullPath.split("/").filter { it.isNotEmpty() }

    val currentGraphHierarchy: List<String>
        get() = _currentGraphHierarchy

    private fun computeCurrentGraphHierarchy(): List<String> {
        return precomputedData?.graphHierarchies?.get(currentEntry.graphId) ?: listOf(currentEntry.graphId)
    }

    fun isInGraph(graphId: String): Boolean {
        return (if(!isCurrentModal) currentGraphHierarchy.contains(graphId) else precomputedData?.graphHierarchies?.get(underlyingScreen?.graphId)?.contains(graphId)) == true
    }

    fun isAtPath(path: String): Boolean {
        return currentFullPath == path.trimStart('/').trimEnd('/') || visibleLayers.find { it.entry.navigatable.route == path } != null
    }

    val navigationDepth: Int
        get() = currentPathSegments.size

    val breadcrumbs: List<NavigationBreadcrumb>
        get() = buildBreadcrumbs()

    private fun buildBreadcrumbs(): List<NavigationBreadcrumb> {
        val breadcrumbs = mutableListOf<NavigationBreadcrumb>()
        val pathSegments = currentPathSegments

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

    fun getZIndex(entry: NavigationEntry): Float {
        return visibleLayers.find { it.entry == entry }?.zIndex ?: entry.zIndex
    }
}

data class NavigationBreadcrumb(
    val label: String,
    val path: String,
    val isGraph: Boolean
)