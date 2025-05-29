package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable

@Serializable
data class NavigationState(
    // Core navigation properties - maintain exact compatibility
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,
    val availableScreens: Map<String, Screen> = emptyMap(),
    val clearedBackStackWithNavigate: Boolean = false,
    val isLoading: Boolean = false,

    // Unified graph state management
    val activeGraphId: String = "root",
    val graphStates: Map<String, GraphState> = emptyMap(),
    val graphDefinitions: Map<String, NavigationGraph> = emptyMap(),
    val globalBackStack: List<NavigationEntry> = emptyList(),
    val isNestedNavigation: Boolean = false
) : ModuleState {

    /**
     * Get the current active graph state with proper fallback handling
     */
    val activeGraphState: GraphState
        get() = if (isNestedNavigation) {
            graphStates[activeGraphId] ?: GraphState(
                graphId = activeGraphId,
                currentEntry = currentEntry,
                backStack = backStack,
                isActive = true
            )
        } else {
            GraphState(
                graphId = "root",
                currentEntry = currentEntry,
                backStack = backStack,
                isActive = true
            )
        }

    /**
     * Get all available screens including from graphs
     */
    val allAvailableScreens: Map<String, Screen>
        get() = if (isNestedNavigation) {
            buildMap {
                putAll(availableScreens)
                graphDefinitions.values.forEach { graph ->
                    putAll(graph.getAllScreens())
                }
            }
        } else {
            availableScreens
        }

    /**
     * Check if we can navigate back
     */
    val canGoBack: Boolean
        get() = if (isNestedNavigation) {
            activeGraphState.backStack.size > 1 || globalBackStack.size > 1
        } else {
            backStack.size > 1
        }

    /**
     * Find which graph contains a specific screen route
     */
    fun findGraphContaining(route: String): NavigationGraph? {
        if (!isNestedNavigation) return null
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
}