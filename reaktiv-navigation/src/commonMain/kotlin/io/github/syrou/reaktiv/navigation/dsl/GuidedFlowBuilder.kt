package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import kotlin.reflect.KClass

/**
 * Builder for creating GuidedFlow definitions with a clean DSL
 */
class GuidedFlowBuilder(private val guidedFlow: GuidedFlow) {
    private val steps = mutableListOf<GuidedFlowStep>()
    private var onCompleteBlock: (suspend NavigationBuilder.() -> Unit)? = null
    private var pendingStepBuilder: GuidedFlowStepBuilder? = null

    /**
     * Add a step using a route string
     * Example: step("user/profile?tab=settings")
     */
    fun step(route: String): GuidedFlowStepBuilder {
        finalizePendingStep()
        val stepBuilder = GuidedFlowStepBuilder { params ->
            GuidedFlowStep.Route(route, params)
        }
        pendingStepBuilder = stepBuilder
        return stepBuilder
    }

    /**
     * Add a step using a typed screen class
     * Example: step<UserProfileScreen>()
     */
    fun <T : Screen> step(screenClass: KClass<T>): GuidedFlowStepBuilder {
        finalizePendingStep()
        val stepBuilder = GuidedFlowStepBuilder { params ->
            GuidedFlowStep.TypedScreen(screenClass.qualifiedName!!, params)
        }
        pendingStepBuilder = stepBuilder
        return stepBuilder
    }

    /**
     * Define what happens when the guided flow completes
     */
    fun onComplete(block: suspend NavigationBuilder.() -> Unit) {
        finalizePendingStep()
        onCompleteBlock = block
    }

    /**
     * Finalize any pending step and add it to the steps list
     */
    internal fun finalizePendingStep() {
        pendingStepBuilder?.let { builder ->
            val step = builder.build()
            steps.add(step)
            pendingStepBuilder = null
        }
    }

    /**
     * Build the final GuidedFlowDefinition
     */
    internal fun build(): GuidedFlowDefinition {
        finalizePendingStep() // Ensure the last step is finalized
        return GuidedFlowDefinition(
            guidedFlow = guidedFlow,
            steps = steps.toList(),
            onComplete = onCompleteBlock
        )
    }
}

/**
 * Builder for individual guided flow steps that allows adding parameters
 */
class GuidedFlowStepBuilder(
    private val stepFactory: (StringAnyMap) -> GuidedFlowStep
) {
    private val params = mutableMapOf<String, Any>()

    /**
     * Add a parameter to this step
     */
    fun param(key: String, value: Any): GuidedFlowStepBuilder {
        params[key] = value
        return this
    }

    /**
     * Add multiple parameters to this step
     */
    fun params(vararg pairs: Pair<String, Any>): GuidedFlowStepBuilder {
        params.putAll(pairs)
        return this
    }

    /**
     * Add multiple parameters from a map
     */
    fun params(paramMap: Map<String, Any>): GuidedFlowStepBuilder {
        params.putAll(paramMap)
        return this
    }

    /**
     * Build the step with current parameters
     */
    internal fun build(): GuidedFlowStep {
        return stepFactory(params.toMap())
    }
}

/**
 * DSL function to create a GuidedFlow definition
 */
fun guidedFlow(route: String, block: GuidedFlowBuilder.() -> Unit): GuidedFlowDefinition {
    val builder = GuidedFlowBuilder(GuidedFlow(route))
    builder.block()
    return builder.build()
}

/**
 * DSL function to create a GuidedFlow definition with a GuidedFlow object
 */
fun guidedFlow(guidedFlow: GuidedFlow, block: GuidedFlowBuilder.() -> Unit): GuidedFlowDefinition {
    val builder = GuidedFlowBuilder(guidedFlow)
    builder.block()
    return builder.build()
}

/**
 * Inline helper function for type-safe screen step creation
 */
inline fun <reified T : Screen> GuidedFlowBuilder.step(): GuidedFlowStepBuilder {
    return step(T::class)
}