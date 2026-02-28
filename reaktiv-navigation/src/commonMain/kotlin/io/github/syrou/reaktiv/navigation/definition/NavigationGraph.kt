package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition

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
interface NavigationGraph : NavigationNode {
    override val route: String

    /** The default destination shown when navigating to this graph without a specific target. */
    val startDestination: StartDestination?

    /** Direct children of this graph. */
    val navigatables: List<Navigatable>

    /** Sub-graphs owned by this graph. */
    val nestedGraphs: List<NavigationGraph>

    /** Optional shared layout wrapper applied to every screen inside this graph. */
    val layout: (@Composable (@Composable () -> Unit) -> Unit)?

    /** Guard evaluated before entering any route within this graph. */
    val interceptDefinition: InterceptDefinition? get() = null

    /** Dynamic entry-point resolver; overrides [startDestination] when present. */
    val entryDefinition: EntryDefinition? get() = null

    /**
     * Returns a flat map of route → [Navigatable] for this graph and all nested graphs.
     */
    fun getAllNavigatables(): Map<String, Navigatable> = buildMap {
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
    fun findGraphContaining(route: String): NavigationGraph? {
        if (navigatables.any { it.route == route }) return this
        return nestedGraphs.firstNotNullOfOrNull { it.findGraphContaining(route) }
    }

    /**
     * Returns the nested graph with the given [graphId], searching recursively.
     *
     * @param graphId The [route] of the nested graph to find.
     * @return The matching graph, or `null` if not found.
     */
    fun findNestedGraph(graphId: String): NavigationGraph? {
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
    fun resolveStartScreen(graphDefinitions: Map<String, NavigationGraph>): Navigatable? {
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
