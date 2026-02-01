package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GraphBasedBuilder {
    private var rootGraph: NavigationGraph? = null
    private var notFoundScreen: Screen? = null
    private val guidedFlowDefinitions = mutableMapOf<String, GuidedFlowDefinition>()
    private var screenRetentionDuration: Duration = 10.seconds

    fun rootGraph(block: NavigationGraphBuilder.() -> Unit) {
        val builder = NavigationGraphBuilder("root")
        builder.apply(block)
        rootGraph = builder.build()
    }

    /**
     * Sets the screen to display when a route is not found or when navigating
     * to a graph that has no startScreen/startGraph defined.
     *
     * This screen acts as a 404 fallback for the navigation system.
     *
     * @param screen The screen to display for not found routes
     */
    fun notFoundScreen(screen: Screen) {
        this.notFoundScreen = screen
    }

    fun screenRetentionDuration(duration: Duration) {
        screenRetentionDuration = duration
    }

    /**
     * DSL function to create a GuidedFlow definition
     *
     * @deprecated Guided flows are deprecated. Use regular navigation with separate state modules for multi-step flows.
     * This provides better separation of concerns and more flexibility.
     */
    @Deprecated(
        message = "Guided flows are deprecated. Use regular navigation with separate state modules for multi-step flows.",
        level = DeprecationLevel.WARNING
    )
    fun guidedFlow(route: String, block: GuidedFlowBuilder.() -> Unit) {
        val definition = io.github.syrou.reaktiv.navigation.dsl.guidedFlow(route, block)
        guidedFlowDefinitions[definition.guidedFlow.route] = definition
    }

    fun build(): NavigationModule {
        requireNotNull(rootGraph) { "Root graph must be defined" }
        return NavigationModule(
            rootGraph = rootGraph!!,
            notFoundScreen = notFoundScreen,
            originalGuidedFlowDefinitions = guidedFlowDefinitions.toMap(),
            screenRetentionDuration = screenRetentionDuration
        )
    }
}