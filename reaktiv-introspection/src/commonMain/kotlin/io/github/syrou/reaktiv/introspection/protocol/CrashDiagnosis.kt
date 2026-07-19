package io.github.syrou.reaktiv.introspection.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
public data class CrashDiagnosis(
    val summary: String,
    val exceptionType: String,
    val exceptionMessage: String? = null,
    val location: String? = null,
    val sourceUrl: String? = null,
    val route: String? = null,
    val afterActionIndex: Int = -1,
    val triggeringAction: String? = null,
    val recentActions: List<String> = emptyList(),
    val recentStateChanges: List<String> = emptyList(),
    val suspects: List<String> = emptyList(),
    val reproduction: String = "",
    val text: String = ""
)

public fun buildCrashDiagnosis(
    crash: CrashInfo,
    actions: List<CapturedAction>,
    logicStarted: List<LogicMethodStart>,
    logicFailed: List<LogicMethodFailed>
): CrashDiagnosis = runCatching {
    diagnose(crash, actions, logicStarted, logicFailed)
}.getOrElse {
    CrashDiagnosis(
        summary = crash.exception.exceptionType,
        exceptionType = crash.exception.exceptionType,
        exceptionMessage = crash.exception.message,
        route = crash.route,
        afterActionIndex = crash.afterActionIndex,
        reproduction = reproductionHint(crash.afterActionIndex),
        text = "Crash: ${crash.exception.exceptionType}" +
            (crash.exception.message?.let { ", $it" } ?: "")
    )
}

private fun diagnose(
    crash: CrashInfo,
    actions: List<CapturedAction>,
    logicStarted: List<LogicMethodStart>,
    logicFailed: List<LogicMethodFailed>
): CrashDiagnosis {
    val exceptionType = crash.exception.exceptionType
    val message = crash.exception.message?.takeIf { it.isNotBlank() }

    val callId = crash.callId ?: logicFailed.lastOrNull()?.callId
    val start = callId?.let { id -> logicStarted.lastOrNull { it.callId == id } }
    val logicClass = crash.logicClass ?: start?.logicClass
    val methodName = crash.methodName ?: start?.methodName
    val location = if (logicClass != null && methodName != null) {
        buildString {
            append(logicClass.substringAfterLast('.'))
            append('.')
            append(methodName)
            val file = start?.sourceFile
            if (file != null) {
                append(" (").append(file)
                start.lineNumber?.let { append(":").append(it) }
                append(")")
            }
        }
    } else {
        null
    }
    val sourceUrl = start?.githubSourceUrl

    val idx = crash.afterActionIndex
    val triggering = actions.getOrNull(idx)?.let { describeAction(it) }

    val recentFrom = if (idx in actions.indices) maxOf(0, idx - 4) else maxOf(0, actions.size - 5)
    val recentTo = if (idx in actions.indices) idx else actions.lastIndex
    val recentActions = if (actions.isNotEmpty() && recentTo >= recentFrom) {
        (recentFrom..recentTo).mapNotNull { i -> actions.getOrNull(i)?.let { describeAction(it) } }
    } else {
        emptyList()
    }

    val changeFrom = if (idx in actions.indices) maxOf(0, idx - 2) else maxOf(0, actions.size - 3)
    val changeTo = if (idx in actions.indices) idx else actions.lastIndex
    val nulled = mutableListOf<String>()
    val recentChanges = if (actions.isNotEmpty() && changeTo >= changeFrom) {
        (changeFrom..changeTo).flatMap { i ->
            val action = actions.getOrNull(i) ?: return@flatMap emptyList()
            changedFields(action).map { (field, becameNull) ->
                if (becameNull) nulled.add(field)
                field
            }
        }.distinct()
    } else {
        emptyList()
    }

    val suspects = buildList {
        val nullRelated = exceptionType.contains("Null", ignoreCase = true) ||
            (message?.contains("null", ignoreCase = true) == true)
        if (nullRelated && nulled.isNotEmpty()) {
            add("${nulled.joinToString(", ")} became null just before the crash, and this is a null-related failure.")
        }
        if (message != null) {
            val mentioned = recentChanges.filter { field ->
                val leaf = field.substringAfterLast('.')
                leaf.isNotBlank() && message.contains(leaf, ignoreCase = true)
            }
            if (mentioned.isNotEmpty()) {
                add("The error mentions ${mentioned.joinToString(", ")}, which changed in the actions right before the crash.")
            }
        }
    }

    val summary = if (location != null) "$exceptionType in $location" else exceptionType
    val reproduction = reproductionHint(idx)

    val text = buildString {
        append("Crash: ").append(exceptionType)
        message?.let { append(", ").append(it) }
        appendLine()
        when {
            location != null -> {
                append("Where: ").append(location)
                crash.route?.let { append(", route ").append(it) }
                appendLine()
            }
            crash.route != null -> append("Where: route ").append(crash.route).appendLine()
        }
        if (idx >= 0) {
            append("When: after action #").append(idx)
            triggering?.let { append(", ").append(it) }
            appendLine()
        }
        if (recentActions.isNotEmpty()) {
            append("Recent: ").append(recentActions.joinToString(" -> ")).append(" -> [crash]").appendLine()
        }
        if (recentChanges.isNotEmpty()) {
            append("State changes before crash: ").append(recentChanges.joinToString(", ")).appendLine()
        }
        suspects.forEach { append("Suspect: ").append(it).appendLine() }
        append("Reproduce: ").append(reproduction)
    }.trim()

    return CrashDiagnosis(
        summary = summary,
        exceptionType = exceptionType,
        exceptionMessage = message,
        location = location,
        sourceUrl = sourceUrl,
        route = crash.route,
        afterActionIndex = idx,
        triggeringAction = triggering,
        recentActions = recentActions,
        recentStateChanges = recentChanges,
        suspects = suspects,
        reproduction = reproduction,
        text = text
    )
}

private fun reproductionHint(idx: Int): String =
    if (idx >= 0) {
        "Import this session in DevTools and step to action #$idx to inspect the exact state at the crash."
    } else {
        "Import this session in DevTools to replay it and inspect the state."
    }

private fun describeAction(action: CapturedAction): String {
    val data = action.actionData.trim()
    return when {
        data.isNotBlank() && data.length <= 80 -> data
        data.isNotBlank() -> data.take(77) + "..."
        else -> action.actionType
    }
}

private fun changedFields(action: CapturedAction): List<Pair<String, Boolean>> {
    if (action.deltaKind != DeltaKind.FIELDS) return emptyList()
    val module = action.moduleName.substringAfterLast('.').ifBlank { "state" }
    val obj = runCatching { Json.parseToJsonElement(action.stateDeltaJson) as? JsonObject }.getOrNull()
        ?: return emptyList()
    return obj.entries
        .filter { it.key != "type" }
        .map { (key, value) -> "$module.$key" to (value is JsonNull) }
}
