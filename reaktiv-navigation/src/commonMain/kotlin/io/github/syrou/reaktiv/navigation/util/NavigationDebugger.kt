package io.github.syrou.reaktiv.navigation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState

/**
 * Debug composable to help troubleshoot navigation issues
 */
@Composable
fun NavigationDebugger() {
    val navigationState by composeState<NavigationState>()
    
    LaunchedEffect(navigationState) {
        println("=== Navigation Debug Info ===")
        println("Is nested navigation: ${navigationState.isNestedNavigation}")
        println("Active graph ID: ${navigationState.activeGraphId}")
        println("Current screen: ${navigationState.currentEntry.screen.route}")
        println("Available graphs: ${navigationState.graphDefinitions.keys}")
        
        navigationState.graphDefinitions.forEach { (graphId, graph) ->
            println("Graph '$graphId':")
            println("  - Has layout: ${graph.layout != null}")
            println("  - Start screen: ${graph.startScreen.route}")
            println("  - Screens: ${graph.screens.map { it.route }}")
            println("  - Nested graphs: ${graph.nestedGraphs.map { it.graphId }}")
            println("  - Parent graph: ${graph.parentGraph?.graphId ?: "none"}")
        }
        
        println("Graph states:")
        navigationState.graphStates.forEach { (graphId, graphState) ->
            println("  '$graphId': active=${graphState.isActive}, current=${graphState.currentEntry.screen.route}")
        }
        println("=== End Debug Info ===")
    }
}