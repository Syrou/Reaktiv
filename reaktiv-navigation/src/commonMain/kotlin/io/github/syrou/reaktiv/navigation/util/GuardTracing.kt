package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.model.GuardResult
import kotlinx.coroutines.CancellationException

internal const val GUARD_TRACE_CLASS: String = "NavigationGuards"

internal const val NAVIGATION_TRACE_CLASS: String = "Navigation"

internal suspend fun traceGuard(
    storeAccessor: StoreAccessor,
    methodName: String,
    targetRoute: String,
    block: suspend () -> GuardResult
): GuardResult =
    traceEvaluation(storeAccessor, GUARD_TRACE_CLASS, methodName, targetRoute, ::describeGuardResult, block)

internal suspend fun traceEntrySelection(
    storeAccessor: StoreAccessor,
    methodName: String,
    targetRoute: String,
    block: suspend () -> NavigationNode
): NavigationNode =
    traceEvaluation(storeAccessor, GUARD_TRACE_CLASS, methodName, targetRoute, ::describeNode, block)

internal suspend fun traceNavigation(
    storeAccessor: StoreAccessor,
    targetRoute: String,
    block: suspend () -> NavigationOutcome
): NavigationOutcome =
    try {
        traceEvaluation(
            storeAccessor,
            NAVIGATION_TRACE_CLASS,
            "navigate",
            targetRoute,
            ::describeOutcome,
            block
        ).also { ReaktivDebug.nav("navigate($targetRoute) -> $it") }
    } catch (e: CancellationException) {
        ReaktivDebug.nav("navigate($targetRoute) cancelled before it was applied")
        throw e
    }

private fun describeOutcome(outcome: NavigationOutcome): Pair<String, String> = when (outcome) {
    is NavigationOutcome.Success -> "Success" to "NavigationOutcome"
    is NavigationOutcome.Dropped -> "Dropped" to "NavigationOutcome"
    is NavigationOutcome.Rejected -> "Rejected" to "NavigationOutcome"
    is NavigationOutcome.Redirected -> "Redirected(${outcome.to})" to "NavigationOutcome"
}

private fun describeGuardResult(result: GuardResult): Pair<String, String> = when (result) {
    is GuardResult.Allow -> "Allow" to "GuardResult"
    is GuardResult.Reject -> "Reject" to "GuardResult"
    is GuardResult.RedirectTo -> "RedirectTo(${result.route})" to "GuardResult"
    is GuardResult.PendAndRedirectTo -> "PendAndRedirectTo(${result.route})" to "GuardResult"
}

private fun describeNode(node: NavigationNode): Pair<String, String> =
    node.route to (node::class.simpleName ?: "NavigationNode")

private suspend fun <T> traceEvaluation(
    storeAccessor: StoreAccessor,
    scope: String,
    methodName: String,
    targetRoute: String,
    describe: (T) -> Pair<String, String>,
    block: suspend () -> T
): T {
    val instrumentation = (storeAccessor as? Store)?.activeDispatchInstrumentation ?: return block()
    val startedAt = currentTimeMillis()
    val token = instrumentation.onEvaluationStarted(
        scope = scope,
        name = methodName,
        params = mapOf("target" to targetRoute)
    )
    return try {
        val result = block()
        val (resultText, resultType) = describe(result)
        instrumentation.onEvaluationCompleted(token, resultText, resultType, currentTimeMillis() - startedAt)
        result
    } catch (e: Throwable) {
        instrumentation.onEvaluationFailed(token, e, currentTimeMillis() - startedAt)
        throw e
    }
}
