package io.github.syrou.reaktiv.navigation

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Stable
@Serializable
data class NavigationState(
    // Core navigation data
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,

    // Track last navigation action for content preservation
    val lastNavigationAction: NavigationAction? = null,

    // Screen content retention configuration
    val screenRetentionDuration: Duration,

    // Computed state properties (set by reducer, fully serializable)
    val orderedBackStack: List<NavigationEntry>,
    val visibleLayers: List<NavigationLayer>,
    val currentFullPath: String,
    val currentPathSegments: List<String>,
    val currentGraphHierarchy: List<String>,
    val breadcrumbs: List<NavigationBreadcrumb>,

    // Boolean flags (computed by reducer)
    val canGoBack: Boolean,
    val isCurrentModal: Boolean,
    val isCurrentScreen: Boolean,
    val hasModalsInStack: Boolean,
    val effectiveDepth: Int,
    val navigationDepth: Int,

    // Layer entries (computed by reducer)
    val contentLayerEntries: List<NavigationEntry>,
    val globalOverlayEntries: List<NavigationEntry>,
    val systemLayerEntries: List<NavigationEntry>,
    val renderableEntries: List<NavigationEntry>,
    val underlyingScreen: NavigationEntry?,
    val modalsInStack: List<NavigationEntry>,

    // For modal isInGraph() checks - graph hierarchy of the underlying screen
    val underlyingScreenGraphHierarchy: List<String>? = null,

    // Modal contexts
    val activeModalContexts: Map<String, ModalContext>,

    // GuidedFlow state - store runtime modifications per flow route
    val guidedFlowModifications: Map<String, GuidedFlowDefinition> = emptyMap(),

    // Active guided flow state for the currently executing flow (backward compatibility)
    val activeGuidedFlowState: GuidedFlowState? = null
) : ModuleState {

    val entriesByLayer: Map<RenderLayer, List<NavigationEntry>>
        get() = mapOf(
            RenderLayer.CONTENT to contentLayerEntries,
            RenderLayer.GLOBAL_OVERLAY to globalOverlayEntries,
            RenderLayer.SYSTEM to systemLayerEntries
        )

    fun isInGraph(graphId: String): Boolean {
        return if (!isCurrentModal) {
            currentGraphHierarchy.contains(graphId)
        } else {
            underlyingScreenGraphHierarchy?.contains(graphId) ?: false
        }
    }

    fun isAtPath(path: String): Boolean {
        val cleanPath = path.trimStart('/').trimEnd('/')
        return currentFullPath == cleanPath ||
                visibleLayers.any { it.entry.navigatable.route == path }
    }

    fun getZIndex(entry: NavigationEntry): Float {
        return visibleLayers.find { it.entry == entry }?.zIndex ?: entry.zIndex
    }
}

@Serializable
data class NavigationBreadcrumb(
    val label: String,
    val path: String,
    val isGraph: Boolean
)

