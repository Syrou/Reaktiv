package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import io.github.syrou.reaktiv.navigation.model.NavigatableInterceptMap

/**
 * A named group of [Navigatable] destinations and optional nested sub-graphs.
 *
 * Graphs form the structural backbone of the navigation hierarchy. Each graph has a unique
 * [route] (its "graph ID"), a list of owned [navigatables], and optional [nestedGraphs].
 * Guards and entry-point overrides are attached at the graph level via [interceptDefinition]
 * and [entryDefinition].
 *
 * Use the `createNavigationModule { graph(...) { ... } }` DSL rather than implementing
 * this interface directly.
 *
 * @see Screen
 * @see NavigationState.isInGraph
 */
public interface NavigationGraph : NavigationNode {
    override val route: String

    /** The default destination shown when navigating to this graph without a specific target. */
    public val startDestination: StartDestination?

    /** Direct children of this graph. */
    public val navigatables: List<Navigatable>

    /** Sub-graphs owned by this graph. */
    public val nestedGraphs: List<NavigationGraph>

    /** Optional shared layout wrapper applied to every screen inside this graph. */
    public val layout: (@Composable (@Composable () -> Unit) -> Unit)?

    /** Guard evaluated before entering any route within this graph. */
    public val interceptDefinition: InterceptDefinition? get() = null

    /** Dynamic entry-point resolver; overrides [startDestination] when present. */
    public val entryDefinition: EntryDefinition? get() = null

    /**
     * Intercept definitions for navigatables registered directly inside an `intercept { }`
     * block rather than inside a nested named graph. These navigatables land in the parent
     * graph's [navigatables] list but must still be guarded by the intercept.
     */
    public val navigatableIntercepts: NavigatableInterceptMap get() = emptyMap()

    /**
     * Returns a flat map of route → [Navigatable] for this graph and all nested graphs.
     */
    public fun getAllNavigatables(): Map<String, Navigatable> = buildMap {
        navigatables.forEach { navigatable -> put(navigatable.route, navigatable) }
        nestedGraphs.forEach { nestedGraph ->
            putAll(nestedGraph.getAllNavigatables())
        }
    }

    /**
     * Returns the graph that directly owns a navigatable with the given [route],
     * searching recursively through [nestedGraphs].
     *
     * @param route The route of the navigatable to search for.
     * @return The owning graph, or `null` if not found.
     */
    public fun findGraphContaining(route: String): NavigationGraph? {
        if (navigatables.any { it.route == route }) return this
        return nestedGraphs.firstNotNullOfOrNull { it.findGraphContaining(route) }
    }

    /**
     * Returns the nested graph with the given [graphId], searching recursively.
     *
     * @param graphId The [route] of the nested graph to find.
     * @return The matching graph, or `null` if not found.
     */
    public fun findNestedGraph(graphId: String): NavigationGraph? {
        return nestedGraphs.find { it.route == graphId }
            ?: nestedGraphs.firstNotNullOfOrNull { it.findNestedGraph(graphId) }
    }

    /**
     * Resolves the initial [Navigatable] for this graph, following [StartDestination.GraphReference]
     * chains through [graphDefinitions].
     *
     * @param graphDefinitions All known graphs keyed by their route.
     * @return The resolved start screen, or `null` if [startDestination] is `null`.
     */
    public fun resolveStartScreen(graphDefinitions: Map<String, NavigationGraph>): Navigatable? {
        return when (val dest = startDestination) {
            is StartDestination.DirectScreen -> dest.screen
            is StartDestination.GraphReference -> {
                val referencedGraph = graphDefinitions[dest.graphId]
                referencedGraph?.resolveStartScreen(graphDefinitions)
            }
            null -> null
        }
    }
}
