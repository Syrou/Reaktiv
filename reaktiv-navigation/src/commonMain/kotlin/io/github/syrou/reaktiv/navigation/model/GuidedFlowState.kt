package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Represents the current state of an active guided flow
 * @param flowRoute GuidedFlow route identifier
 */
@Serializable
data class GuidedFlowState(
    val flowRoute: String,
    val currentStepIndex: Int = 0,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val isOnFinalStep: Boolean = false,
    val progress: Float = 0f,
    val isCompleted: Boolean = false,
    val duration: Duration? = null
)