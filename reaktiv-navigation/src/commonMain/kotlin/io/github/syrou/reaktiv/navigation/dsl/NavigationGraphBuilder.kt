package io.github.syrou.reaktiv.navigation.dsl

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.MutableNavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.definition.StartDestination

class NavigationGraphBuilder(
    private val route: String
) {
    private var startDestination: StartDestination? = null
    private val navigatables = mutableListOf<Navigatable>()
    private val nestedGraphs = mutableListOf<NavigationGraph>()
    private var graphLayout: (@Composable (@Composable () -> Unit) -> Unit)? = null

    
    fun startScreen(screen: Screen) {
        if (startDestination != null) {
            throw IllegalStateException(
                "Start destination already set for graph '$route'. " +
                        "Use either startScreen() or startGraph(), not both."
            )
        }
        this.startDestination = StartDestination.DirectScreen(screen)
        if (!navigatables.contains(screen)) {
            navigatables.add(screen)
        }
    }

    
    fun startGraph(graphId: String) {
        if (startDestination != null) {
            throw IllegalStateException(
                "Start destination already set for graph '${this.route}'. " +
                        "Use either startScreen() or startGraph(), not both."
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

    internal fun build(): NavigationGraph {
        return MutableNavigationGraph(
            route = this.route,
            startDestination = this.startDestination,
            navigatables = this.navigatables.toList(),
            nestedGraphs = this.nestedGraphs.toList(),
            layout = this.graphLayout
        )
    }
}