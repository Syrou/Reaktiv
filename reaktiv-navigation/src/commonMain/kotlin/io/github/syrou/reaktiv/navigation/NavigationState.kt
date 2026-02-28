package io.github.syrou.reaktiv.navigation

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Immutable snapshot of the navigation system's runtime state.
 *
 * All derived/computed properties (layer lists, flags, hierarchy) are pre-computed by the
 * reducer so that reads in the Compose tree are allocation-free.
 *
 * Example — check current location in Compose:
 * ```kotlin
 * val navState by selectState<NavigationState>().collectAsState()
 * if (navState.isInGraph("auth")) { ... }
 * if (navState.isAtPath("home/dashboard")) { ... }
 * ```
 */
@Stable
@Serializable
data class NavigationState(
    // Core navigation state
    /** The entry that is currently active (top of stack). */
    val currentEntry: NavigationEntry,
    /** Ordered back stack; the last element is always equal to [currentEntry]. */
    val backStack: List<NavigationEntry>,

    /** The most recent action dispatched by the navigation system, used for content preservation. */
    val lastNavigationAction: NavigationAction? = null,

    /** How long rendered screen content is retained in memory after being popped from the stack. */
    val screenRetentionDuration: Duration,

    // Computed properties — set by the reducer, fully serializable
    /** Entries that should be rendered, ordered from bottom to top layer. */
    val visibleLayers: List<NavigationEntry>,
    /** The full slash-separated path for [currentEntry], e.g. `"auth/login"`. */
    val currentFullPath: String,
    /** Ordered list of graph IDs from root to the graph containing [currentEntry]. */
    val currentGraphHierarchy: List<String>,
    /** Breadcrumb trail derived from [currentFullPath], suitable for navigation UIs. */
    val breadcrumbs: List<NavigationBreadcrumb>,

    // Boolean flags — computed by the reducer
    /** `true` when [currentEntry] resolves to a [Modal]. */
    val isCurrentModal: Boolean,
    /** `true` when [currentEntry] resolves to a [Screen]. */
    val isCurrentScreen: Boolean,
    /** `true` when at least one modal is present anywhere in [backStack]. */
    val hasModalsInStack: Boolean,

    // Layer entries — computed by the reducer
    /** Entries assigned to [RenderLayer.CONTENT]. */
    val contentLayerEntries: List<NavigationEntry>,
    /** Entries assigned to [RenderLayer.GLOBAL_OVERLAY]. */
    val globalOverlayEntries: List<NavigationEntry>,
    /** Entries assigned to [RenderLayer.SYSTEM] (e.g. loading modals). */
    val systemLayerEntries: List<NavigationEntry>,
    /** The screen rendered underneath the current modal, or `null` if not in a modal. */
    val underlyingScreen: NavigationEntry?,
    /** All modal entries currently present in [backStack]. */
    val modalsInStack: List<NavigationEntry>,

    /**
     * Graph hierarchy of [underlyingScreen], used so that [isInGraph] works correctly
     * when [isCurrentModal] is `true`.
     */
    val underlyingScreenGraphHierarchy: List<String>? = null,

    /** Active modal contexts keyed by the modal entry's full path. */
    val activeModalContexts: Map<String, ModalContext>,

    /**
     * Navigation that was stored when a guard returned [GuardResult.PendAndRedirectTo].
     * Call [NavigationLogic.resumePendingNavigation] to resume it after the guard condition is met.
     */
    val pendingNavigation: PendingNavigation? = null,

    /**
     * `true` until bootstrap (and any cold-start deep link) has fully resolved.
     * [NavigationRender] suppresses content layers while this is `true` to avoid
     * flashing the initial placeholder before the real destination is known.
     */
    val isBootstrapping: Boolean = true,

    /**
     * Resolved title string for [currentEntry], populated by [NavigationRender] after
     * invoking the navigatable's `titleResource` inside the Compose tree.
     */
    val currentTitle: String? = null
) : ModuleState {

    /** `true` when there is more than one entry in [backStack] and a back navigation is possible. */
    val canGoBack: Boolean get() = backStack.size > 1

    /** Number of entries currently in [backStack]. */
    val effectiveDepth: Int get() = backStack.size

    /** Path segments of [currentFullPath] with empty segments filtered out. */
    val currentPathSegments: List<String> get() = currentFullPath.split("/").filter { it.isNotEmpty() }

    /** Number of segments in [currentFullPath]. */
    val navigationDepth: Int get() = currentPathSegments.size

    /** [backStack] with each entry's [NavigationEntry.stackPosition] set to its index. */
    val orderedBackStack: List<NavigationEntry> get() = backStack.mapIndexed { i, e -> e.copy(stackPosition = i) }

    /** Alias for [visibleLayers] — entries that should be rendered. */
    val renderableEntries: List<NavigationEntry> get() = visibleLayers

    /** Layer entries grouped by their [RenderLayer]. */
    val entriesByLayer: Map<RenderLayer, List<NavigationEntry>>
        get() = mapOf(
            RenderLayer.CONTENT to contentLayerEntries,
            RenderLayer.GLOBAL_OVERLAY to globalOverlayEntries,
            RenderLayer.SYSTEM to systemLayerEntries
        )

    /**
     * Returns `true` if the current navigation position is inside the given [graphId].
     *
     * When the current entry is a modal, the check is performed against the graph hierarchy of
     * [underlyingScreen] so that modal content can still query its owning graph.
     *
     * @param graphId The route identifier of the graph to test membership in.
     */
    fun isInGraph(graphId: String): Boolean {
        return if (!isCurrentModal) {
            currentGraphHierarchy.contains(graphId)
        } else {
            underlyingScreenGraphHierarchy?.contains(graphId) ?: false
        }
    }

    /**
     * Returns `true` if any visible layer's route or path matches [path].
     *
     * @param path A slash-separated path or plain route to match against [currentFullPath]
     *   or any entry in [visibleLayers].
     */
    fun isAtPath(path: String): Boolean {
        val cleanPath = path.trimStart('/').trimEnd('/')
        return currentFullPath == cleanPath ||
                visibleLayers.any { it.route == cleanPath || it.path == cleanPath }
    }
}

/**
 * A single step in the breadcrumb trail derived from the current navigation path.
 *
 * @property label Human-readable segment name (first letter capitalised).
 * @property path Cumulative slash-joined path up to and including this segment.
 * @property isGraph `true` when this segment corresponds to a [NavigationGraph] route rather than a screen.
 */
@Serializable
data class NavigationBreadcrumb(
    val label: String,
    val path: String,
    val isGraph: Boolean
)
