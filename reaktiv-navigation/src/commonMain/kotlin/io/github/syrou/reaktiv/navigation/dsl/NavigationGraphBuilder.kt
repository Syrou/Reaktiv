package io.github.syrou.reaktiv.navigation.dsl

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.MutableNavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class NavigationGraphBuilder(
    private val route: String
) {
    private var startDestination: StartDestination? = null
    private val navigatables = mutableListOf<Navigatable>()
    private val nestedGraphs = mutableListOf<NavigationGraph>()
    private var graphLayout: (@Composable (@Composable () -> Unit) -> Unit)? = null
    private var pendingEntryDefinition: EntryDefinition? = null

    /**
     * Set the start destination to a static screen.
     *
     * Replaces the deprecated [startScreen] method.
     *
     * @param screen The screen to navigate to when entering this graph directly
     */
    fun entry(screen: Screen) {
        if (startDestination != null) {
            error("Start destination already set for graph '$route'.")
        }
        startDestination = StartDestination.DirectScreen(screen)
        if (screen !in navigatables) navigatables.add(screen)
    }

    /**
     * Define dynamic entry with a typed [NavigationNode] route selector.
     *
     * [route] is evaluated when navigating directly to this graph to determine the destination.
     * Return any [NavigationNode] — a [Screen], [Navigatable], or [Graph] object.
     *
     * If evaluation exceeds [loadingThreshold], the global loading modal configured via
     * `loadingModal()` at the module level is shown as a [RenderLayer.SYSTEM] overlay.
     *
     * To guard all routes inside this graph (including deep links and direct screen navigation),
     * wrap the graph with [intercept] at the parent level instead.
     *
     * Example:
     * ```kotlin
     * graph("content") {
     *     entry(
     *         route = { store ->
     *             val state = store.selectState<ContentState>().value
     *             if (state.releases.isNotEmpty()) ReleasesScreen else NoContentScreen
     *         }
     *     )
     *     screens(ReleasesScreen, NoContentScreen)
     * }
     * ```
     *
     * @param route Typed selector returning the [NavigationNode] to navigate to
     * @param loadingThreshold How long to wait before showing the global loading modal (default 200ms)
     */
    fun entry(
        route: suspend (StoreAccessor) -> NavigationNode,
        loadingThreshold: Duration = 200.milliseconds
    ) {
        pendingEntryDefinition = EntryDefinition(
            route = route,
            loadingThreshold = loadingThreshold
        )
    }

    @Deprecated("Use entry(screen) instead", ReplaceWith("entry(screen)"))
    fun startScreen(screen: Screen) = entry(screen)

    fun startGraph(graphId: String) {
        if (startDestination != null) {
            throw IllegalStateException(
                "Start destination already set for graph '${this.route}'. " +
                        "Use either entry(screen) or startGraph(), not both."
            )
        }
        this.startDestination = StartDestination.GraphReference(graphId)
    }

    fun screens(vararg screens: Screen) {
        this.navigatables.addAll(screens.filterNot(this.navigatables::contains))
    }

    fun modals(vararg modals: Modal) {
        this.navigatables.addAll(modals.filterNot(this.navigatables::contains))
    }

    fun screenGroup(screenGroup: ScreenGroup) {
        this.navigatables.addAll(screenGroup.screens.filterNot(this.navigatables::contains))
    }

    fun graph(graphId: String, builder: NavigationGraphBuilder.() -> Unit): NavigationGraph {
        val nestedBuilder = NavigationGraphBuilder(graphId)
        nestedBuilder.apply(builder)
        val nestedGraph = nestedBuilder.build()
        nestedGraphs.add(nestedGraph)
        return nestedGraph
    }

    fun graph(graph: Graph, builder: NavigationGraphBuilder.() -> Unit): NavigationGraph {
        return graph(graph.route, builder)
    }

    fun layout(layoutComposable: @Composable (@Composable () -> Unit) -> Unit) {
        this.graphLayout = layoutComposable
    }

    /**
     * Define a protected region where all nested graphs and screens require an intercept guard.
     *
     * The [guard] is evaluated before navigation is committed to any route inside this block.
     * It returns a [GuardResult] that decides whether to allow, reject, or redirect navigation.
     *
     * If guard evaluation exceeds [loadingThreshold], the global loading modal configured via
     * `loadingModal()` at the module level is shown as a [RenderLayer.SYSTEM] overlay.
     *
     * Example:
     * ```kotlin
     * rootGraph {
     *     entry(startScreen)
     *     screens(startScreen, loginScreen)
     *     intercept(
     *         guard = { store ->
     *             if (store.selectState<AuthState>().value.isLoggedIn) GuardResult.Allow
     *             else GuardResult.RedirectTo(loginScreen)
     *         }
     *     ) {
     *         graph("workspace") {
     *             entry(workspaceHome)
     *             screens(workspaceHome, inviteScreen)
     *         }
     *     }
     * }
     * ```
     *
     * @param guard Guard evaluated before navigation; returns [GuardResult] decision
     * @param loadingThreshold How long to wait before showing the global loading modal (default 200ms)
     * @param block Builder block containing the graphs and screens to intercept
     */
    fun intercept(
        guard: NavigationGuard,
        loadingThreshold: Duration = 200.milliseconds,
        block: NavigationGraphBuilder.() -> Unit
    ) {
        val interceptDef = InterceptDefinition(guard, loadingThreshold)
        val innerBuilder = NavigationGraphBuilder("_intercept_")
        innerBuilder.apply(block)

        navigatables.addAll(innerBuilder.navigatables.filterNot(navigatables::contains))

        for (nestedGraph in innerBuilder.nestedGraphs) {
            val interceptedGraph = (nestedGraph as MutableNavigationGraph).copy(interceptDefinition = interceptDef)
            nestedGraphs.add(interceptedGraph)
        }
    }

    internal fun build(): NavigationGraph {
        return MutableNavigationGraph(
            route = this.route,
            startDestination = this.startDestination,
            navigatables = this.navigatables.toList(),
            nestedGraphs = this.nestedGraphs.toList(),
            layout = this.graphLayout,
            entryDefinition = this.pendingEntryDefinition
        )
    }
}
