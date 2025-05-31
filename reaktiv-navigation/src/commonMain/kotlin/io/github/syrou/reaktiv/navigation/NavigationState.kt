package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.serialization.Serializable

@Serializable
data class NavigationState(
    // Core navigation properties - single source of truth
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>, // Single back stack - no more multiple overlapping stacks
    val availableScreens: Map<String, Screen> = emptyMap(),
    val isLoading: Boolean = false,

    // Graph definitions for nested navigation
    val graphDefinitions: Map<String, NavigationGraph> = emptyMap(),
) : ModuleState {

    /**
     * Get current active graph ID from current entry
     */
    val activeGraphId: String
        get() = currentEntry.graphId

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
        get() = backStack.size > 1

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
     * Get the current active graph definition
     */
    val activeGraph: NavigationGraph?
        get() = graphDefinitions[activeGraphId]

    /**
     * Get navigation history as readable string for debugging
     */
    val navigationHistory: String
        get() = backStack.map { "${it.graphId}/${it.screen.route}" }.joinToString(" -> ")
}