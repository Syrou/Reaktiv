package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.model.GuardResult

internal const val GUARD_TRACE_CLASS: String = "NavigationGuards"

internal suspend fun traceGuard(
    storeAccessor: StoreAccessor,
    methodName: String,
    targetRoute: String,
    block: suspend () -> GuardResult
): GuardResult = traceEvaluation(storeAccessor, methodName, targetRoute, ::describeGuardResult, block)

internal suspend fun traceEntrySelection(
    storeAccessor: StoreAccessor,
    methodName: String,
    targetRoute: String,
    block: suspend () -> NavigationNode
): NavigationNode = traceEvaluation(storeAccessor, methodName, targetRoute, ::describeNode, block)

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
    methodName: String,
    targetRoute: String,
    describe: (T) -> Pair<String, String>,
    block: suspend () -> T
): T {
    val instrumentation = (storeAccessor as? Store)?.activeDispatchInstrumentation ?: return block()
    val startedAt = currentTimeMillis()
    val token = instrumentation.onEvaluationStarted(
        scope = GUARD_TRACE_CLASS,
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
