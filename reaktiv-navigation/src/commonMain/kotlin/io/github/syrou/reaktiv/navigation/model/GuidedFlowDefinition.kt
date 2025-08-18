package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Blueprint for creating a guided flow with multiple steps.
 * Defines the flow coordinator, the sequence of steps to navigate through,
 * and the completion behavior.
 */
@Serializable
data class GuidedFlowDefinition(
    val guidedFlow: GuidedFlow,
    val steps: List<GuidedFlowStep>,
    @Transient
    val onComplete: (suspend (StoreAccessor) -> Unit)? = null
)