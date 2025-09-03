package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.dsl.GuidedFlowOperationBuilder
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.param.Params

/**
 * Get a guided flow definition by route
 */
suspend fun StoreAccessor.getGuidedFlow(route: String): GuidedFlowDefinition? {
    val navigationLogic = selectLogic<NavigationLogic>()
    return navigationLogic.getEffectiveGuidedFlowDefinitionByRoute(route)
}

/**
 * Start a guided flow by route
 * This method is suspending and waits for the flow state to be properly initialized
 */
suspend fun StoreAccessor.startGuidedFlow(
    route: String,
    params: Params = Params.empty()
) {
    this.selectLogic<NavigationLogic>().startGuidedFlow(route, params)
}

/**
 * Navigate to the next step in the currently active guided flow
 * This method is suspending and waits for the navigation to complete
 */
suspend fun StoreAccessor.nextGuidedFlowStep(
    params: Params = Params.empty()
) {
    this.selectLogic<NavigationLogic>().nextGuidedFlowStep(params)
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