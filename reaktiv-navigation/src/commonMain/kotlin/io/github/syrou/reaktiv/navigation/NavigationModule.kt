package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.dsl.Builder
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

class NavigationModule internal constructor(
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Existing parameters for backward compatibility
    private val rootScreen: Screen?,
    private val screens: List<Pair<KClass<out Screen>, NavigationNode>>,
    private val addRootScreenToBackStack: Boolean,
    // New parameters for graph support
    private val rootGraph: NavigationGraph? = null,
    private val isNestedNavigation: Boolean = false
) : Module<NavigationState, NavigationAction>, CustomTypeRegistrar {

    override val initialState: NavigationState by lazy {
        if (isNestedNavigation && rootGraph != null) {
            createNestedNavigationState()
        } else {
            createFlatNavigationState()
        }
    }

    private fun createFlatNavigationState(): NavigationState {
        val availableScreens = mutableMapOf<String, Screen>()

        screens.forEach { (_, node) ->
            when (node) {
                is Screen -> availableScreens[node.route] = node
                is ScreenGroup -> node.screens.forEach { screen ->
                    availableScreens[screen.route] = screen
                }
            }
        }

        requireNotNull(rootScreen) { "Root screen is required for flat navigation" }
        availableScreens[rootScreen.route] = rootScreen

        val rootEntry = NavigationEntry(rootScreen, emptyMap())

        return NavigationState(
            currentEntry = rootEntry,
            backStack = if (addRootScreenToBackStack) {
                listOf(rootEntry)
            } else {
                emptyList()
            },
            availableScreens = availableScreens,
            isNestedNavigation = false
        )
    }

    private fun createNestedNavigationState(): NavigationState {
        requireNotNull(rootGraph) { "Root graph is required for nested navigation" }

        val graphDefinitions = mutableMapOf<String, NavigationGraph>()
        val graphStates = mutableMapOf<String, GraphState>()
        val availableScreens = mutableMapOf<String, Screen>()

        // Recursively collect all graphs and their screens
        fun collectGraphs(graph: NavigationGraph) {
            graphDefinitions[graph.graphId] = graph

            // Collect all screens from this graph
            graph.screens.forEach { screen ->
                availableScreens[screen.route] = screen
            }

            // Initialize graph state
            val initialEntry = NavigationEntry(graph.startScreen, emptyMap())
            graphStates[graph.graphId] = GraphState(
                graphId = graph.graphId,
                currentEntry = initialEntry,
                backStack = listOf(initialEntry),
                isActive = graph.graphId == rootGraph.graphId
            )

            // Process nested graphs
            graph.nestedGraphs.forEach { nestedGraph ->
                collectGraphs(nestedGraph)
            }
        }

        collectGraphs(rootGraph)

        val rootEntry = NavigationEntry(rootGraph.startScreen, emptyMap())

        return NavigationState(
            currentEntry = rootEntry,
            backStack = listOf(rootEntry),
            availableScreens = availableScreens,
            activeGraphId = rootGraph.graphId,
            graphStates = graphStates,
            graphDefinitions = graphDefinitions,
            globalBackStack = listOf(rootEntry),
            isNestedNavigation = true
        )
    }

    @OptIn(InternalSerializationApi::class)
    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(Screen::class) {
            if (isNestedNavigation && rootGraph != null) {
                fun registerGraphScreens(graph: NavigationGraph) {
                    graph.screens.forEach { screen ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            subclass(
                                screen::class as KClass<Screen>,
                                screen::class.serializer() as KSerializer<Screen>
                            )
                        } catch (e: Exception) {
                            // Continue with other screens if one fails
                        }
                    }
                    graph.nestedGraphs.forEach { registerGraphScreens(it) }
                }
                registerGraphScreens(rootGraph)
            } else {
                // Register root screen if not already in screens list
                if (rootScreen != null && screens.none { it.second == rootScreen }) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        subclass(
                            rootScreen::class as KClass<Screen>,
                            rootScreen::class.serializer() as KSerializer<Screen>
                        )
                    } catch (e: Exception) {
                        // Continue if serializer registration fails
                    }
                }

                screens.forEach { (screenClass, node) ->
                    when (node) {
                        is Screen -> {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                subclass(screenClass as KClass<Screen>, screenClass.serializer())
                            } catch (e: Exception) {
                                // Continue with other screens
                            }
                        }
                        is ScreenGroup -> {
                            node.screens.forEach { screen ->
                                try {
                                    @Suppress("UNCHECKED_CAST")
                                    subclass(
                                        screen::class as KClass<Screen>,
                                        screen::class.serializer() as KSerializer<Screen>
                                    )
                                } catch (e: Exception) {
                                    // Continue with other screens
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Simplified reducer - focuses on atomic state updates
     */
    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.UpdateCurrentEntry -> state.copy(currentEntry = action.entry)
            is NavigationAction.UpdateBackStack -> state.copy(backStack = action.backStack)
            is NavigationAction.UpdateActiveGraph -> state.copy(activeGraphId = action.graphId)
            is NavigationAction.UpdateGlobalBackStack -> state.copy(globalBackStack = action.globalBackStack)
            is NavigationAction.SetClearedBackStackFlag -> state.copy(clearedBackStackWithNavigate = action.cleared)
            is NavigationAction.SetLoading -> state.copy(isLoading = action.isLoading)

            is NavigationAction.UpdateGraphState -> {
                state.copy(
                    graphStates = state.graphStates + (action.graphId to action.graphState)
                )
            }

            is NavigationAction.BatchUpdate -> {
                state.copy(
                    currentEntry = action.currentEntry ?: state.currentEntry,
                    backStack = action.backStack ?: state.backStack,
                    activeGraphId = action.activeGraphId ?: state.activeGraphId,
                    graphStates = action.graphStates ?: state.graphStates,
                    globalBackStack = action.globalBackStack ?: state.globalBackStack,
                    clearedBackStackWithNavigate = action.clearedBackStackWithNavigate
                        ?: state.clearedBackStackWithNavigate
                )
            }

            is NavigationAction.ClearCurrentScreenParams -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen == state.currentEntry.screen) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = state.currentEntry.copy(params = emptyMap())

                if (state.isNestedNavigation) {
                    val updatedGraphState = state.activeGraphState.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack
                    )
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack,
                        graphStates = state.graphStates + (state.activeGraphId to updatedGraphState)
                    )
                } else {
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack
                    )
                }
            }

            is NavigationAction.ClearCurrentScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen == state.currentEntry.screen) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = state.currentEntry.copy(params = state.currentEntry.params - action.key)

                if (state.isNestedNavigation) {
                    val updatedGraphState = state.activeGraphState.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack
                    )
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack,
                        graphStates = state.graphStates + (state.activeGraphId to updatedGraphState)
                    )
                } else {
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack
                    )
                }
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

                if (state.isNestedNavigation) {
                    val updatedGraphStates = state.graphStates.mapValues { (_, graphState) ->
                        val updatedGraphBackStack = graphState.backStack.map { entry ->
                            if (entry.screen.route == action.route) {
                                entry.copy(params = emptyMap())
                            } else {
                                entry
                            }
                        }
                        val updatedGraphCurrentEntry = if (graphState.currentEntry.screen.route == action.route) {
                            graphState.currentEntry.copy(params = emptyMap())
                        } else {
                            graphState.currentEntry
                        }
                        graphState.copy(
                            currentEntry = updatedGraphCurrentEntry,
                            backStack = updatedGraphBackStack
                        )
                    }
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack,
                        graphStates = updatedGraphStates
                    )
                } else {
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack
                    )
                }
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

                if (state.isNestedNavigation) {
                    val updatedGraphStates = state.graphStates.mapValues { (_, graphState) ->
                        val updatedGraphBackStack = graphState.backStack.map { entry ->
                            if (entry.screen.route == action.route) {
                                entry.copy(params = entry.params - action.key)
                            } else {
                                entry
                            }
                        }
                        val updatedGraphCurrentEntry = if (graphState.currentEntry.screen.route == action.route) {
                            graphState.currentEntry.copy(params = graphState.currentEntry.params - action.key)
                        } else {
                            graphState.currentEntry
                        }
                        graphState.copy(
                            currentEntry = updatedGraphCurrentEntry,
                            backStack = updatedGraphBackStack
                        )
                    }
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack,
                        graphStates = updatedGraphStates
                    )
                } else {
                    state.copy(
                        currentEntry = updatedCurrentEntry,
                        backStack = updatedBackStack
                    )
                }
            }

            // Complex navigation actions are handled by NavigationLogic
            else -> state
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
        NavigationLogic(
            coroutineScope = coroutineScope,
            availableScreens = initialState.allAvailableScreens,
            storeAccessor = storeAccessor,
            graphDefinitions = initialState.graphDefinitions,
            isNestedNavigation = isNestedNavigation
        )
    }

    companion object {
        inline fun create(block: Builder.() -> Unit): NavigationModule {
            return Builder().apply(block).build()
        }

        inline fun createWithGraphs(block: GraphBasedBuilder.() -> Unit): NavigationModule {
            return GraphBasedBuilder().apply(block).build()
        }
    }
}

fun createNavigationModule(block: Builder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}

fun createGraphNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.createWithGraphs {
        block.invoke(this)
    }
}