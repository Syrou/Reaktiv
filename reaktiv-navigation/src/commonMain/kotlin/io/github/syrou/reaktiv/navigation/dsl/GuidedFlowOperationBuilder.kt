package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep
import io.github.syrou.reaktiv.navigation.param.SerializableParam
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Builder for guided flow operations that can be executed atomically at runtime.
 * 
 * Provides a DSL for modifying guided flows and performing navigation actions
 * within a single atomic operation. All modifications are applied before navigation
 * actions to ensure consistent state.
 * 
 * Example usage:
 * ```kotlin
 * store.guidedFlow("user-onboarding") {
 *     removeSteps(listOf(2, 3))
 *     updateStepParams(1, mapOf("userId" to "123"))
 *     nextStep()
 * }
 * ```
 * 
 * @param flowRoute The route identifier for the guided flow
 * @param storeAccessor Accessor for the store to execute operations
 */
class GuidedFlowOperationBuilder(
    @PublishedApi
    internal val flowRoute: String,
    @PublishedApi
    internal val storeAccessor: StoreAccessor
) {
    private val operations = mutableListOf<GuidedFlowOperation>()
    
    /**
     * Add steps to the guided flow
     * @param steps List of steps to add
     * @param insertIndex Position to insert steps, -1 to append at end
     */
    fun addSteps(steps: List<GuidedFlowStep>, insertIndex: Int = -1) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.AddSteps(steps, insertIndex)))
    }
    
    /**
     * Remove steps from the guided flow
     * @param stepIndices List of step indices to remove
     */
    fun removeSteps(stepIndices: List<Int>) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.RemoveSteps(stepIndices)))
    }
    
    /**
     * Replace a step in the guided flow
     * @param stepIndex Index of the step to replace
     * @param newStep New step to replace with
     */
    fun replaceStep(stepIndex: Int, newStep: GuidedFlowStep) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.ReplaceStep(stepIndex, newStep)))
    }
    
    /**
     * Update parameters for a step in the guided flow
     * @param stepIndex Index of the step to update
     * @param newParams New parameters to set
     */
    fun updateStepParams(stepIndex: Int, newParams: Params) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.UpdateStepParams(stepIndex, newParams)))
    }
    
    /**
     * Update the onComplete handler for the guided flow
     * @param onComplete New completion handler function
     */
    fun updateOnComplete(onComplete: (suspend NavigationBuilder.(StoreAccessor) -> Unit)?) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.UpdateOnComplete(onComplete)))
    }
    
    /**
     * Navigate to the next step in the guided flow
     * @param params Optional parameters to pass to the next step
     */
    fun nextStep(params: Params = Params.empty()) {
        operations.add(GuidedFlowOperation.NextStep(params))
    }
    
    
    /**
     * Find step index by Screen type in the current guided flow
     * @return Index of the step, or -1 if not found
     */
    suspend inline fun <reified T : Screen> findStepByType(): Int {
        val className = T::class.qualifiedName ?: return -1
        return findStepByType(className)
    }
    
    /**
     * Find step index by screen class name in the current guided flow
     * @param screenClassName Fully qualified class name of the screen
     * @return Index of the step, or -1 if not found
     */
    suspend fun findStepByType(screenClassName: String): Int {
        val navigationLogic = storeAccessor.selectLogic<NavigationLogic>()
        val flowDefinition = navigationLogic.getEffectiveGuidedFlowDefinitionByRoute(flowRoute) ?: return -1
        val navigationState = storeAccessor.selectState<NavigationState>().first()
        
        return flowDefinition.steps.indexOfFirst { step ->
            when (step) {
                is GuidedFlowStep.TypedScreen -> step.screenClass == screenClassName
                is GuidedFlowStep.Route -> {
                    // Check if this route step corresponds to the target screen type
                    val baseRoute = step.route.split('?')[0]
                    navigationState.allAvailableNavigatables.values
                        .filterIsInstance<Screen>()
                        .any { screen -> 
                            screen.route == baseRoute && screen::class.qualifiedName == screenClassName
                        }
                }
            }
        }
    }
    
    /**
     * Remove a step from the guided flow by Screen type
     * @throws IllegalArgumentException if the screen type is not found in the flow
     */
    suspend inline fun <reified T : Screen> removeStep() {
        val stepIndex = findStepByType<T>()
        if (stepIndex < 0) {
            throw IllegalArgumentException("Screen type ${T::class.simpleName} not found in guided flow '$flowRoute'")
        }
        removeSteps(listOf(stepIndex))
    }
    
    /**
     * Remove a step from the guided flow by Screen type, returns true if found and removed
     * @return true if the step was found and removed, false if not found
     */
    suspend inline fun <reified T : Screen> removeStepIfExists(): Boolean {
        val stepIndex = findStepByType<T>()
        return if (stepIndex >= 0) {
            removeSteps(listOf(stepIndex))
            true
        } else {
            false
        }
    }
    
    /**
     * Replace a step in the guided flow by Screen type
     * @param newStep New step to replace with
     * @throws IllegalArgumentException if the screen type is not found in the flow
     */
    suspend inline fun <reified T : Screen> replaceStep(newStep: GuidedFlowStep) {
        val stepIndex = findStepByType<T>()
        if (stepIndex < 0) {
            throw IllegalArgumentException("Screen type ${T::class.simpleName} not found in guided flow '$flowRoute'")
        }
        replaceStep(stepIndex, newStep)
    }
    
    /**
     * Replace a step in the guided flow by Screen type, returns true if found and replaced
     * @param newStep New step to replace with
     * @return true if the step was found and replaced, false if not found
     */
    suspend inline fun <reified T : Screen> replaceStepIfExists(newStep: GuidedFlowStep): Boolean {
        val stepIndex = findStepByType<T>()
        return if (stepIndex >= 0) {
            replaceStep(stepIndex, newStep)
            true
        } else {
            false
        }
    }
    
    /**
     * Update parameters for a step by Screen type using typed parameters
     * @param paramBuilder Builder for typed parameters, similar to navigation{} DSL
     * @throws IllegalArgumentException if the screen type is not found in the flow
     */
    suspend inline fun <reified T : Screen> updateStepParams(
        noinline paramBuilder: TypedParameterBuilder.() -> Unit
    ) {
        val stepIndex = findStepByType<T>()
        if (stepIndex < 0) {
            throw IllegalArgumentException("Screen type ${T::class.simpleName} not found in guided flow '$flowRoute'")
        }
        val builder = TypedParameterBuilder()
        paramBuilder(builder)
        updateStepParams(stepIndex, Params.fromMap(builder.buildParams()))
    }
    
    /**
     * Update parameters for a step by Screen type with raw parameters
     * @param newParams New parameters to set
     * @throws IllegalArgumentException if the screen type is not found in the flow
     */
    suspend inline fun <reified T : Screen> updateStepParams(newParams: Params) {
        val stepIndex = findStepByType<T>()
        if (stepIndex < 0) {
            throw IllegalArgumentException("Screen type ${T::class.simpleName} not found in guided flow '$flowRoute'")
        }
        updateStepParams(stepIndex, newParams)
    }
    
    /**
     * Update parameters for a step by Screen type using typed parameters, returns true if found and updated
     * @param paramBuilder Builder for typed parameters, similar to navigation{} DSL
     * @return true if the step was found and updated, false if not found
     */
    suspend inline fun <reified T : Screen> updateStepParamsIfExists(
        noinline paramBuilder: TypedParameterBuilder.() -> Unit
    ): Boolean {
        val stepIndex = findStepByType<T>()
        return if (stepIndex >= 0) {
            val builder = TypedParameterBuilder()
            paramBuilder(builder)
            updateStepParams(stepIndex, Params.fromMap(builder.buildParams()))
            true
        } else {
            false
        }
    }
    
    /**
     * Update parameters for a step by Screen type with raw parameters, returns true if found and updated
     * @param newParams New parameters to set
     * @return true if the step was found and updated, false if not found
     */
    suspend inline fun <reified T : Screen> updateStepParamsIfExists(newParams: Params): Boolean {
        val stepIndex = findStepByType<T>()
        return if (stepIndex >= 0) {
            updateStepParams(stepIndex, newParams)
            true
        } else {
            false
        }
    }
    
    /**
     * Get all collected operations
     */
    internal fun getOperations(): List<GuidedFlowOperation> = operations.toList()
    
    /**
     * Get the flow route
     */
    internal fun getFlowRoute(): String = flowRoute
    
    /**
     * Validate all operations
     */
    internal fun validate() {
        // Basic validation - ensure we have at least one operation
        if (operations.isEmpty()) {
            throw IllegalStateException("No guided flow operations defined")
        }
    }
}

/**
 * Builder for creating typed parameters for guided flow steps.
 * Provides the same typed parameter support as the navigation{} DSL.
 * 
 * Usage:
 * ```kotlin
 * updateStepParams<UserProfileScreen> {
 *     put("userId", "123")
 *     put("user", user)
 *     putString("tab", "settings")
 * }
 * ```
 */
class TypedParameterBuilder {
    @PublishedApi
    internal val params = mutableMapOf<String, Any>()

    /**
     * Add a typed parameter with automatic serialization
     */
    inline fun <reified T> put(key: String, value: T): TypedParameterBuilder {
        params[key] = SerializableParam(value, serializer<T>())
        return this
    }

    /**
     * Add a parameter with explicit serializer
     */
    fun <T> put(key: String, value: T, serializer: KSerializer<T>): TypedParameterBuilder {
        params[key] = SerializableParam(value, serializer)
        return this
    }

    /**
     * Add a raw parameter (no serialization)
     */
    fun putRaw(key: String, value: Any): TypedParameterBuilder {
        params[key] = value
        return this
    }

    // Convenience methods for common types
    fun putString(key: String, value: String) = putRaw(key, value)
    fun putInt(key: String, value: Int) = putRaw(key, value)
    fun putBoolean(key: String, value: Boolean) = putRaw(key, value)
    fun putDouble(key: String, value: Double) = putRaw(key, value)
    fun putLong(key: String, value: Long) = putRaw(key, value)
    fun putFloat(key: String, value: Float) = putRaw(key, value)

    // Shorter alias
    fun param(key: String, value: Any) = putRaw(key, value)

    /**
     * Build the final parameter map
     */
    @PublishedApi
    internal fun buildParams(): Map<String, Any> = params.toMap()
}

/**
 * Represents a guided flow operation that can be executed at runtime.
 * 
 * Operations are executed in the order they are added to the builder.
 * All [Modify] operations are processed before navigation operations
 * to ensure consistent state.
 */
sealed class GuidedFlowOperation {
    data class Modify(val modification: FlowModification) : GuidedFlowOperation()
    data class NextStep(val params: Params = Params.empty()) : GuidedFlowOperation()
}