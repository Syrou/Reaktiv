package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import kotlin.time.Duration

/**
 * Computes all derived properties for a GuidedFlowState
 */
fun computeGuidedFlowProperties(
    baseState: GuidedFlowState,
    definition: GuidedFlowDefinition
): GuidedFlowState {
    val isOnFinalStep = baseState.currentStepIndex == definition.steps.size - 1
    val progress = if (definition.steps.isEmpty()) 1f 
                  else (baseState.currentStepIndex + 1).toFloat() / definition.steps.size
    val isCompleted = baseState.completedAt != null
    val duration = baseState.completedAt?.let { completedAt ->
        completedAt - baseState.startedAt
    }
    
    return baseState.copy(
        isOnFinalStep = isOnFinalStep,
        progress = progress,
        isCompleted = isCompleted,
        duration = duration
    )
}