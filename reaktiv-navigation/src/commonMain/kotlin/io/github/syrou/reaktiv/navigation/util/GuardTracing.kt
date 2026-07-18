package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.model.GuardResult

internal const val GUARD_TRACE_CLASS: String = "NavigationGuards"

internal suspend fun traceGuard(
    methodName: String,
    targetRoute: String,
    block: suspend () -> GuardResult
): GuardResult = traceEvaluation(methodName, targetRoute, ::describeGuardResult, block)

internal suspend fun traceEntrySelection(
    methodName: String,
    targetRoute: String,
    block: suspend () -> NavigationNode
): NavigationNode = traceEvaluation(methodName, targetRoute, ::describeNode, block)

private fun describeGuardResult(result: GuardResult): Pair<String, String> = when (result) {
    is GuardResult.Allow -> "Allow" to "GuardResult"
    is GuardResult.Reject -> "Reject" to "GuardResult"
    is GuardResult.RedirectTo -> "RedirectTo(${result.route})" to "GuardResult"
    is GuardResult.PendAndRedirectTo -> "PendAndRedirectTo(${result.route})" to "GuardResult"
}

private fun describeNode(node: NavigationNode): Pair<String, String> =
    node.route to (node::class.simpleName ?: "NavigationNode")

private suspend fun <T> traceEvaluation(
    methodName: String,
    targetRoute: String,
    describe: (T) -> Pair<String, String>,
    block: suspend () -> T
): T {
    if (!LogicTracer.active) return block()
    val startedAt = currentTimeMillis()
    val callId = LogicTracer.notifyMethodStart(
        logicClass = GUARD_TRACE_CLASS,
        methodName = methodName,
        params = mapOf("target" to targetRoute)
    )
    return try {
        val result = block()
        val (resultText, resultType) = describe(result)
        LogicTracer.notifyMethodCompleted(callId, resultText, resultType, currentTimeMillis() - startedAt)
        result
    } catch (e: Throwable) {
        LogicTracer.notifyMethodFailed(callId, e, currentTimeMillis() - startedAt)
        throw e
    }
}
