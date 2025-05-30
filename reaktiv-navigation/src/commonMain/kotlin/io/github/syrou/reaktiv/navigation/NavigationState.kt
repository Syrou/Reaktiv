package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable

@Serializable
data class NavigationState(
    // Core navigation properties - simplified for graph-only navigation
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,
    val availableScreens: Map<String, Screen> = emptyMap(),
    val clearedBackStackWithNavigate: Boolean = false,
    val isLoading: Boolean = false,

    // Graph state management - always true now since we removed flat navigation
    val activeGraphId: String = "root",
    val graphStates: Map<String, GraphState> = emptyMap(),
    val graphDefinitions: Map<String, NavigationGraph> = emptyMap(),
    val globalBackStack: List<NavigationEntry> = emptyList(),
) : ModuleState {

    /**
     * Get the current active graph state with proper fallback handling
     */
    val activeGraphState: GraphState
        get() = graphStates[activeGraphId] ?: GraphState(
            graphId = activeGraphId,
            currentEntry = currentEntry,
            backStack = backStack,
            isActive = true
        )

    /**
     * Get all available screens including from graphs
     */
    val allAvailableScreens: Map<String, Screen>
        get() = buildMap {
            putAll(availableScreens)
            graphDefinitions.values.forEach { graph ->
                putAll(graph.getAllScreens())
            }
        }

    /**
     * Check if we can navigate back
     */
    val canGoBack: Boolean
        get() = activeGraphState.backStack.size > 1 || globalBackStack.size > 1

    /**
     * Find which graph contains a specific screen route
     */
    fun findGraphContaining(route: String): NavigationGraph? {
        return graphDefinitions.values.find { graph ->
            graph.getAllScreens().containsKey(route)
        }
    }

    /**
     * Get screen for a route, checking all available sources
     */
    fun getScreen(route: String): Screen? {
        return allAvailableScreens[route]
    }

    /**
     * Check if a route exists in the current navigation context
     */
    fun hasRoute(route: String): Boolean {
        return allAvailableScreens.containsKey(route)
    }

    /**
     * Get the current graph definition
     */
    val activeGraph: NavigationGraph?
        get() = graphDefinitions[activeGraphId]

    /**
     * Check if a graph has retained state
     */
    fun hasRetainedState(graphId: String): Boolean {
        return graphStates[graphId]?.retainedState?.isNotEmpty() == true
    }

    /**
     * Get retained state for a graph
     */
    fun getRetainedState(graphId: String): Map<String, Any> {
        return graphStates[graphId]?.retainedState ?: emptyMap()
    }

    /**
     * Get all inactive graphs that retain state
     */
    val retainedGraphs: List<String>
        get() = graphStates.filter { (graphId, graphState) ->
            !graphState.isActive &&
                    graphDefinitions[graphId]?.retainState == true &&
                    graphState.retainedState.isNotEmpty()
        }.keys.toList()
}