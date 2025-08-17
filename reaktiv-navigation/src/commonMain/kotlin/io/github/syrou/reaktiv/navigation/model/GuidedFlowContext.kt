package io.github.syrou.reaktiv.navigation.model

import kotlinx.serialization.Serializable

/**
 * Context information for tracking which guided flow a navigation entry belongs to
 */
@Serializable
data class GuidedFlowContext(
    val flowRoute: String,
    val stepIndex: Int,
    val totalSteps: Int
)