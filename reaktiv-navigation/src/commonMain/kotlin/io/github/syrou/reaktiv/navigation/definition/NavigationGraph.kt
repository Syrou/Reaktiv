package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.model.EntryDefinition
import io.github.syrou.reaktiv.navigation.model.InterceptDefinition

interface NavigationGraph : NavigationNode {
    override val route: String
    val startDestination: StartDestination?
    val navigatables: List<Navigatable>
    val nestedGraphs: List<NavigationGraph>
    val layout: (@Composable (@Composable () -> Unit) -> Unit)?
    val interceptDefinition: InterceptDefinition? get() = null
    val entryDefinition: EntryDefinition? get() = null

    @Deprecated("Use navigatables instead", ReplaceWith("navigatables"))
    val screens: List<Navigatable> get() = navigatables

    fun getAllNavigatables(): Map<String, Navigatable> = buildMap {
        navigatables.forEach { navigatable -> put(navigatable.route, navigatable) }
        nestedGraphs.forEach { nestedGraph ->
            putAll(nestedGraph.getAllNavigatables())
        }
    }

    @Deprecated("Use getAllNavigatables", ReplaceWith("getAllNavigatables()"))
    fun getAllScreens(): Map<String, Navigatable> = getAllNavigatables()

    fun findGraphContaining(route: String): NavigationGraph? {
        if (navigatables.any { it.route == route }) return this
        return nestedGraphs.firstNotNullOfOrNull { it.findGraphContaining(route) }
    }

    fun findNestedGraph(graphId: String): NavigationGraph? {
        return nestedGraphs.find { it.route == graphId }
            ?: nestedGraphs.firstNotNullOfOrNull { it.findNestedGraph(graphId) }
    }

    fun resolveStartScreen(graphDefinitions: Map<String, NavigationGraph>): Navigatable? {
        return when (val dest = startDestination) {
            is StartDestination.DirectScreen -> dest.screen
            is StartDestination.GraphReference -> {
                val referencedGraph = graphDefinitions[dest.graphId]
                referencedGraph?.resolveStartScreen(graphDefinitions)
            }
            null -> null
        }
    }
}
