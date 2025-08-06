package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.NavigationEntry


@Composable
fun RenderLayoutsHierarchically(
    layoutGraphs: List<NavigationGraph>,
    modifier: Modifier,
    navigationState: NavigationState,
    previousNavigationEntry: MutableState<NavigationEntry?>,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit,
    currentIndex: Int = 0
) {
    if (currentIndex >= layoutGraphs.size) {
        val entryToRender = if (navigationState.isCurrentModal) {
            navigationState.underlyingScreen ?: navigationState.currentEntry
        } else {
            navigationState.currentEntry
        }
        Box(modifier = Modifier.fillMaxSize()) {
            screenContent(entryToRender.navigatable, entryToRender.params)
            LocalOverlayLayerRender(
                entries = navigationState.localOverlayEntries,
                screenContent = screenContent
            )
        }
        return
    }

    val currentGraph = layoutGraphs[currentIndex]
    val layout = currentGraph.layout!!

    layout {
        RenderLayoutsHierarchically(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            previousNavigationEntry = previousNavigationEntry,
            screenContent = screenContent,
            currentIndex = currentIndex + 1
        )
    }
}