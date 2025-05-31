package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable

class MutableNavigationGraph(
    override val graphId: String,
    override val startDestination: StartDestination,  // Changed from startScreen: Screen
    override val screens: List<Screen>,
    override val nestedGraphs: List<NavigationGraph>,
    private var _parentGraph: NavigationGraph?,
    override val layout: (@Composable (@Composable () -> Unit) -> Unit)?
) : NavigationGraph {

    override val parentGraph: NavigationGraph? get() = _parentGraph

    fun setParent(parent: NavigationGraph) {
        _parentGraph = parent
    }
}