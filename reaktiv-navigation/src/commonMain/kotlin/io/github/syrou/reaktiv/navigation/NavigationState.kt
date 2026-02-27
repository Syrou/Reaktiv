package io.github.syrou.reaktiv.navigation

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
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
    val visibleLayers: List<NavigationEntry>,
    val currentFullPath: String,
    val currentGraphHierarchy: List<String>,
    val breadcrumbs: List<NavigationBreadcrumb>,

    // Boolean flags (computed by reducer)
    val isCurrentModal: Boolean,
    val isCurrentScreen: Boolean,
    val hasModalsInStack: Boolean,

    // Layer entries (computed by reducer)
    val contentLayerEntries: List<NavigationEntry>,
    val globalOverlayEntries: List<NavigationEntry>,
    val systemLayerEntries: List<NavigationEntry>,
    val underlyingScreen: NavigationEntry?,
    val modalsInStack: List<NavigationEntry>,

    // For modal isInGraph() checks - graph hierarchy of the underlying screen
    val underlyingScreenGraphHierarchy: List<String>? = null,

    // Modal contexts
    val activeModalContexts: Map<String, ModalContext>,

    // Pending navigation stored when a guard denies access via PendAndRedirect policy
    val pendingNavigation: PendingNavigation? = null,

    // True until bootstrap (and any cold-start deep link) has fully resolved.
    // NavigationRender skips content layers while this is true to avoid flashing the
    // initial placeholder before the real destination is known.
    val isBootstrapping: Boolean = true,

    // Resolved title string for the current entry, set by NavigationRender after invoking
    // the navigatable's titleResource inside the Compose tree where stringResource is available.
    val currentTitle: String? = null
) : ModuleState {

    val canGoBack: Boolean get() = backStack.size > 1
    val effectiveDepth: Int get() = backStack.size
    val currentPathSegments: List<String> get() = currentFullPath.split("/").filter { it.isNotEmpty() }
    val navigationDepth: Int get() = currentPathSegments.size
    val orderedBackStack: List<NavigationEntry> get() = backStack.mapIndexed { i, e -> e.copy(stackPosition = i) }

    val renderableEntries: List<NavigationEntry> get() = visibleLayers

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
                visibleLayers.any { it.route == cleanPath || it.path == cleanPath }
    }
}

@Serializable
data class NavigationBreadcrumb(
    val label: String,
    val path: String,
    val isGraph: Boolean
)
