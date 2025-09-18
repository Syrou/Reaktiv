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



