package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GraphBasedBuilder {
    private var rootGraph: NavigationGraph? = null
    private val guidedFlowDefinitions = mutableMapOf<String, GuidedFlowDefinition>()
    private var screenRetentionDuration: Duration = 10.seconds

    fun rootGraph(block: NavigationGraphBuilder.() -> Unit) {
        val builder = NavigationGraphBuilder("root")
        builder.apply(block)
        rootGraph = builder.build()
    }

    fun screenRetentionDuration(duration: Duration) {
        screenRetentionDuration = duration
    }

    fun guidedFlow(route: String, block: GuidedFlowBuilder.() -> Unit) {
        val definition = io.github.syrou.reaktiv.navigation.dsl.guidedFlow(route, block)
        guidedFlowDefinitions[definition.guidedFlow.route] = definition
    }

    fun build(): NavigationModule {
        requireNotNull(rootGraph) { "Root graph must be defined" }
        return NavigationModule(
            rootGraph = rootGraph!!,
            originalGuidedFlowDefinitions = guidedFlowDefinitions.toMap(),
            screenRetentionDuration = screenRetentionDuration
        )
    }
}