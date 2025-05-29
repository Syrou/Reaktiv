package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen


@Composable
fun RenderLayoutsHierarchically(
    layoutGraphs: List<NavigationGraph>,
    modifier: Modifier,
    navigationState: NavigationState,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit,
    currentIndex: Int = 0
) {
    if (currentIndex >= layoutGraphs.size) {
        NavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent
        )
        return
    }

    val currentGraph = layoutGraphs[currentIndex]
    val layout = currentGraph.layout!!
    layout {
        RenderLayoutsHierarchically(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            screenContent = screenContent,
            currentIndex = currentIndex + 1
        )
    }
}