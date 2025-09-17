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

    fun modifyGuidedFlow(route: String, block: GuidedFlowBuilder.() -> Unit) {
        val existingDefinition = guidedFlowDefinitions[route]
        if (existingDefinition != null) {
            // Create a new builder with existing data and apply modifications
            val builder = GuidedFlowBuilder(existingDefinition.guidedFlow)
            // Pre-populate with existing steps
            existingDefinition.steps.forEach { step ->
                when (step) {
                    is io.github.syrou.reaktiv.navigation.model.GuidedFlowStep.Route -> 
                        builder.step(step.route).params(step.params)
                    is io.github.syrou.reaktiv.navigation.model.GuidedFlowStep.TypedScreen -> 
                        builder.step(step.screenClass).params(step.params)
                }
            }
            // Set existing completion handler
            existingDefinition.onComplete?.let { builder.onComplete(it) }
            
            // Apply modifications
            builder.apply(block)
            val modifiedDefinition = builder.build()
            guidedFlowDefinitions[route] = modifiedDefinition
        } else {
            throw IllegalArgumentException("GuidedFlow with route '$route' not found")
        }
    }

    fun build(): NavigationModule {
        requireNotNull(rootGraph) { "Root graph must be defined" }
        return NavigationModule(
            rootGraph = rootGraph!!,
            originalGuidedFlowDefinitions = guidedFlowDefinitions.toMap(),
            screenRetentionDuration = screenRetentionDuration
        )
    }

    
    fun getRootGraph(): NavigationGraph? = rootGraph
}