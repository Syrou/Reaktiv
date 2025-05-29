package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable

interface NavigationGraph : NavigationNode {
    val graphId: String
    val startScreen: Screen
    val screens: List<Screen>
    val nestedGraphs: List<NavigationGraph>
    val parentGraph: NavigationGraph?
    val graphEnterBehavior: GraphEnterBehavior
    val retainState: Boolean

    // New layout property for custom graph rendering
    val layout: (@Composable (@Composable () -> Unit) -> Unit)?
        get() = null

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
}