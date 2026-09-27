package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.devtools.DevToolsInternalApi

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture

@DevToolsInternalApi
public enum class FindingSeverity { WARNING, CRITICAL }

@DevToolsInternalApi
public data class Finding(
    val severity: FindingSeverity,
    val category: String,
    val title: String,
    val detail: String,
    val timestampMs: Long? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val githubUrl: String? = null,
    val advice: FindingAdvice? = null,
    val culprits: List<String> = emptyList(),
    val stacks: List<FindingStack> = emptyList()
)

@DevToolsInternalApi
public data class FindingAdvice(val meaning: String, val impact: String, val fix: String)

@DevToolsInternalApi
public data class FindingStack(val label: String, val stack: String)

@DevToolsInternalApi
public val PERFORMANCE_FINDING_CATEGORIES: Set<String> = setOf(
    "stall",
    "congestion",
    "contention",
    "dispatch-latency",
    "dispatch-phase",
    "dispatch-storm",
    "state-size"
)

private val STALL_ADVICE = FindingAdvice(
    meaning = "The main thread stopped making progress long enough to be noticed, so something ran a blocking " +
        "or long operation on it instead of yielding. The stack is where the main thread was when the freeze " +
        "was detected, so its topmost frames are what blocked the UI.",
    impact = "While the main thread is blocked nothing renders and no input is handled, so the app looks " +
        "frozen and Android can raise an ANR.",
    fix = "Move the blocking work off Main: do it inside a ModuleLogic method wrapped in " +
        "withContext(Dispatchers.Default), keep reducers pure, and dispatch a result action when it finishes so " +
        "the UI only ever renders state, never computes it."
)

private val CONGESTION_ADVICE = FindingAdvice(
    meaning = "A new call to this logic method started before the previous one finished, so several ran at once.",
    impact = "Overlapping calls duplicate work and can race on shared resources, and when they interleave on one " +
        "thread they starve each other, wasting time and risking inconsistent state.",
    fix = "Conflate the trigger: debounce inside the logic method, collapse rapid updates into one action, or " +
        "hold a Mutex in the ModuleLogic so calls serialize. Move IO onto Dispatchers.Default so genuinely " +
        "parallel calls stop interleaving on one thread."
)

private val CONTENTION_ADVICE = FindingAdvice(
    meaning = "Several different logic methods were running on the same thread at the same time.",
    impact = "A single thread makes progress on only one at a time, so they take turns and each finishes later " +
        "than it would alone, adding latency across unrelated features.",
    fix = "Give the store a multi-threaded pool via createStore { coroutineContext(Dispatchers.Default) }, or " +
        "move the competing ModuleLogic work into their own withContext(Dispatchers.IO) blocks so one thread is " +
        "not oversubscribed."
)

private val QUEUE_ADVICE = FindingAdvice(
    meaning = "Actions piled up in the store's single ordered dispatch channel faster than reducers drained them.",
    impact = "Every dispatched action waits behind the backlog, so state updates and the UI lag behind input " +
        "even when the reducers themselves are simple.",
    fix = "Keep reducers pure and O(1), move side effects out of middleware into ModuleLogic, batch " +
        "high-frequency dispatches into fewer actions, and avoid dispatchAndAwait in hot loops since it makes " +
        "the producer wait for the reducer."
)

private val SLOW_REDUCER_ADVICE = FindingAdvice(
    meaning = "A reducer took long enough to hold up every action queued behind it.",
    impact = "Reducers run one at a time in the store's dispatch channel, so a slow one delays all state " +
        "updates and the UI with them.",
    fix = "Keep reducers to copying state. Move parsing, sorting and other work into ModuleLogic and dispatch " +
        "the result."
)

private val STORM_ADVICE = FindingAdvice(
    meaning = "The same action was dispatched many times within one second.",
    impact = "Every dispatch runs the reducers, notifies observers and is captured, so a burst floods the " +
        "pipeline and recomposes the UI again and again.",
    fix = "Dispatch once for the burst: debounce or conflate the source in ModuleLogic, or batch the changes " +
        "into one action."
)

private val GROWTH_ADVICE = FindingAdvice(
    meaning = "This ModuleState has grown steadily all session, the signature of an append-only collection " +
        "that is never trimmed.",
    impact = "Reaktiv persists, replicates and captures full state, so unbounded growth inflates every " +
        "snapshot, delta and crash file, slows serialization and eventually leaks memory.",
    fix = "Cap or prune the collection in the reducer, or keep large data in a ModuleLogic-owned repository " +
        "and store only ids in state."
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

@DevToolsInternalApi
public fun Finding.asClipboardText(): String = buildString {
    append('[').append(severity.name).append("] ")
    append(category).append(": ").append(title)
    append(" - ").append(detail)
    val location = sourceFile?.let { file -> lineNumber?.let { "$file:$it" } ?: file }
    if (location != null) {
        append(" (").append(location).append(')')
    }
}

@DevToolsInternalApi
public fun computeFindings(
    starts: List<LogicMethodStart>,
    completions: List<LogicMethodCompleted>,
    sizes: List<ModuleSizeStats> = emptyList(),
    churn: List<ChurnEntry> = emptyList(),
    network: List<NetworkRequestCapture> = emptyList(),
    failures: List<LogicMethodFailed> = emptyList()
): List<Finding> {
    val findings = mutableListOf<Finding>()
    val completionsByCallId = completions.associateBy { it.callId }

    for (start in starts) {
        if (start.kind != SpanKind.STALL || start.methodName != "stall") continue
        val completion = completionsByCallId[start.callId] ?: continue
        val stallEnd = completion.timestampMs
        val stallStart = stallEnd - completion.durationMs
        val culprit = starts
            .filter { candidate ->
                !candidate.kind.pipeline &&
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
                githubUrl = culprit?.githubSourceUrl,
                advice = STALL_ADVICE,
                culprits = listOfNotNull(culprit?.let { "${it.logicClass}.${it.methodName}" }),
                stacks = listOfNotNull(
                    start.params["stack"]?.takeIf { it.isNotBlank() }?.let { FindingStack("Main thread when it froze", it) }
                )
            )
        )
    }

    aggregateLogicStats(starts, completions, failures)
        .filter { it.isCongested && it.kind != SpanKind.DISPATCH && it.kind != SpanKind.STALL }
        .forEach { stat ->
            findings.add(
                Finding(
                    severity = FindingSeverity.WARNING,
                    category = "congestion",
                    title = "${stat.methodIdentifier} ran ${stat.maxConcurrent} calls at once",
                    detail = stat.congestionReason ?: "${stat.maxConcurrent} concurrent calls",
                    sourceFile = stat.sourceFile,
                    lineNumber = stat.lineNumber,
                    githubUrl = stat.githubSourceUrl,
                    advice = CONGESTION_ADVICE,
                    culprits = listOf(stat.methodIdentifier)
                )
            )
        }

    aggregateThreadStats(starts, completions, failures)
        .filter { it.isCongested }
        .forEach { thread ->
            findings.add(
                Finding(
                    severity = FindingSeverity.WARNING,
                    category = "contention",
                    title = "${thread.thread} ran ${thread.maxConcurrent} logic calls at once",
                    detail = thread.contentionReason ?: "${thread.maxConcurrent} overlapping calls",
                    advice = CONTENTION_ADVICE,
                    culprits = thread.contenders.toList()
                )
            )
        }

    starts.filter { it.kind == SpanKind.CAPTURE_ISSUE }.forEach { start ->
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
        it.kind == SpanKind.DISPATCH &&
            (it.params["queueWaitMs"]?.toLongOrNull() ?: 0L) >= DISPATCH_QUEUE_WAIT_WARN_MS
    }
    slowWaits.maxByOrNull { it.params["queueWaitMs"]?.toLongOrNull() ?: 0L }?.let { worst ->
        findings.add(
            Finding(
                severity = FindingSeverity.WARNING,
                category = "dispatch-latency",
                title = "${slowWaits.size} dispatches waited ${DISPATCH_QUEUE_WAIT_WARN_MS}ms or more",
                detail = "Worst: ${worst.methodName} waited ${worst.params["queueWaitMs"]}ms",
                timestampMs = worst.timestampMs,
                advice = QUEUE_ADVICE
            )
        )
    }

    starts.filter { it.kind == SpanKind.PHASE && it.methodName == "reducer" }
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
                    timestampMs = worst.timestampMs,
                    advice = SLOW_REDUCER_ADVICE
                )
            )
        }

    starts.filter { it.kind == SpanKind.DISPATCH }
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
                    timestampMs = sorted[peakIndex].timestampMs,
                    advice = STORM_ADVICE
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
                detail = fieldDetail,
                advice = GROWTH_ADVICE
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
