@file:OptIn(ExperimentalTime::class)

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.ui.NavigationContent
import io.github.syrou.reaktiv.navigation.ui.RenderLayoutsHierarchicallyWithAnimation
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger
import kotlin.time.ExperimentalTime

@Composable
fun NavigationRender(
    modifier: Modifier,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val navigationState by composeState<NavigationState>()

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState)
    }

    val previousNavigationEntry = remember { mutableStateOf<NavigationEntry?>(null) }

    // Render the navigation content
    val layoutGraphs = if (navigationState.isCurrentModal) {
        findLayoutGraphsInHierarchy(navigationState.underlyingScreen?.graphId ?: "root", navigationState)
    } else {
        findLayoutGraphsInHierarchy(navigationState.currentEntry.graphId, navigationState)
    }
    if (layoutGraphs.isNotEmpty()) {
        RenderLayoutsHierarchicallyWithAnimation(
            layoutGraphs = layoutGraphs,
            modifier = modifier,
            navigationState = navigationState,
            previousNavigationEntry = previousNavigationEntry,
            screenContent = screenContent
        )
    } else {
        NavigationContent(
            modifier = modifier,
            navigationState = navigationState,
            previousNavigationEntry = previousNavigationEntry,
            screenContent = screenContent
        )
    }
}

fun findLayoutGraphsInHierarchy(
    currentGraphId: String,
    navigationState: NavigationState
): List<NavigationGraph> {
    val hierarchyPath = buildGraphHierarchyPath(currentGraphId, navigationState.graphDefinitions)
    return hierarchyPath.filter { it.layout != null }
}

fun buildGraphHierarchyPath(
    targetGraphId: String,
    graphDefinitions: Map<String, NavigationGraph>
): List<NavigationGraph> {
    val targetGraph = graphDefinitions[targetGraphId] ?: return emptyList()

    val path = mutableListOf<NavigationGraph>()
    var currentGraph: NavigationGraph? = targetGraph
    while (currentGraph != null) {
        path.add(0, currentGraph)
        currentGraph = findParentGraph(currentGraph, graphDefinitions)
    }

    return path
}

fun findParentGraph(
    targetGraph: NavigationGraph,
    graphDefinitions: Map<String, NavigationGraph>
): NavigationGraph? {
    return graphDefinitions.values.find { graph ->
        graph.nestedGraphs.any { it.route == targetGraph.route }
    }
}