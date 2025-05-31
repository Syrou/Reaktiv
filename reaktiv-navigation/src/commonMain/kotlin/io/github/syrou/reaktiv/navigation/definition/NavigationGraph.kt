package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable

interface NavigationGraph : NavigationNode {
    val graphId: String
    val startDestination: StartDestination  // Changed from startScreen: Screen
    val screens: List<Screen>
    val nestedGraphs: List<NavigationGraph>
    val parentGraph: NavigationGraph?
    val layout: (@Composable (@Composable () -> Unit) -> Unit)?

    fun getAllScreens(): Map<String, Screen> = buildMap {
        screens.forEach { screen -> put(screen.route, screen) }
        nestedGraphs.forEach { nestedGraph ->
            putAll(nestedGraph.getAllScreens())
        }
    }

    fun findGraphContaining(route: String): NavigationGraph? {
        if (screens.any { it.route == route }) return this
        return nestedGraphs.firstNotNullOfOrNull { it.findGraphContaining(route) }
    }

    fun findNestedGraph(graphId: String): NavigationGraph? {
        return nestedGraphs.find { it.graphId == graphId }
            ?: nestedGraphs.firstNotNullOfOrNull { it.findNestedGraph(graphId) }
    }

    /**
     * Resolve the actual start screen after following graph references
     */
    fun resolveStartScreen(graphDefinitions: Map<String, NavigationGraph>): Screen? {
        return when (val dest = startDestination) {
            is StartDestination.DirectScreen -> dest.screen
            is StartDestination.GraphReference -> {
                val referencedGraph = graphDefinitions[dest.graphId]
                referencedGraph?.resolveStartScreen(graphDefinitions)
            }
        }
    }
}