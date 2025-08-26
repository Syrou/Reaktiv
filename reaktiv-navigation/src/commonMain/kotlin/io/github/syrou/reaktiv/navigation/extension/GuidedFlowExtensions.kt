package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperationBuilder
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import kotlinx.coroutines.flow.first

/**
 * Get a guided flow definition by route
 */
suspend fun StoreAccessor.getGuidedFlow(route: String): GuidedFlowDefinition? {
    val navigationLogic = selectLogic<NavigationLogic>()
    return navigationLogic.getEffectiveGuidedFlowDefinitionByRoute(route)
}

/**
 * Start a guided flow by route
 */
suspend fun StoreAccessor.startGuidedFlow(
    route: String,
    params: Params = Params.empty()
) {
    dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(route), params))
}


/**
 * Execute multiple guided flow operations atomically
 * This is the main DSL entry point for guided flow operations
 * 
 * Example:
 * ```
 * storeAccessor.guidedFlow("signup-flow") {
 *     removeSteps(listOf(2, 3))
 *     updateStepParams(1, mapOf("userId" to "123"))
 *     nextStep()
 * }
 * ```
 */
suspend fun StoreAccessor.guidedFlow(
    flowRoute: String,
    block: suspend GuidedFlowOperationBuilder.() -> Unit
) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.executeGuidedFlowOperations(flowRoute, block)
}