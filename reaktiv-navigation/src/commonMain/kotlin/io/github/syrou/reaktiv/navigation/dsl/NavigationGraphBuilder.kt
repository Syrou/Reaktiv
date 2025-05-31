package io.github.syrou.reaktiv.navigation.dsl

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.GraphEnterBehavior
import io.github.syrou.reaktiv.navigation.definition.MutableNavigationGraph
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.definition.StartDestination

class NavigationGraphBuilder(
    private val graphId: String
) {
    private var startDestination: StartDestination? = null
    private val screens = mutableListOf<Screen>()
    private val nestedGraphs = mutableListOf<NavigationGraph>()
    private var parentGraph: NavigationGraph? = null
    private var graphLayout: (@Composable (@Composable () -> Unit) -> Unit)? = null

    /**
     * Set the start screen directly
     */
    fun startScreen(screen: Screen) {
        if (startDestination != null) {
            throw IllegalStateException(
                "Start destination already set for graph '$graphId'. " +
                        "Use either startScreen() or startGraph(), not both."
            )
        }
        this.startDestination = StartDestination.DirectScreen(screen)
        if (!screens.contains(screen)) {
            screens.add(screen)
        }
    }

    /**
     * Set the start screen by referencing another graph
     * When navigating to this graph, it will use the referenced graph's start screen
     */
    fun startGraph(graphId: String) {
        if (startDestination != null) {
            throw IllegalStateException(
                "Start destination already set for graph '${this.graphId}'. " +
                        "Use either startScreen() or startGraph(), not both."
            )
        }
        this.startDestination = StartDestination.GraphReference(graphId)
    }

    fun screens(vararg screens: Screen) {
        this.screens.addAll(screens)
    }

    fun screenGroup(screenGroup: ScreenGroup) {
        this.screens.addAll(screenGroup.screens)
    }

    fun graph(graphId: String, builder: NavigationGraphBuilder.() -> Unit): NavigationGraph {
        val nestedBuilder = NavigationGraphBuilder(graphId)
        nestedBuilder.apply(builder)
        val nestedGraph = nestedBuilder.build()
        nestedGraphs.add(nestedGraph)
        return nestedGraph
    }

    fun layout(layoutComposable: @Composable (@Composable () -> Unit) -> Unit) {
        this.graphLayout = layoutComposable
    }

    internal fun build(): NavigationGraph {
        val resolvedDestination = startDestination
            ?: throw IllegalArgumentException(
                "Graph '$graphId' must have either startScreen() or startGraph() defined"
            )

        return MutableNavigationGraph(
            graphId = this.graphId,
            startDestination = resolvedDestination,
            screens = this.screens.toList(),
            nestedGraphs = this.nestedGraphs.toList(),
            _parentGraph = this.parentGraph,
            layout = this.graphLayout
        )
    }
}