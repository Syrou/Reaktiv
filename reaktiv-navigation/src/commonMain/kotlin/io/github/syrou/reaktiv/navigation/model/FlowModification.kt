package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents modifications that can be applied to a guided flow at runtime
 */
@Serializable
sealed class FlowModification {
    
    /**
     * Add steps to the guided flow
     * @param insertIndex -1 means append at end
     */
    @Serializable
    data class AddSteps(
        val steps: List<GuidedFlowStep>,
        val insertIndex: Int = -1
    ) : FlowModification()
    
    /**
     * Remove steps from the guided flow
     */
    @Serializable
    data class RemoveSteps(
        val stepIndices: List<Int>
    ) : FlowModification()
    
    /**
     * Replace a specific step in the guided flow
     */
    @Serializable
    data class ReplaceStep(
        val stepIndex: Int,
        val newStep: GuidedFlowStep
    ) : FlowModification()
    
    /**
     * Update parameters for a specific step in the guided flow
     */
    @Serializable
    data class UpdateStepParams(
        val stepIndex: Int,
        val newParams: Params
    ) : FlowModification()
    
    /**
     * Update the onComplete block for the guided flow
     */
    @Serializable
    data class UpdateOnComplete(
        @Transient
        val onComplete: (suspend NavigationBuilder.(StoreAccessor) -> Unit)? = null
    ) : FlowModification()
}

/**
 * Apply a flow modification to a guided flow definition
 */
fun GuidedFlowDefinition.applyModification(modification: FlowModification): GuidedFlowDefinition {
    return when (modification) {
        is FlowModification.AddSteps -> {
            val insertIndex = if (modification.insertIndex == -1) steps.size else modification.insertIndex
            val newSteps = steps.toMutableList().apply {
                addAll(insertIndex, modification.steps)
            }
            copy(steps = newSteps)
        }
        is FlowModification.RemoveSteps -> {
            val newSteps = steps.filterIndexed { index, _ -> 
                index !in modification.stepIndices 
            }
            copy(steps = newSteps)
        }
        is FlowModification.ReplaceStep -> {
            val newSteps = steps.toMutableList().apply {
                if (modification.stepIndex in indices) {
                    set(modification.stepIndex, modification.newStep)
                }
            }
            copy(steps = newSteps)
        }
        is FlowModification.UpdateStepParams -> {
            val newSteps = steps.mapIndexed { index, step ->
                if (index == modification.stepIndex) {
                    when (step) {
                        is GuidedFlowStep.Route -> step.copy(params = modification.newParams)
                        is GuidedFlowStep.TypedScreen -> step.copy(params = modification.newParams)
                    }
                } else {
                    step
                }
            }
            copy(steps = newSteps)
        }
        is FlowModification.UpdateOnComplete -> {
            copy(onComplete = modification.onComplete)
        }
    }
}