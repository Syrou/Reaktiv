package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction

public data class ChurnEntry(
    val composable: String,
    val statesRead: List<String>,
    val changeEvents: Int
) {
    val shortComposable: String get() = composable.substringAfterLast('.')
}

public fun aggregateChurn(
    actions: List<CapturedAction>,
    reads: List<StateRead>
): List<ChurnEntry> {
    val changesByState = actions
        .filter { it.moduleName.isNotBlank() }
        .groupingBy { it.moduleName.substringAfterLast('.') }
        .eachCount()
    return reads
        .groupBy { it.composable }
        .map { (composable, composableReads) ->
            val states = composableReads.map { it.stateClass.substringAfterLast('.') }.distinct()
            ChurnEntry(
                composable = composable,
                statesRead = states,
                changeEvents = states.sumOf { changesByState[it] ?: 0 }
            )
        }
        .sortedByDescending { it.changeEvents }
}
