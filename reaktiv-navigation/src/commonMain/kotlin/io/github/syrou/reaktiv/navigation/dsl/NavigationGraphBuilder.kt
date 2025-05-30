package io.github.syrou.reaktiv.navigation.dsl

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.GraphEnterBehavior
import io.github.syrou.reaktiv.navigation.definition.MutableNavigationGraph
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup

class NavigationGraphBuilder(
    private val graphId: String
) {
    private var startScreen: Screen? = null
    private val screens = mutableListOf<Screen>()
    private val nestedGraphs = mutableListOf<NavigationGraph>()
    private var parentGraph: NavigationGraph? = null
    private var graphEnterBehavior: GraphEnterBehavior = GraphEnterBehavior.ResumeOrStart
    private var retainState: Boolean = false
    private var graphLayout: (@Composable (@Composable () -> Unit) -> Unit)? = null

    fun startScreen(screen: Screen) {
        this.startScreen = screen
        if (!screens.contains(screen)) {
            screens.add(screen)
        }
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

        // Set parent reference
        if (nestedGraph is MutableNavigationGraph) {
            nestedGraph.setParent(this.build())
        }

        nestedGraphs.add(nestedGraph)
        return nestedGraph
    }

    fun enterBehavior(behavior: GraphEnterBehavior) {
        this.graphEnterBehavior = behavior
    }

    fun retainState(retain: Boolean) {
        this.retainState = retain
    }

    /**
     * Define a custom layout for this graph
     */
    fun layout(layoutComposable: @Composable (@Composable () -> Unit) -> Unit) {
        println("DEBUG: Setting layout for graph: $graphId")
        this.graphLayout = layoutComposable
    }

    fun tabs(tabScreens: Map<String, Screen>) {
        tabScreens.forEach { (tabId, screen) ->
            graph("tab_$tabId") {
                startScreen(screen)
                enterBehavior(GraphEnterBehavior.ResumeOrStart)
                retainState(true)
            }
        }
    }

    fun modal(modalId: String, vararg modalScreens: Screen) {
        require(modalScreens.isNotEmpty()) { "Modal must have at least one screen" }
        graph(modalId) {
            startScreen(modalScreens.first())
            screens(*modalScreens)
            enterBehavior(GraphEnterBehavior.StartAtRoot)
            retainState(false)
        }
    }

    fun flow(flowId: String, vararg flowScreens: Screen) {
        require(flowScreens.isNotEmpty()) { "Flow must have at least one screen" }
        graph(flowId) {
            startScreen(flowScreens.first())
            screens(*flowScreens)
            enterBehavior(GraphEnterBehavior.StartAtRoot)
            retainState(true)
        }
    }

    internal fun build(): NavigationGraph {
        requireNotNull(startScreen) { "Start screen must be set for graph: $graphId" }

        return MutableNavigationGraph(
            graphId = this.graphId,
            startScreen = this.startScreen!!,
            screens = this.screens.toList(),
            nestedGraphs = this.nestedGraphs.toList(),
            _parentGraph = this.parentGraph,
            graphEnterBehavior = this.graphEnterBehavior,
            retainState = this.retainState,
            layout = this.graphLayout
        )
    }
}