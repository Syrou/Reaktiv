package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.ClearModificationBehavior
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import kotlin.reflect.KClass

/**
 * Builder for creating GuidedFlow definitions with a clean DSL.
 * 
 * Provides a structured way to define multi-step guided flows with 
 * type-safe screen references and completion handlers.
 * 
 * Example usage:
 * ```kotlin
 * guidedFlow("user-onboarding") {
 *     step<WelcomeScreen>()
 *     step<ProfileSetupScreen> { 
 *         param("userId", "123") 
 *     }
 *     step("settings/preferences")
 *     
 *     onComplete { storeAccessor ->
 *         storeAccessor.navigation {
 *             clearBackStack()
 *             navigateTo("home")
 *         }
 *     }
 * }
 * ```
 * 
 * @param guidedFlow The guided flow definition to build
 */
class GuidedFlowBuilder(private val guidedFlow: GuidedFlow) {
    private val steps = mutableListOf<GuidedFlowStep>()
    private var onCompleteBlock: (suspend NavigationBuilder.(StoreAccessor) -> Unit)? = null
    private var clearBehavior: ClearModificationBehavior = ClearModificationBehavior.CLEAR_ALL
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
     * Example: step(UserProfileScreen::class) or step<UserProfileScreen>()
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
     * Add a step using reified type parameter
     * Example: step<UserProfileScreen>()
     */
    inline fun <reified T : Screen> step(): GuidedFlowStepBuilder {
        return step(T::class)
    }

    /**
     * Define what happens when the guided flow completes
     * Use navigateTo, clearBackStack, and other navigation operations directly.
     * The storeAccessor parameter provides access to other module states when needed.
     */
    fun onComplete(block: suspend NavigationBuilder.(StoreAccessor) -> Unit) {
        finalizePendingStep()
        onCompleteBlock = block
    }

    /**
     * Clear the completion handler
     */
    fun clearOnComplete() {
        finalizePendingStep()
        onCompleteBlock = null
    }

    /**
     * Configure how modifications should be cleared when this flow completes.
     * 
     * @param behavior The clearing behavior to use:
     *   - CLEAR_ALL: Clear all flow modifications (default)
     *   - CLEAR_SPECIFIC: Clear only this flow's modifications
     *   - CLEAR_NONE: Don't clear any modifications
     */
    fun clearModificationsOnComplete(behavior: ClearModificationBehavior) {
        finalizePendingStep()
        clearBehavior = behavior
    }

    /**
     * Insert a step at a specific index
     */
    fun insertStep(index: Int, route: String): GuidedFlowStepBuilder {
        finalizePendingStep()
        require(index >= 0 && index <= steps.size) { "Index $index is out of bounds" }
        
        val stepBuilder = GuidedFlowStepBuilder { params ->
            GuidedFlowStep.Route(route, params)
        }
        
        // Create the step and insert it
        val step = stepBuilder.build()
        steps.add(index, step)
        
        // Return a new builder for chaining (though the step is already added)
        return GuidedFlowStepBuilder { _ -> step }
    }

    /**
     * Remove a step by index
     */
    fun removeStep(index: Int) {
        finalizePendingStep()
        require(index >= 0 && index < steps.size) { "Index $index is out of bounds" }
        steps.removeAt(index)
    }

    /**
     * Remove all steps matching a route
     */
    fun removeStepsFor(route: String) {
        finalizePendingStep()
        steps.removeAll { step ->
            when (step) {
                is GuidedFlowStep.Route -> step.route == route
                is GuidedFlowStep.TypedScreen -> step.screenClass == route
            }
        }
    }

    /**
     * Replace a step at a specific index
     */
    fun replaceStep(index: Int, route: String): GuidedFlowStepBuilder {
        finalizePendingStep()
        require(index >= 0 && index < steps.size) { "Index $index is out of bounds" }
        
        val stepBuilder = GuidedFlowStepBuilder { params ->
            GuidedFlowStep.Route(route, params)
        }
        
        val step = stepBuilder.build()
        steps[index] = step
        
        return GuidedFlowStepBuilder { _ -> step }
    }

    /**
     * Find the index of a step by route
     */
    fun findStepIndex(route: String): Int = steps.indexOfFirst { step ->
        when (step) {
            is GuidedFlowStep.Route -> step.route == route
            is GuidedFlowStep.TypedScreen -> step.screenClass == route
        }
    }

    /**
     * Check if a route is already in the flow
     */
    fun hasStep(route: String): Boolean = steps.any { step ->
        when (step) {
            is GuidedFlowStep.Route -> step.route == route
            is GuidedFlowStep.TypedScreen -> step.screenClass == route
        }
    }

    /**
     * Get current steps count
     */
    val stepCount: Int get() = steps.size

    /**
     * Clear all steps
     */
    fun clearSteps() {
        finalizePendingStep()
        steps.clear()
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
            clearModificationsOnComplete = clearBehavior,
            onComplete = onCompleteBlock
        )
    }
}

/**
 * Builder for individual guided flow steps that allows adding parameters
 */
class GuidedFlowStepBuilder(
    private val stepFactory: (Params) -> GuidedFlowStep
) {
    private var currentParams = Params.empty()

    /**
     * Add a parameter to this step
     */
    fun param(key: String, value: Any): GuidedFlowStepBuilder {
        currentParams = when (value) {
            is String -> currentParams.with(key, value)
            is Int -> currentParams.with(key, value)
            is Boolean -> currentParams.with(key, value)
            is Double -> currentParams.with(key, value)
            is Long -> currentParams.with(key, value)
            is Float -> currentParams.with(key, value)
            else -> currentParams.withTyped(key, value)
        }
        return this
    }

    /**
     * Add multiple parameters to this step
     */
    fun params(vararg pairs: Pair<String, Any>): GuidedFlowStepBuilder {
        currentParams = currentParams + Params.of(*pairs)
        return this
    }

    /**
     * Add parameters from a Params object
     */
    fun params(params: Params): GuidedFlowStepBuilder {
        currentParams = currentParams + params
        return this
    }

    /**
     * Add multiple parameters from a map
     */
    fun params(paramMap: Map<String, Any>): GuidedFlowStepBuilder {
        currentParams = currentParams + Params.fromMap(paramMap)
        return this
    }

    /**
     * Build the step with current parameters
     */
    internal fun build(): GuidedFlowStep {
        return stepFactory(currentParams)
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

