package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture

public enum class FindingSeverity { WARNING, CRITICAL }

public data class Finding(
    val severity: FindingSeverity,
    val category: String,
    val title: String,
    val detail: String,
    val timestampMs: Long? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val githubUrl: String? = null
)

@Deprecated(
    "Duplicate of DISPATCH_QUEUE_WAIT_WARN_MS, which carries the same threshold.",
    ReplaceWith("DISPATCH_QUEUE_WAIT_WARN_MS"),
    DeprecationLevel.WARNING
)
public const val FINDING_QUEUE_WAIT_WARN_MS: Long = DISPATCH_QUEUE_WAIT_WARN_MS
public const val FINDING_REDUCER_WARN_MS: Long = 8L
public const val FINDING_CHURN_WARN_EVENTS: Int = 50
public const val FINDING_STORM_EVENTS: Int = 20
public const val FINDING_STORM_WINDOW_MS: Long = 1000L

public fun Finding.asClipboardText(): String = buildString {
    append('[').append(severity.name).append("] ")
    append(category).append(": ").append(title)
    append(" - ").append(detail)
    val location = sourceFile?.let { file -> lineNumber?.let { "$file:$it" } ?: file }
    if (location != null) {
        append(" (").append(location).append(')')
    }
}

public fun computeFindings(
    starts: List<LogicMethodStart>,
    completions: List<LogicMethodCompleted>,
    sizes: List<ModuleSizeStats> = emptyList(),
    churn: List<ChurnEntry> = emptyList(),
    network: List<NetworkRequestCapture> = emptyList()
): List<Finding> {
    val findings = mutableListOf<Finding>()
    val completionsByCallId = completions.associateBy { it.callId }

    for (start in starts) {
        if (start.logicClass != STALL_TRACE_CLASS || start.methodName != "stall") continue
        val completion = completionsByCallId[start.callId] ?: continue
        val stallEnd = completion.timestampMs
        val stallStart = stallEnd - completion.durationMs
        val culprit = starts
            .filter { candidate ->
                candidate.logicClass !in SYNTHETIC_TRACE_CLASSES &&
                    candidate.thread?.let { isMainThread(it) } == true &&
                    candidate.timestampMs <= stallEnd &&
                    (completionsByCallId[candidate.callId]?.timestampMs ?: stallEnd) >= stallStart
            }
            .maxByOrNull { completionsByCallId[it.callId]?.durationMs ?: Long.MAX_VALUE }
        val location = start.params["hottestFrame"]
            ?: culprit?.let { "${it.logicClass.substringAfterLast('.')}.${it.methodName}" }
        findings.add(
            Finding(
                severity = FindingSeverity.CRITICAL,
                category = "stall",
                title = "Main thread froze for ${completion.durationMs}ms",
                detail = location?.let { "Culprit: $it" } ?: "No culprit identified",
                timestampMs = stallEnd,
                sourceFile = culprit?.sourceFile,
                lineNumber = culprit?.lineNumber,
                githubUrl = culprit?.githubSourceUrl
            )
        )
    }

    starts.filter { it.logicClass == REDACTION_TRACE_CLASS }.forEach { start ->
        findings.add(
            Finding(
                severity = FindingSeverity.WARNING,
                category = "redaction",
                title = "Capture redaction issue",
                detail = start.params["detail"] ?: "unknown",
                timestampMs = start.timestampMs
            )
        )
    }

    val slowWaits = starts.filter {
        it.logicClass == DISPATCH_TRACE_CLASS &&
            (it.params["queueWaitMs"]?.toLongOrNull() ?: 0L) >= DISPATCH_QUEUE_WAIT_WARN_MS
    }
    slowWaits.maxByOrNull { it.params["queueWaitMs"]?.toLongOrNull() ?: 0L }?.let { worst ->
        findings.add(
            Finding(
                severity = FindingSeverity.WARNING,
                category = "dispatch-latency",
                title = "${slowWaits.size} dispatches waited ${DISPATCH_QUEUE_WAIT_WARN_MS}ms or more",
                detail = "Worst: ${worst.methodName} waited ${worst.params["queueWaitMs"]}ms",
                timestampMs = worst.timestampMs
            )
        )
    }

    starts.filter { it.logicClass == PHASE_TRACE_CLASS && it.methodName == "reducer" }
        .let { reducerStarts ->
            val worst = reducerStarts.maxByOrNull {
                completionsByCallId[it.callId]?.durationMs ?: 0L
            } ?: return@let
            val worstMs = completionsByCallId[worst.callId]?.durationMs ?: 0L
            if (worstMs < FINDING_REDUCER_WARN_MS) return@let
            findings.add(
                Finding(
                    severity = FindingSeverity.CRITICAL,
                    category = "dispatch-phase",
                    title = "Slow reducer",
                    detail = "Worst ${worstMs}ms on ${worst.params["actionType"]} " +
                        "across ${reducerStarts.size} occurrences at 4ms or more",
                    timestampMs = worst.timestampMs
                )
            )
        }

    starts.filter { it.logicClass == DISPATCH_TRACE_CLASS }
        .groupBy { it.methodName }
        .forEach { (actionType, dispatches) ->
            val sorted = dispatches.sortedBy { it.timestampMs }
            var windowStart = 0
            var maxInWindow = 0
            var peakIndex = 0
            sorted.forEachIndexed { index, dispatch ->
                while (dispatch.timestampMs - sorted[windowStart].timestampMs > FINDING_STORM_WINDOW_MS) {
                    windowStart += 1
                }
                val inWindow = index - windowStart + 1
                if (inWindow > maxInWindow) {
                    maxInWindow = inWindow
                    peakIndex = index
                }
            }
            if (maxInWindow < FINDING_STORM_EVENTS) return@forEach
            val origin = sorted.take(peakIndex + 1)
                .lastOrNull { it.params["dispatchedFrom"] != null }
                ?.params?.get("dispatchedFrom")
            findings.add(
                Finding(
                    severity = FindingSeverity.WARNING,
                    category = "dispatch-storm",
                    title = "$actionType dispatched $maxInWindow times within a second",
                    detail = origin?.let { "Dispatched from $it" } ?: "No dispatch origin recorded",
                    timestampMs = sorted[peakIndex].timestampMs
                )
            )
        }

    sizes.filter { it.isSuspicious }.forEach { size ->
        val fieldDetail = size.topGrowingField?.let {
            "Fastest growing field: $it (+${size.topGrowingFieldGrowthBytes} bytes)"
        } ?: "No single field identified"
        findings.add(
            Finding(
                severity = FindingSeverity.CRITICAL,
                category = "state-size",
                title = "${size.shortName} grew ${size.growthPercent}% and keeps growing",
                detail = fieldDetail
            )
        )
    }

    churn.filter { it.changeEvents >= FINDING_CHURN_WARN_EVENTS }.take(3).forEach { entry ->
        findings.add(
            Finding(
                severity = FindingSeverity.WARNING,
                category = "recomposition",
                title = "${entry.shortComposable} recomposes on ${entry.changeEvents} state changes",
                detail = "Reads: ${entry.statesRead.joinToString()}"
            )
        )
    }

    network.filter { it.decodeError != null }
        .groupBy { "${it.method} ${it.url}" to it.decodeError }
        .forEach { (key, exchanges) ->
            val (endpoint, decodeError) = key
            val latest = exchanges.maxBy { it.startedAtMs }
            val repeated = if (exchanges.size > 1) " (${exchanges.size} times)" else ""
            findings.add(
                Finding(
                    severity = FindingSeverity.CRITICAL,
                    category = "network-decode",
                    title = "Response did not match the expected type$repeated",
                    detail = "$endpoint responded ${latest.responseStatus ?: "?"} but decoding failed: $decodeError",
                    timestampMs = latest.startedAtMs + latest.durationMs
                )
            )
        }

    return findings.sortedWith(
        compareBy<Finding> { it.severity != FindingSeverity.CRITICAL }.thenByDescending { it.timestampMs ?: 0L }
    )
}
