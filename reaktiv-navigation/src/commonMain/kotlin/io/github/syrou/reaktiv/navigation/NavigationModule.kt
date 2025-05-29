package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.StartDestination
import io.github.syrou.reaktiv.navigation.dsl.GraphBasedBuilder
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.RouteResolution
import io.github.syrou.reaktiv.navigation.util.RouteResolver
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
    private val rootGraph: NavigationGraph
) : Module<NavigationState, NavigationAction>, CustomTypeRegistrar {
    private val precomputedData: PrecomputedNavigationData by lazy {
        PrecomputedNavigationData.create(rootGraph)
    }

    override val initialState: NavigationState by lazy {
        createInitialState()
    }

    private fun createInitialState(): NavigationState {
        ReaktivDebug.nav("🚀 Creating  initial navigation state")

        // Resolve the start destination using precomputed resolver
        val resolution = when (val dest = rootGraph.startDestination) {
            is StartDestination.DirectScreen -> {
                RouteResolution(
                    targetNavigatable = dest.screen,
                    targetGraphId = rootGraph.route,
                    extractedParams = emptyMap(),
                    navigationGraphId = rootGraph.route
                )
            }
            is StartDestination.GraphReference -> {
                precomputedData.routeResolver.resolve(dest.graphId)
                    ?: throw IllegalStateException("Could not resolve root graph reference to '${dest.graphId}'")
            }
        }

        val effectiveGraphId = resolution.getEffectiveGraphId()

        // Create initial entry with precomputed data
        val initialEntry = NavigationEntry(
            navigatable = resolution.targetNavigatable,
            params = emptyMap(),
            graphId = effectiveGraphId
        )

        return NavigationState(
            currentEntry = initialEntry,
            backStack = listOf(initialEntry),
            precomputedData = precomputedData
        )
    }

    @OptIn(InternalSerializationApi::class)
    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(Navigatable::class) {
            fun registerGraphNavigatables(graph: NavigationGraph) {
                graph.navigatables.forEach { navigatable ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        subclass(
                            navigatable::class as KClass<Navigatable>,
                            navigatable::class.serializer() as KSerializer<Navigatable>
                        )
                    } catch (e: Exception) {
                        ReaktivDebug.warn("Could not register serializer for navigatable ${navigatable.route}: ${e.message}")
                    }
                }
                graph.nestedGraphs.forEach { registerGraphNavigatables(it) }
            }
            registerGraphNavigatables(rootGraph)
        }
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.BatchUpdate -> {
                state.copy(
                    currentEntry = action.currentEntry ?: state.currentEntry,
                    backStack = action.backStack ?: state.backStack,
                )
            }

            is NavigationAction.Back -> {
                state.copy(
                    currentEntry = action.currentEntry ?: state.currentEntry,
                    backStack = action.backStack ?: state.backStack,
                )
            }

            is NavigationAction.ClearBackstack -> {
                state.copy(
                    currentEntry = action.currentEntry ?: state.currentEntry,
                    backStack = action.backStack ?: state.backStack,
                )
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
                    if (entry.navigatable.route == action.route) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.navigatable.route == action.route) {
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
                    if (entry.navigatable.route == action.route) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.navigatable.route == action.route) {
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

            else -> state
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
        NavigationLogic(storeAccessor = storeAccessor, precomputedData = precomputedData)
    }

    companion object {
        inline fun create(block: GraphBasedBuilder.() -> Unit): NavigationModule {
            return GraphBasedBuilder().apply(block).build()
        }
    }
}

data class PrecomputedNavigationData(
    val routeResolver: RouteResolver,
    val availableNavigatables: Map<String, Navigatable>,
    val graphDefinitions: Map<String, NavigationGraph>,
    val allNavigatables: Map<String, Navigatable>,
    val graphHierarchies: Map<String, List<String>>,
    val navigatableToGraph: Map<Navigatable, String>,
    val routeToNavigatable: Map<String, Navigatable>,
    val navigatableToFullPath: Map<Navigatable, String>,
) {
    companion object {
        fun create(rootGraph: NavigationGraph): PrecomputedNavigationData {
            val graphDefinitions = mutableMapOf<String, NavigationGraph>()
            val availableNavigatables = mutableMapOf<String, Navigatable>()
            val allNavigatables = mutableMapOf<String, Navigatable>()
            val navigatableToGraph = mutableMapOf<Navigatable, String>()
            val routeToNavigatable = mutableMapOf<String, Navigatable>()
            val navigatableToFullPath = mutableMapOf<Navigatable, String>() // CRITICAL: Build the missing lookup table

            // Build parent graph lookup first for hierarchy calculations
            val parentGraphLookup = mutableMapOf<String, String>()

            // Collect all graphs and navigatables in one pass
            fun collectGraphs(graph: NavigationGraph) {
                graphDefinitions[graph.route] = graph

                // Build parent lookup for nested graphs
                graph.nestedGraphs.forEach { nestedGraph ->
                    parentGraphLookup[nestedGraph.route] = graph.route
                }

                // Root graph navigatables are globally accessible
                if (graph.route == "root") {
                    graph.navigatables.forEach { navigatable ->
                        availableNavigatables[navigatable.route] = navigatable
                        routeToNavigatable[navigatable.route] = navigatable
                        // Root level navigatables have simple paths
                        navigatableToFullPath[navigatable] = navigatable.route
                    }
                }

                // All navigatables go into the comprehensive map
                graph.navigatables.forEach { navigatable ->
                    allNavigatables[navigatable.route] = navigatable
                    navigatableToGraph[navigatable] = graph.route
                    routeToNavigatable[navigatable.route] = navigatable

                    // Build full path for this navigatable
                    if (graph.route != "root") {
                        val graphPath = buildGraphPathToRoot(graph.route, parentGraphLookup)
                        val fullPath = if (graphPath.isEmpty()) {
                            navigatable.route
                        } else {
                            "$graphPath/${navigatable.route}"
                        }
                        navigatableToFullPath[navigatable] = fullPath
                    }
                    // Root navigatables already handled above
                }

                // Process nested graphs
                graph.nestedGraphs.forEach { collectGraphs(it) }
            }

            collectGraphs(rootGraph)

            // Create  route resolver with all precomputed data
            val routeResolver = RouteResolver.create(graphDefinitions)

            // Build graph hierarchies
            val graphHierarchies = mutableMapOf<String, List<String>>()
            for (graphId in graphDefinitions.keys) {
                val hierarchy = mutableListOf<String>()
                var currentGraphId: String? = graphId

                while (currentGraphId != null && currentGraphId != "root") {
                    hierarchy.add(0, currentGraphId)
                    currentGraphId = parentGraphLookup[currentGraphId]
                }

                graphHierarchies[graphId] = hierarchy
            }

            ReaktivDebug.nav("🎯 Precomputed ${allNavigatables.size} navigatables across ${graphDefinitions.size} graphs")
            ReaktivDebug.nav("📍 Built ${navigatableToFullPath.size} navigatable-to-path mappings for NavigationTarget resolution")

            return PrecomputedNavigationData(
                routeResolver = routeResolver,
                availableNavigatables = availableNavigatables,
                graphDefinitions = graphDefinitions,
                allNavigatables = allNavigatables,
                graphHierarchies = graphHierarchies,
                navigatableToGraph = navigatableToGraph,
                routeToNavigatable = routeToNavigatable,
                navigatableToFullPath = navigatableToFullPath // Now properly populated!
            )
        }

        
        private fun buildGraphPathToRoot(graphId: String, parentLookup: Map<String, String>): String {
            if (graphId == "root") return ""

            val pathSegments = mutableListOf<String>()
            var currentGraphId: String? = graphId

            while (currentGraphId != null && currentGraphId != "root") {
                pathSegments.add(0, currentGraphId)
                currentGraphId = parentLookup[currentGraphId]
            }

            return pathSegments.joinToString("/")
        }
    }
}

fun createNavigationModule(block: GraphBasedBuilder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}