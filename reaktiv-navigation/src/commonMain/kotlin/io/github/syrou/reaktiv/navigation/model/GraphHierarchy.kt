package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.NavigationGraph

data class GraphHierarchy(
    val path: List<NavigationGraph>,
    val activeGraph: NavigationGraph
) {
    /**
     * Get all graphs in the hierarchy that have custom layouts
     */
    val layoutGraphs: List<NavigationGraph>
        get() = path.filter { it.layout != null }
    
    /**
     * Check if any graph in the hierarchy has a layout
     */
    val hasLayouts: Boolean
        get() = layoutGraphs.isNotEmpty()
}