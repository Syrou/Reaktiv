package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.NavigationEntry


@Composable
fun RenderLayoutsHierarchicallyWithAnimation(
    layoutGraphs: List<NavigationGraph>,
    modifier: Modifier,
    navigationState: NavigationState,
    previousNavigationEntry: androidx.compose.runtime.MutableState<NavigationEntry?>,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit,
    currentIndex: Int = 0
) {
    if (currentIndex >= layoutGraphs.size) {
        NavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            previousNavigationEntry = previousNavigationEntry,
            screenContent = screenContent
        )
        return
    }

    val currentGraph = layoutGraphs[currentIndex]
    val layout = currentGraph.layout!!

    layout {
        RenderLayoutsHierarchicallyWithAnimation(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            previousNavigationEntry = previousNavigationEntry,
            screenContent = screenContent,
            currentIndex = currentIndex + 1
        )
    }
}