package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable

class MutableNavigationGraph(
    override val graphId: String,
    override val startScreen: Screen,
    override val screens: List<Screen>,
    override val nestedGraphs: List<NavigationGraph>,
    private var _parentGraph: NavigationGraph?,
    override val graphEnterBehavior: GraphEnterBehavior,
    override val retainState: Boolean,
    override val layout: (@Composable (@Composable () -> Unit) -> Unit)?,
    val startGraphId: String? = null // NEW: Store reference to startGraph
) : NavigationGraph {

    override val parentGraph: NavigationGraph? get() = _parentGraph

    fun setParent(parent: NavigationGraph) {
        _parentGraph = parent
    }

    /**
     * Check if this graph uses startGraph instead of startScreen
     */
    fun usesStartGraph(): Boolean = startGraphId != null
}