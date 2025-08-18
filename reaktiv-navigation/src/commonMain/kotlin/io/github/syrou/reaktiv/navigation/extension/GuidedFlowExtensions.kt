package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import kotlinx.coroutines.flow.first

/**
 * Get a guided flow definition by route
 */
suspend fun StoreAccessor.getGuidedFlow(route: String): GuidedFlowDefinition? {
    val navigationState = selectState<NavigationState>().first()
    return navigationState.guidedFlowDefinitions[route]
}

/**
 * Start a guided flow by route
 */
suspend fun StoreAccessor.startGuidedFlow(
    route: String,
    params: Map<String, Any> = emptyMap()
) {
    dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(route), params))
}

/**
 * Update onComplete block for a guided flow
 */
suspend fun StoreAccessor.updateGuidedFlowCompletion(
    route: String,
    onComplete: (suspend NavigationBuilder.() -> Unit)?
) {
    dispatch(
        NavigationAction.ModifyGuidedFlow(
            flowRoute = route,
            modification = FlowModification.UpdateOnComplete(onComplete)
        )
    )
}

/**
 * Modify a guided flow with any type of modification
 */
suspend fun StoreAccessor.modifyGuidedFlow(
    route: String,
    modification: FlowModification
) {
    dispatch(
        NavigationAction.ModifyGuidedFlow(
            flowRoute = route,
            modification = modification
        )
    )
}