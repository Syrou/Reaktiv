package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep

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
    private val flowRoute: String,
    private val storeAccessor: StoreAccessor
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
    fun updateStepParams(stepIndex: Int, newParams: StringAnyMap) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.UpdateStepParams(stepIndex, newParams)))
    }
    
    /**
     * Update the onComplete handler for the guided flow
     * @param onComplete New completion handler function
     */
    fun updateOnComplete(onComplete: (suspend (StoreAccessor) -> Unit)?) {
        operations.add(GuidedFlowOperation.Modify(FlowModification.UpdateOnComplete(onComplete)))
    }
    
    /**
     * Navigate to the next step in the guided flow
     * @param params Optional parameters to pass to the next step
     */
    fun nextStep(params: StringAnyMap = emptyMap()) {
        operations.add(GuidedFlowOperation.NextStep(params))
    }
    
    /**
     * Navigate to the previous step in the guided flow
     */
    fun previousStep() {
        operations.add(GuidedFlowOperation.PreviousStep)
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
 * Represents a guided flow operation that can be executed at runtime.
 * 
 * Operations are executed in the order they are added to the builder.
 * All [Modify] operations are processed before navigation operations
 * to ensure consistent state.
 */
sealed class GuidedFlowOperation {
    data class Modify(val modification: FlowModification) : GuidedFlowOperation()
    data class NextStep(val params: StringAnyMap = emptyMap()) : GuidedFlowOperation()
    object PreviousStep : GuidedFlowOperation()
}