package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.navigation.definition.MutableNavigationGraph
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.GraphBasedBuilder
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Simplified NavigationModule with single back stack and clear state management
 */
class NavigationModule internal constructor(
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val rootGraph: NavigationGraph
) : Module<NavigationState, NavigationAction>, CustomTypeRegistrar {

    override val initialState: NavigationState by lazy {
        createInitialNavigationState()
    }

    private fun createInitialNavigationState(): NavigationState {
        val graphDefinitions = mutableMapOf<String, NavigationGraph>()
        val availableScreens = mutableMapOf<String, Screen>()

        // Recursively collect all graphs and their screens
        fun collectGraphs(graph: NavigationGraph) {
            graphDefinitions[graph.graphId] = graph

            // Collect all screens from this graph
            graph.screens.forEach { screen ->
                // Only add non-placeholder screens
                if (!screen.route.startsWith("__placeholder_")) {
                    availableScreens[screen.route] = screen
                }
            }

            // Process nested graphs
            graph.nestedGraphs.forEach { nestedGraph ->
                collectGraphs(nestedGraph)
            }
        }

        collectGraphs(rootGraph)

        // Resolve the actual start screen for the root graph
        val actualStartScreen = resolveStartScreen(rootGraph, graphDefinitions)
        val actualGraphId = getActualGraphIdForScreen(actualStartScreen, graphDefinitions)

        println("DEBUG: 🚀 Initial navigation - Root graph: ${rootGraph.graphId}")
        println("DEBUG: 🎯 Resolved start screen: ${actualStartScreen.route} in graph: $actualGraphId")

        // Create initial entry with resolved screen and correct graph ID
        val initialEntry = NavigationEntry(
            screen = actualStartScreen,
            params = emptyMap(),
            graphId = actualGraphId
        )

        return NavigationState(
            currentEntry = initialEntry,
            backStack = listOf(initialEntry),
            availableScreens = availableScreens,
            isLoading = false,
            graphDefinitions = graphDefinitions
        )
    }

    /**
     * Resolve the actual start screen for a graph, handling startGraph references
     */
    private fun resolveStartScreen(graph: NavigationGraph, graphDefinitions: Map<String, NavigationGraph>): Screen {
        if (graph is MutableNavigationGraph && graph.usesStartGraph()) {
            val startGraphId = graph.startGraphId
            if (startGraphId != null) {
                println("DEBUG: 🔗 Resolving startGraph reference from '${graph.graphId}' to '$startGraphId'")

                val referencedGraph = graphDefinitions[startGraphId] ?: graph.findNestedGraph(startGraphId)
                if (referencedGraph != null) {
                    // Recursively resolve the referenced graph's start screen
                    return resolveStartScreen(referencedGraph, graphDefinitions)
                } else {
                    println("DEBUG: ⚠️ Warning: Referenced graph '$startGraphId' not found, using placeholder")
                }
            }
        }

        return graph.startScreen
    }

    /**
     * Get the actual graph ID where a screen belongs
     */
    private fun getActualGraphIdForScreen(screen: Screen, graphDefinitions: Map<String, NavigationGraph>): String {
        // Find which graph actually contains this screen
        for ((graphId, graph) in graphDefinitions) {
            if (graph.getAllScreens().containsKey(screen.route)) {
                return graphId
            }
        }

        // Default to root if not found
        return "root"
    }

    @OptIn(InternalSerializationApi::class)
    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(Screen::class) {
            fun registerGraphScreens(graph: NavigationGraph) {
                graph.screens.forEach { screen ->
                    try {
                        // Skip placeholder screens
                        if (!screen.route.startsWith("__placeholder_")) {
                            @Suppress("UNCHECKED_CAST")
                            subclass(
                                screen::class as KClass<Screen>,
                                screen::class.serializer() as KSerializer<Screen>
                            )
                        }
                    } catch (e: Exception) {
                        // Continue with other screens if one fails
                    }
                }
                graph.nestedGraphs.forEach { registerGraphScreens(it) }
            }
            registerGraphScreens(rootGraph)
        }
    }

    /**
     * Simplified reducer - focuses on core navigation state updates
     */
    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.BatchUpdate -> {
                state.copy(
                    currentEntry = action.currentEntry ?: state.currentEntry,
                    backStack = action.backStack ?: state.backStack,
                    isLoading = action.isLoading ?: state.isLoading
                )
            }

            is NavigationAction.SetLoading -> {
                state.copy(isLoading = action.isLoading)
            }

            is NavigationAction.ClearCurrentScreenParams -> {
                val updatedEntry = state.currentEntry.copy(params = emptyMap())
                val updatedBackStack = state.backStack.dropLast(1) + updatedEntry
                state.copy(
                    currentEntry = updatedEntry,
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.ClearCurrentScreenParam -> {
                val updatedEntry = state.currentEntry.copy(
                    params = state.currentEntry.params - action.key
                )
                val updatedBackStack = state.backStack.dropLast(1) + updatedEntry
                state.copy(
                    currentEntry = updatedEntry,
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.ClearScreenParams -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen.route == action.route) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.screen.route == action.route) {
                    state.currentEntry.copy(params = emptyMap())
                } else {
                    state.currentEntry
                }
                state.copy(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.ClearScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen.route == action.route) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.screen.route == action.route) {
                    state.currentEntry.copy(params = state.currentEntry.params - action.key)
                } else {
                    state.currentEntry
                }
                state.copy(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack
                )
            }

            // Legacy actions - maintain compatibility
            is NavigationAction.UpdateCurrentEntry -> {
                val updatedBackStack = state.backStack.dropLast(1) + action.entry
                state.copy(
                    currentEntry = action.entry,
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.UpdateBackStack -> {
                val newCurrentEntry = action.backStack.lastOrNull() ?: state.currentEntry
                state.copy(
                    currentEntry = newCurrentEntry,
                    backStack = action.backStack
                )
            }

            else -> state // Ignore other legacy actions
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
        NavigationLogic(storeAccessor = storeAccessor)
    }

    companion object {
        inline fun create(block: GraphBasedBuilder.() -> Unit): NavigationModule {
            return GraphBasedBuilder().apply(block).build()
        }
    }
}

fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}