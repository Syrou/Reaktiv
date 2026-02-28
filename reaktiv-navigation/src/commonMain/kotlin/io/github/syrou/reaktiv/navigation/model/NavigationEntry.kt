package io.github.syrou.reaktiv.navigation.model

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.Serializable

/**
 * A single entry in the navigation back stack representing one visited destination.
 *
 * Entries are immutable; the navigation system creates new instances when state changes.
 * The [path] is the full slash-separated route (e.g. `"auth/login"`), while [route] is
 * just the last segment (e.g. `"login"`).
 *
 * @property path Full path from the root graph, e.g. `"profile/settings"`.
 * @property params Parameters passed to this destination at navigation time.
 * @property stackPosition Zero-based index of this entry in the back stack.
 * @property navigatableRoute The route of the [Navigatable] that this entry targets
 *   (the last segment of [path]).
 */
@Stable
@Serializable
data class NavigationEntry(
    val path: String,
    val params: Params,
    val stackPosition: Int = 0,
    val navigatableRoute: String = path.substringAfterLast("/")
) {
    /** Alias for [navigatableRoute] — the short route of the [Navigatable]. */
    val route: String get() = navigatableRoute

    /**
     * A stable identity key combining [path] and [params], suitable for use as a Compose key.
     * Changes when the entry's destination or parameters change.
     */
    val stableKey: String get() = "${path}_${params.hashCode()}"

    /**
     * The ID of the [NavigationGraph] that directly owns this entry, derived from [path].
     * Returns `"root"` for top-level navigatables that live outside a named graph.
     */
    val graphId: String get() {
        val prefix = path.removeSuffix("/$navigatableRoute")
        return if (prefix == path || prefix.isEmpty()) "root"
        else prefix.substringAfterLast("/")
    }
}

/**
 * The result of resolving a route string to a concrete [Navigatable] within the graph hierarchy.
 *
 * @property targetNavigatable The resolved destination.
 * @property targetGraphId The graph that directly owns [targetNavigatable].
 * @property extractedParams Path parameters extracted from the route pattern (e.g. `{id}`).
 * @property navigationGraphId The graph referenced when this was a graph-reference resolution.
 * @property isGraphReference `true` when this resolution was triggered by a graph route rather
 *   than a direct screen route.
 */
data class RouteResolution(
    val targetNavigatable: Navigatable,
    val targetGraphId: String,
    val extractedParams: Params,
    val navigationGraphId: String? = null,
    val isGraphReference: Boolean = false
) {
    /**
     * Returns the graph ID that should be used for path building and hierarchy computation.
     * Prefers [navigationGraphId] when present, otherwise falls back to [targetGraphId].
     */
    fun getEffectiveGraphId(): String {
        return when {
            isGraphReference -> targetGraphId
            navigationGraphId != null -> navigationGraphId
            else -> targetGraphId
        }
    }
}

/**
 * A lightweight pairing of a resolved [Navigatable] and the graph ID it belongs to.
 *
 * @property navigatable The resolved destination.
 * @property actualGraphId The graph that owns [navigatable].
 */
data class ScreenResolution(
    val navigatable: Navigatable,
    val actualGraphId: String
)

/**
 * Creates a [NavigationEntry] for this [Navigatable] at the given [path].
 *
 * @param path Full slash-separated path for the entry.
 * @param params Parameters to attach to the entry.
 * @param stackPosition The entry's zero-based position in the back stack.
 * @return A new [NavigationEntry] with [Navigatable.route] as [NavigationEntry.navigatableRoute].
 */
fun Navigatable.toNavigationEntry(
    path: String,
    params: Params = Params.empty(),
    stackPosition: Int = 0
): NavigationEntry = NavigationEntry(
    path = path,
    params = params,
    stackPosition = stackPosition,
    navigatableRoute = this.route
)
