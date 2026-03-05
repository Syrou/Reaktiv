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

    private fun checkNoStartDestination() {
        if (startDestination != null || pendingEntryDefinition != null) {
            error("Start destination already set for graph '$route'.")
        }
    }

    /**
     * Set the start destination to a static screen.
     *
     * @param screen The screen to navigate to when entering this graph directly
     */
    fun start(screen: Screen) {
        checkNoStartDestination()
        startDestination = StartDestination.DirectScreen(screen)
        if (screen !in navigatables) navigatables.add(screen)
    }

    /**
     * Set the start destination to another graph by its route ID.
     *
     * When this graph is entered directly, navigation is forwarded to [graphId].
     * If [graphId] itself has a dynamic [start] lambda, a `loadingModal` must be
     * configured at the module level.
     *
     * @param graphId The route of the graph to delegate entry to
     */
    fun start(graphId: String) {
        checkNoStartDestination()
        startDestination = StartDestination.GraphReference(graphId)
    }

    /**
     * Define a dynamic start destination evaluated at navigation time.
     *
     * [route] is evaluated when navigating directly to this graph to determine the destination.
     * Return any [NavigationNode] — a [Screen], [Navigatable], or graph route.
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
     *     start(
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
    fun start(
        route: suspend (StoreAccessor) -> NavigationNode,
        loadingThreshold: Duration = 200.milliseconds
    ) {
        checkNoStartDestination()
        pendingEntryDefinition = EntryDefinition(
            route = route,
            loadingThreshold = loadingThreshold
        )
    }

    @Deprecated("Use start(screen) instead", ReplaceWith("start(screen)"))
    fun entry(screen: Screen) = start(screen)

    @Deprecated("Use start(route, loadingThreshold) instead", ReplaceWith("start(route, loadingThreshold)"))
    fun entry(
        route: suspend (StoreAccessor) -> NavigationNode,
        loadingThreshold: Duration = 200.milliseconds
    ) = start(route, loadingThreshold)

    @Deprecated("Use start(screen) instead", ReplaceWith("start(screen)"))
    fun startScreen(screen: Screen) = start(screen)

    @Deprecated("Use start(graphId) instead", ReplaceWith("start(graphId)"))
    fun startGraph(graphId: String) = start(graphId)

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
     * **Guard chaining**: `intercept` blocks may be nested to arbitrary depth. Guards run in
     * declaration order — outermost first. Navigation proceeds only when every guard in the chain
     * returns [GuardResult.Allow]. The first non-Allow result stops evaluation immediately;
     * inner guards are never called.
     *
     * Multiple `intercept` blocks at the same level are independent — each wrapped graph
     * accumulates only the guards that apply to it.
     *
     * Example — single guard:
     * ```kotlin
     * rootGraph {
     *     start(startScreen)
     *     screens(startScreen, loginScreen)
     *     intercept(
     *         guard = { store ->
     *             if (store.selectState<AuthState>().value.isLoggedIn) GuardResult.Allow
     *             else GuardResult.RedirectTo(loginScreen)
     *         }
     *     ) {
     *         graph("workspace") {
     *             start(workspaceHome)
     *             screens(workspaceHome, profileScreen)
     *         }
     *     }
     * }
     * ```
     *
     * Example — chained guards (startup check → auth check):
     * ```kotlin
     * rootGraph {
     *     start(startScreen)
     *     screens(startScreen, loginScreen)
     *     intercept(
     *         guard = { store ->
     *             if (store.selectState<AppState>().value.startupReady) GuardResult.Allow
     *             else GuardResult.Reject
     *         }
     *     ) {
     *         intercept(
     *             guard = { store ->
     *                 if (store.selectState<AuthState>().value.isLoggedIn) GuardResult.Allow
     *                 else GuardResult.RedirectTo(loginScreen)
     *             }
     *         ) {
     *             graph("workspace") {
     *                 start(workspaceHome)
     *                 screens(workspaceHome, profileScreen)
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * @param guard Guard evaluated before navigation; returns [GuardResult] decision
     * @param loadingThreshold How long to wait before showing the global loading modal (default 200ms)
     * @param block Builder block containing the graphs and screens to protect
     *
     * @see InterceptDefinition
     * @see GuardResult
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
            val existing = nestedGraph.interceptDefinition
            val mergedIntercept = if (existing != null) existing.prependOuter(interceptDef) else interceptDef
            val interceptedGraph = (nestedGraph as MutableNavigationGraph).copy(interceptDefinition = mergedIntercept)
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
