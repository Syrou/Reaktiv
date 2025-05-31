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
    private var startGraphId: String? = null // Reference to another graph's start screen
    private val screens = mutableListOf<Screen>()
    private val nestedGraphs = mutableListOf<NavigationGraph>()
    private var parentGraph: NavigationGraph? = null
    private var graphEnterBehavior: GraphEnterBehavior = GraphEnterBehavior.ResumeOrStart
    private var retainState: Boolean = false
    private var graphLayout: (@Composable (@Composable () -> Unit) -> Unit)? = null

    /**
     * Set the start screen directly
     */
    fun startScreen(screen: Screen) {
        if (startGraphId != null) {
            throw IllegalStateException("Cannot set both startScreen and startGraph. Use either startScreen() or startGraph(), not both.")
        }
        this.startScreen = screen
        if (!screens.contains(screen)) {
            screens.add(screen)
        }
    }

    /**
     * Set the start screen by referencing another graph
     * When navigating to this graph, it will use the referenced graph's start screen
     */
    fun startGraph(graphId: String) {
        if (startScreen != null) {
            throw IllegalStateException("Cannot set both startScreen and startGraph. Use either startScreen() or startGraph(), not both.")
        }
        this.startGraphId = graphId
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
        this.graphLayout = layoutComposable
    }

    // Convenience methods for common patterns
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
        // For graphs that use startGraph, we don't resolve the screen here
        // We'll resolve it during navigation
        val resolvedStartScreen = if (startScreen != null) {
            startScreen!!
        } else if (startGraphId != null) {
            // We'll create a placeholder screen and resolve it during navigation
            // The actual resolution happens in the route resolver
            createPlaceholderScreen()
        } else {
            throw IllegalArgumentException("Graph '$graphId' must have either startScreen or startGraph defined")
        }

        return MutableNavigationGraph(
            graphId = this.graphId,
            startScreen = resolvedStartScreen,
            screens = this.screens.toList(),
            nestedGraphs = this.nestedGraphs.toList(),
            _parentGraph = this.parentGraph,
            graphEnterBehavior = this.graphEnterBehavior,
            retainState = this.retainState,
            layout = this.graphLayout,
            startGraphId = this.startGraphId // Store the reference for later resolution
        )
    }

    /**
     * Create a placeholder screen for graphs that use startGraph
     * This will be resolved during navigation
     */
    private fun createPlaceholderScreen(): Screen {
        return object : Screen {
            override val route = "__placeholder_${graphId}"
            override val enterTransition = io.github.syrou.reaktiv.navigation.transition.NavTransition.None
            override val exitTransition = io.github.syrou.reaktiv.navigation.transition.NavTransition.None
            override val requiresAuth = false

            @Composable
            override fun Content(params: Map<String, Any>) {
                // This should never be rendered
            }
        }
    }
}