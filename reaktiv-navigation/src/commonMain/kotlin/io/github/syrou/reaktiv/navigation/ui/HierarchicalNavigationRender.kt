package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.util.GraphHierarchyResolver

/**
 * Renders navigation with proper hierarchical layout composition
 */
@Composable
fun HierarchicalNavigationRender(
    modifier: Modifier,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit
) {
    val navigationState by composeState<NavigationState>()
    
    println("DEBUG: HierarchicalNavigationRender called")
    println("DEBUG: Navigation state - isNested: ${navigationState.isNestedNavigation}, activeGraph: ${navigationState.activeGraphId}")
    
    val hierarchy = GraphHierarchyResolver.resolveHierarchy(navigationState)
    
    if (hierarchy != null) {
        println("DEBUG: Hierarchy resolved with ${hierarchy.path.size} graphs")
        val layoutGraphs = hierarchy.path.filter { it.layout != null }
        println("DEBUG: Found ${layoutGraphs.size} graphs with layouts: ${layoutGraphs.map { it.graphId }}")
        
        if (layoutGraphs.isNotEmpty()) {
            // Compose layouts hierarchically from root to leaf
            ComposeHierarchicalLayouts(
                layoutGraphs = layoutGraphs,
                modifier = modifier,
                navigationState = navigationState,
                screenContent = screenContent
            )
        } else {
            println("DEBUG: No layout graphs found, using default content")
            // No custom layouts, render content directly
            GraphNavigationContent(
                modifier = modifier,
                navigationState = navigationState,
                screenContent = screenContent
            )
        }
    } else {
        println("DEBUG: Hierarchy is null, using default content")
        // Fallback to default content
        GraphNavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
    }
}