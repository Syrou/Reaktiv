package io.github.syrou.reaktiv.navigation

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationProjection
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Immutable snapshot of the navigation system's runtime state.
 *
 * Everything that is a pure function of the back stack is grouped into [derived] and
 * exposed here under its own name, so `navState.visibleLayers` reads the same as it
 * always did.
 *
 * Example - check current location in Compose:
 * ```kotlin
 * val navState by selectState<NavigationState>().collectAsState()
 * if (navState.isInGraph("auth")) { ... }
 * if (navState.isAtPath("home/dashboard")) { ... }
 * ```
 */
@Stable
@Serializable
public data class NavigationState(
    /** The entry that is currently active (top of stack). */
    @Contextual val currentEntry: NavigationEntry,
    /** Ordered back stack; the last element is always equal to [currentEntry]. */
    val backStack: List<@Contextual NavigationEntry>,

    /** How long rendered screen content is retained in memory after being popped from the stack. */
    val screenRetentionDuration: Duration,

    /** Values computed from [backStack] and [activeModalContexts] by the reducer. */
    val derived: NavigationProjection,

    /** Active modal contexts keyed by the modal entry's full path. */
    val activeModalContexts: Map<String, ModalContext>,

    /** The most recent action dispatched by the navigation system, used for content preservation. */
    val lastNavigationAction: NavigationAction? = null,

    /**
     * Navigation that was stored when a guard returned [GuardResult.PendAndRedirectTo].
     * Resume it after the guard condition is met via `navigation { clearBackStack(); resumePendingNavigation() }`.
     */
    val pendingNavigation: PendingNavigation? = null,

    /**
     * `true` until bootstrap (and any cold-start deep link) has fully resolved.
     * [NavigationRender] suppresses content layers while this is `true` to avoid
     * flashing the initial placeholder before the real destination is known.
     */
    val isBootstrapping: Boolean = true,

    /**
     * `true` while a guard or entry-definition lambda is being evaluated and the
     * evaluation has exceeded its loading threshold. [NavigationRender] renders the
     * [io.github.syrou.reaktiv.navigation.definition.LoadingModal] directly as a
     * boolean-controlled overlay rather than a backstack entry while this is `true`.
     */
    val isEvaluatingNavigation: Boolean = false,

    /**
     * Live gesture scrub progress mirrored into state so followers and session
     * captures can replicate interactive gestures. Cleared by any other
     * navigation action.
     */
    val activeScrub: ScrubState? = null
) : ModuleState {

    @Deprecated(
        "The fourteen derived values are now grouped into NavigationProjection and exposed " +
            "through accessors of the same name. Reads are unchanged. Construct with derived = ... instead.",
        level = DeprecationLevel.WARNING
    )
    public constructor(
        currentEntry: NavigationEntry,
        backStack: List<NavigationEntry>,
        lastNavigationAction: NavigationAction?,
        screenRetentionDuration: Duration,
        visibleLayers: List<NavigationEntry>,
        currentFullPath: String,
        currentGraphHierarchy: List<String>,
        breadcrumbs: List<NavigationBreadcrumb>,
        isCurrentModal: Boolean,
        isCurrentScreen: Boolean,
        hasModalsInStack: Boolean,
        contentLayerEntries: List<NavigationEntry>,
        globalOverlayEntries: List<NavigationEntry>,
        systemLayerEntries: List<NavigationEntry>,
        underlyingScreen: NavigationEntry?,
        modalsInStack: List<NavigationEntry>,
        activeModalContexts: Map<String, ModalContext>,
        underlyingScreenGraphHierarchy: List<String>? = null,
        pendingNavigation: PendingNavigation? = null,
        isBootstrapping: Boolean = true,
        isEvaluatingNavigation: Boolean = false,
        showsNavigationChrome: Boolean = true,
        activeScrub: ScrubState? = null
    ) : this(
        currentEntry = currentEntry,
        backStack = backStack,
        screenRetentionDuration = screenRetentionDuration,
        derived = NavigationProjection(
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
        ),
        activeModalContexts = activeModalContexts,
        lastNavigationAction = lastNavigationAction,
        pendingNavigation = pendingNavigation,
        isBootstrapping = isBootstrapping,
        isEvaluatingNavigation = isEvaluatingNavigation,
        activeScrub = activeScrub
    )

    /** Entries that should be rendered, ordered from bottom to top layer. */
    val visibleLayers: List<NavigationEntry> get() = derived.visibleLayers

    /** The full slash-separated path for [currentEntry], e.g. `"auth/login"`. */
    val currentFullPath: String get() = derived.currentFullPath

    /** Ordered list of graph IDs from root to the graph containing [currentEntry]. */
    val currentGraphHierarchy: List<String> get() = derived.currentGraphHierarchy

    /** Breadcrumb trail derived from [currentFullPath], suitable for navigation UIs. */
    val breadcrumbs: List<NavigationBreadcrumb> get() = derived.breadcrumbs

    /** `true` when [currentEntry] resolves to a [Modal]. */
    val isCurrentModal: Boolean get() = derived.isCurrentModal

    /** `true` when [currentEntry] resolves to a [Screen]. */
    val isCurrentScreen: Boolean get() = derived.isCurrentScreen

    /** `true` when at least one modal is present anywhere in [backStack]. */
    val hasModalsInStack: Boolean get() = derived.hasModalsInStack

    /** Entries assigned to [RenderLayer.CONTENT]. */
    val contentLayerEntries: List<NavigationEntry> get() = derived.contentLayerEntries

    /** Entries assigned to [RenderLayer.GLOBAL_OVERLAY]. */
    val globalOverlayEntries: List<NavigationEntry> get() = derived.globalOverlayEntries

    /** Entries assigned to [RenderLayer.SYSTEM] (e.g. loading modals). */
    val systemLayerEntries: List<NavigationEntry> get() = derived.systemLayerEntries

    /** The screen rendered underneath the current modal, or `null` if not in a modal. */
    val underlyingScreen: NavigationEntry? get() = derived.underlyingScreen

    /** All modal entries currently present in [backStack]. */
    val modalsInStack: List<NavigationEntry> get() = derived.modalsInStack

    /**
     * Graph hierarchy of [underlyingScreen], used so that [isInGraph] works correctly
     * when [isCurrentModal] is `true`.
     */
    val underlyingScreenGraphHierarchy: List<String>? get() = derived.underlyingScreenGraphHierarchy

    /**
     * `true` when the current destination wants a navigation header.
     *
     * False while a modal is current, and false anywhere inside a graph that declares
     * [io.github.syrou.reaktiv.navigation.definition.Graph.showsNavigationChrome] as false, which a
     * presenting graph does by default because it carries its own chrome.
     */
    val showsNavigationChrome: Boolean get() = derived.showsNavigationChrome

    /** `true` when there is more than one entry in [backStack] and a back navigation is possible. */
    val canGoBack: Boolean get() = backStack.size > 1

    @Deprecated("Reads as backStack.size.", ReplaceWith("backStack.size"), DeprecationLevel.WARNING)
    val effectiveDepth: Int get() = backStack.size

    /** Path segments of [currentFullPath] with empty segments filtered out. */
    val currentPathSegments: List<String> get() = currentFullPath.split("/").filter { it.isNotEmpty() }

    /** Number of segments in [currentFullPath]. */
    val navigationDepth: Int get() = currentPathSegments.size

    /** [backStack] with each entry's [NavigationEntry.stackPosition] set to its index. */
    val orderedBackStack: List<NavigationEntry> get() = backStack.mapIndexed { i, e -> e.copy(stackPosition = i) }

    @Deprecated("Alias for visibleLayers.", ReplaceWith("visibleLayers"), DeprecationLevel.WARNING)
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
    public fun isInGraph(graphId: String): Boolean {
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
    public fun isAtPath(path: String): Boolean {
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
public data class NavigationBreadcrumb(
    val label: String,
    val path: String,
    val isGraph: Boolean
)
