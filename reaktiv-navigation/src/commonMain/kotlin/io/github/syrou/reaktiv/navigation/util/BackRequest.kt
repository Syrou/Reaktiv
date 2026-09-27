package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.DismissAction
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.coroutines.flow.first

internal sealed class BackDecision {
    data class Pop(val unwindTo: NavigationEntry?) : BackDecision()
    data object Refuse : BackDecision()
    data class Run(val handler: suspend StoreAccessor.() -> Unit) : BackDecision()
}

internal fun decideBack(
    top: NavigationEntry,
    revealed: NavigationEntry?,
    source: DismissSource,
    navModule: NavigationModule
): BackDecision = decideBack(top, revealed, source, navModule.getGraphDefinitions())

internal fun decideBack(
    top: NavigationEntry,
    revealed: NavigationEntry?,
    source: DismissSource,
    graphDefinitions: Map<String, NavigationGraph>
): BackDecision {
    val boundary = dismissableBoundary(top, graphDefinitions)
    val leavesGraph = boundary != null &&
        revealed != null &&
        dismissableBoundary(revealed, graphDefinitions) != boundary

    val screenAction = top.navigatable.dismissal[source]
    val action = if (leavesGraph) {
        val graphAction = graphDefinitions[boundary]?.declaration?.dismissal?.get(source)
        when {
            graphAction != null && graphAction !is DismissAction.Pop -> graphAction
            screenAction is DismissAction.Run -> screenAction
            else -> DismissAction.Pop
        }
    } else {
        screenAction
    }

    return when (action) {
        DismissAction.Pop -> BackDecision.Pop(unwindTo = if (leavesGraph) revealed else null)
        DismissAction.Ignore -> BackDecision.Refuse
        is DismissAction.Run -> BackDecision.Run(action.handler)
    }
}

internal suspend fun performUserBack(
    store: StoreAccessor,
    navModule: NavigationModule,
    top: NavigationEntry,
    revealed: NavigationEntry?,
    source: DismissSource,
    expectedTopKey: String? = null
) {
    when (val decision = decideBack(top, revealed, source, navModule)) {
        is BackDecision.Pop -> {
            val unwindTo = decision.unwindTo
            if (unwindTo != null) {
                store.navigation { popUpTo(unwindTo.location, inclusive = false) }
            } else {
                store.navigation { navigateBack(expectedTopKey) }
            }
        }
        BackDecision.Refuse -> Unit
        is BackDecision.Run -> decision.handler(store)
    }
}

internal suspend fun performUserBack(store: StoreAccessor, navModule: NavigationModule, source: DismissSource) {
    val state = store.selectState<NavigationState>().first()
    if (!canHandleBack(state)) return
    performUserBack(store, navModule, state.currentEntry, state.revealedEntry, source)
}
