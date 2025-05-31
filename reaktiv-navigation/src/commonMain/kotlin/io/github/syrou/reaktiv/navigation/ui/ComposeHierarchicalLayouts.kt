package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen

/**
 * Compose layouts hierarchically from outer to inner
 */
@Composable
fun ComposeLayoutsHierarchically(
    layoutGraphs: List<NavigationGraph>,
    modifier: Modifier,
    navigationState: NavigationState,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit,
    currentIndex: Int = 0
) {
    if (currentIndex >= layoutGraphs.size) {
        // Base case: render the actual screen content
        SimpleNavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
        return
    }

    val currentGraph = layoutGraphs[currentIndex]
    val layout = currentGraph.layout!!

    // Apply current layout and recurse for nested layouts
    layout {
        ComposeLayoutsHierarchically(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent,
            currentIndex = currentIndex + 1
        )
    }
}